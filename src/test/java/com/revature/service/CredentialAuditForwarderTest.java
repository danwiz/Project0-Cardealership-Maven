package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class CredentialAuditForwarderTest {
    private static final String STREAM = "fixture-stream";

    @Test
    void forwardsOnlyNewEventsAndAllowsNormalSequenceGaps() {
        Harness fixture = new Harness(List.of(event(1), event(3)));
        assertEquals(2, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(3), fixture.ack.get().sourceCursor());
        assertEquals(List.of(STREAM + ":1", STREAM + ":3"),
                new ArrayList<>(fixture.archive.keySet()));
        assertEquals(0, fixture.forwarder.forwardNextBatch());
        assertTrue(fixture.alerts.isEmpty());
    }

    @Test
    void failedAppendAlertsAndRetriesWithoutSkippingTheEvent() {
        Harness fixture = new Harness(List.of(event(1), event(2)));
        fixture.failAppendId = 2;
        assertThrows(IllegalStateException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals(List.of("append:2:0:2"), fixture.alerts);

        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(2), fixture.ack.get().sourceCursor());
        assertEquals(2, fixture.archive.size());
    }

    @Test
    void checkpointFailureRetriesTheSameIdempotentAppend() {
        Harness fixture = new Harness(List.of(event(1)));
        fixture.failCheckpointId = 1;
        assertThrows(IllegalStateException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals(List.of("checkpoint-write:1:0:1"), fixture.alerts);

        fixture.restart(); // Simulated process crash after durable append, before checkpoint.
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals(2, fixture.appendAttempts);
    }

    @Test
    void wrongStreamOrReorderedBatchFailsBeforeAnyDelivery() {
        Harness wrongStream = new Harness(List.of(
                new CredentialAuditEvent("other-stream", cursor(1), 1,
                        "alice", "writer", Instant.EPOCH)));
        assertThrows(SecurityException.class, wrongStream.forwarder::forwardNextBatch);
        assertTrue(wrongStream.archive.isEmpty());
        assertEquals(List.of("source-read-or-order:0:0:0"), wrongStream.alerts);

        Harness reversed = new Harness(List.of(event(2), event(1)));
        assertThrows(SecurityException.class, reversed.forwarder::forwardNextBatch);
        assertTrue(reversed.archive.isEmpty());
        assertEquals(AuditSourceCursor.origin(), reversed.ack.get().sourceCursor());
    }

    @Test
    void commitOrderCursorCanAdvanceWhenDatabaseAuditIdsArriveOutOfOrder() {
        // Commit-order CDC may emit audit ID 11 before late-committing ID 10.
        Harness fixture = new Harness(List.of(
                new CredentialAuditEvent(STREAM, cursor(1), 11, "alice", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, cursor(2), 10, "bob", "writer", Instant.EPOCH)));
        assertEquals(2, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(2), fixture.ack.get().sourceCursor());
        assertEquals(List.of(STREAM + ":11", STREAM + ":10"),
                new ArrayList<>(fixture.archive.keySet()));
        assertEquals(0, fixture.forwarder.forwardNextBatch());
    }

    @Test
    void twoChangesInOneCommitHaveDifferentReplayableCursors() {
        AuditSourceCursor first = new AuditSourceCursor(50, 0);
        AuditSourceCursor second = new AuditSourceCursor(50, 1);
        AuditSourceCursor nextCommit = new AuditSourceCursor(60, 0);
        Harness fixture = new Harness(List.of(
                new CredentialAuditEvent(STREAM, first, 2, "alice", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, second, 3, "bob", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, nextCommit, 1,
                        "carol", "writer", Instant.EPOCH)));
        assertEquals(3, fixture.forwarder.forwardNextBatch());
        assertEquals(nextCommit, fixture.ack.get().sourceCursor());
        assertEquals(List.of(STREAM + ":2", STREAM + ":3", STREAM + ":1"),
                new ArrayList<>(fixture.archive.keySet()));
        fixture.restart();
        assertEquals(0, fixture.forwarder.forwardNextBatch());
    }

    @Test
    void checkpointBetweenTwoRowsOfOneCommitSurvivesRestartAndPageBoundaries() {
        AuditSourceCursor first = new AuditSourceCursor(50, 0);
        AuditSourceCursor second = new AuditSourceCursor(50, 1);
        AuditSourceCursor third = new AuditSourceCursor(60, 0);
        Harness fixture = new Harness(List.of(
                new CredentialAuditEvent(STREAM, first, 11, "a", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, second, 12, "b", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, third, 10, "c", "writer", Instant.EPOCH)));
        fixture.restart(1);
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(first, fixture.ack.get().sourceCursor());
        fixture.restart(1);
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(second, fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(third, fixture.ack.get().sourceCursor());
        assertEquals(0, fixture.forwarder.forwardNextBatch());
        assertEquals(List.of(STREAM + ":11", STREAM + ":12", STREAM + ":10"),
                new ArrayList<>(fixture.archive.keySet()));
        assertTrue(fixture.alerts.isEmpty());
    }

    @Test
    void lossOfSourceHistoryAfterFirstPageNeverLooksLikeIdleStream() {
        Harness fixture = new Harness(List.of(event(1), event(2)));
        fixture.restart(1);
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        fixture.historyUnavailable = true;
        assertThrows(CredentialAuditForwarder.AuditHistoryUnavailableException.class,
                fixture.forwarder::forwardNextBatch);
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
        assertEquals(List.of(STREAM + ":1"), new ArrayList<>(fixture.archive.keySet()));
        assertEquals(List.of("source-history-unavailable:1:0:0"), fixture.alerts);
    }

    @Test
    void duplicateAuditKeyAtDifferentCursorsIsRejectedBeforeAnyAppend() {
        Harness fixture = new Harness(List.of(
                new CredentialAuditEvent(STREAM, cursor(1), 7, "a", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, cursor(2), 7, "b", "writer", Instant.EPOCH)));
        assertThrows(SecurityException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
        assertTrue(fixture.archive.isEmpty());
        assertEquals(List.of("source-read-or-order:0:0:0"), fixture.alerts);
    }

    @Test
    void immutableArchiveRejectsConflictingReplayKeyAcrossPageBoundary() {
        Harness fixture = new Harness(List.of(
                new CredentialAuditEvent(STREAM, cursor(1), 7, "a", "writer", Instant.EPOCH),
                new CredentialAuditEvent(STREAM, cursor(2), 7, "b", "writer", Instant.EPOCH)));
        fixture.restart(1);
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertThrows(SecurityException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals("a", fixture.archive.get(STREAM + ":7").accountUsername());
        assertEquals(List.of("append:2:0:7"), fixture.alerts);
    }

    @Test
    void alertTransportFailureCannotMaskOriginalDeliveryFailure() {
        Harness fixture = new Harness(List.of(event(1)));
        fixture.failAppendId = 1;
        fixture.failAlerts = true;
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                fixture.forwarder::forwardNextBatch);
        assertEquals("fixture append unavailable", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
    }

    @Test
    void checkpointCannotBeReusedForAnotherSourceGeneration() {
        Harness fixture = new Harness(List.of(event(1)));
        fixture.ack.set(new AuditCheckpoint("restored-different-stream",
                AuditSourceCursor.origin()));
        assertThrows(SecurityException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(List.of("checkpoint-read:0:0:0"), fixture.alerts);
        assertTrue(fixture.archive.isEmpty());
    }

    @Test
    void staleCheckpointCompareAndSetFailsClosedAndReplaysIdempotently() {
        Harness fixture = new Harness(List.of(event(1)));
        fixture.rejectNextCheckpoint = true;
        assertThrows(SecurityException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals(List.of("checkpoint-write:1:0:1"), fixture.alerts);

        fixture.restart();
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
        assertEquals(1, fixture.archive.size());
        assertEquals(2, fixture.appendAttempts);
    }

    @Test
    void lostReplayHistoryFailsClosedInsteadOfReportingEmptyBatch() {
        Harness fixture = new Harness(List.of(event(1)));
        fixture.historyUnavailable = true;
        assertThrows(CredentialAuditForwarder.AuditHistoryUnavailableException.class,
                fixture.forwarder::forwardNextBatch);
        assertEquals(List.of("source-history-unavailable:0:0:0"), fixture.alerts);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
        assertTrue(fixture.archive.isEmpty());

        fixture.historyUnavailable = false;
        assertEquals(1, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(1), fixture.ack.get().sourceCursor());
    }

    @Test
    void transientSourceOutageRetainsCheckpointUntilRecovery() {
        Harness fixture = new Harness(List.of(event(1), event(2)));
        fixture.sourceUnavailable = true;
        assertThrows(IllegalStateException.class, fixture.forwarder::forwardNextBatch);
        assertEquals(List.of("source-read-or-order:0:0:0"), fixture.alerts);
        assertEquals(AuditSourceCursor.origin(), fixture.ack.get().sourceCursor());
        fixture.sourceUnavailable = false;
        assertEquals(2, fixture.forwarder.forwardNextBatch());
        assertEquals(cursor(2), fixture.ack.get().sourceCursor());
    }

    private static CredentialAuditEvent event(long id) {
        return new CredentialAuditEvent(STREAM, cursor(id), id, "alice", "writer", Instant.EPOCH);
    }

    private static AuditSourceCursor cursor(long id) {
        return new AuditSourceCursor(id, 0);
    }

    private static final class Harness {
        private final AtomicReference<AuditCheckpoint> ack =
                new AtomicReference<>(new AuditCheckpoint(STREAM, AuditSourceCursor.origin()));
        private final Map<String, CredentialAuditEvent> archive = new LinkedHashMap<>();
        private final List<String> alerts = new ArrayList<>();
        private CredentialAuditForwarder forwarder;
        private final CredentialAuditForwarder.AuditSource source;
        private final CredentialAuditForwarder.AppendOnlySink sink;
        private final CredentialAuditForwarder.CheckpointStore checkpoint;
        private final CredentialAuditForwarder.AlertSink notifications;
        private long failAppendId;
        private long failCheckpointId;
        private boolean failAlerts;
        private boolean rejectNextCheckpoint;
        private boolean historyUnavailable;
        private boolean sourceUnavailable;
        private int appendAttempts;

        private Harness(List<CredentialAuditEvent> records) {
            source = (after, limit) -> {
                if (historyUnavailable) {
                    throw new CredentialAuditForwarder.AuditHistoryUnavailableException();
                }
                if (sourceUnavailable) throw new IllegalStateException("fixture source offline");
                return records.stream()
                    .filter(event -> event.sourceCursor().compareTo(after) > 0)
                    .limit(limit)
                    .collect(Collectors.toList());
            };
            sink = event -> {
                appendAttempts++;
                if (failAppendId == event.auditId()) {
                    failAppendId = 0;
                    throw new IllegalStateException("fixture append unavailable");
                }
                CredentialAuditEvent prior = archive.putIfAbsent(event.eventKey(), event);
                if (prior != null && (!prior.sourceCursor().equals(event.sourceCursor())
                        || prior.auditId() != event.auditId()
                        || !prior.accountUsername().equals(event.accountUsername())
                        || !prior.databaseActor().equals(event.databaseActor())
                        || !prior.changedAt().equals(event.changedAt()))) {
                    throw new SecurityException("fixture archive immutable-event collision");
                }
            };
            checkpoint =
                    new CredentialAuditForwarder.CheckpointStore() {
                @Override public AuditCheckpoint load() { return ack.get(); }
                @Override public boolean advanceIfCurrent(
                        AuditCheckpoint expected, AuditSourceCursor sourceCursor) {
                    if (failCheckpointId == sourceCursor.commitPosition()) {
                        failCheckpointId = 0;
                        throw new IllegalStateException("fixture checkpoint unavailable");
                    }
                    if (rejectNextCheckpoint) {
                        rejectNextCheckpoint = false;
                        return false;
                    }
                    if (sourceCursor.compareTo(expected.sourceCursor()) <= 0) {
                        throw new SecurityException("checkpoint rollback");
                    }
                    AuditCheckpoint current = ack.get();
                    return current != null && current.equals(expected)
                            && ack.compareAndSet(current,
                            new AuditCheckpoint(expected.streamId(), sourceCursor));
                }
            };
            notifications = (phase, stream, cursor, id) -> {
                if (failAlerts) throw new IllegalStateException("fixture alert unavailable");
                alerts.add(phase + ":" + cursor + ":" + id);
            };
            restart();
        }

        private void restart() {
            restart(10);
        }

        private void restart(int limit) {
            forwarder = new CredentialAuditForwarder(STREAM, source, sink,
                    checkpoint, notifications, limit);
        }
    }
}
