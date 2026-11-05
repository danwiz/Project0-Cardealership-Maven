package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import com.revature.service.VerifiedAuditAppendOnlySink.AuditArchiveRecord;

/** Contract tests use process-shared in-memory fakes, never a real archive. */
class AuditDurableBoundariesTest {
    private static final String STREAM = "independent-fixture-stream";

    @Test
    void canonicalArchiveDigestBindsAllImmutableEventMetadata() {
        CredentialAuditEvent baseline = event(20, 0, 8, "alice", "writer", Instant.EPOCH);
        String digest = new AuditArchiveRecord(baseline).contentSha256();
        assertEquals(64, digest.length());
        assertEquals(digest, new AuditArchiveRecord(event(20, 0, 8,
                "alice", "writer", Instant.EPOCH)).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(event(20, 1, 8,
                "alice", "writer", Instant.EPOCH)).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(event(20, 0, 9,
                "alice", "writer", Instant.EPOCH)).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(event(20, 0, 8,
                "bob", "writer", Instant.EPOCH)).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(event(20, 0, 8,
                "alice", "owner", Instant.EPOCH)).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(event(20, 0, 8,
                "alice", "writer", Instant.EPOCH.plusNanos(1))).contentSha256());
        assertNotEquals(digest, new AuditArchiveRecord(new CredentialAuditEvent(
                "restored-source", new AuditSourceCursor(20, 0), 8,
                "alice", "writer", Instant.EPOCH)).contentSha256());
    }

    @Test
    void sinkRejectsWrongKeyWrongDigestOrMissingDurableAcknowledgement() {
        CredentialAuditEvent event = event(20, 0, 1, "alice", "writer", Instant.EPOCH);
        AuditArchiveRecord record = new AuditArchiveRecord(event);
        AuditDurableReceipt accepted = new AuditDurableReceipt(
                record.eventKey(), record.contentSha256());
        assertEquals(record.contentSha256(), accepted.contentSha256());
        new VerifiedAuditAppendOnlySink(r -> accepted).appendIfAbsent(event);
        assertThrows(SecurityException.class, () -> new VerifiedAuditAppendOnlySink(
                r -> new AuditDurableReceipt("wrong-key", record.contentSha256()))
                .appendIfAbsent(event));
        assertThrows(SecurityException.class, () -> new VerifiedAuditAppendOnlySink(
                r -> new AuditDurableReceipt(record.eventKey(), "0".repeat(64)))
                .appendIfAbsent(event));
        assertThrows(SecurityException.class, () -> new VerifiedAuditAppendOnlySink(
                r -> null).appendIfAbsent(event));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditDurableReceipt(record.eventKey(), "NOT-A-DIGEST"));
    }

    @Test
    void mismatchedArchiveReceiptFailsForwardingWithoutCheckpointAdvance() {
        CredentialAuditEvent pending = event(20, 0, 1, "alice", "writer", Instant.EPOCH);
        FakeCheckpoint durable = new FakeCheckpoint();
        List<String> phases = new ArrayList<>();
        CredentialAuditForwarder worker = new CredentialAuditForwarder(
                STREAM,
                (after, limit) -> List.of(pending),
                new VerifiedAuditAppendOnlySink(record ->
                        new AuditDurableReceipt(record.eventKey(), "0".repeat(64))),
                new VerifiedAuditCheckpointStore(durable),
                (phase, stream, cursor, id) -> phases.add(phase), 1);
        assertThrows(SecurityException.class, worker::forwardNextBatch);
        assertEquals(AuditSourceCursor.origin(), durable.load().sourceCursor());
        assertEquals(List.of("append"), phases);
    }

    @Test
    void immutableArchiveRejectsConflictingKeyWithoutReplacingStoredEvent() {
        FakeArchive archive = new FakeArchive();
        VerifiedAuditAppendOnlySink sink = new VerifiedAuditAppendOnlySink(archive::append);
        CredentialAuditEvent first = event(20, 0, 7, "alice", "writer", Instant.EPOCH);
        CredentialAuditEvent conflict = event(30, 0, 7, "bob", "writer", Instant.EPOCH);
        sink.appendIfAbsent(first);
        sink.appendIfAbsent(first); // Exact replay is idempotent.
        assertThrows(SecurityException.class, () -> sink.appendIfAbsent(conflict));
        assertEquals(1, archive.records.size());
        assertEquals(new AuditArchiveRecord(first).contentSha256(),
                archive.records.get(first.eventKey()).contentSha256());
        assertEquals(3, archive.appendAttempts.get());
    }

    @Test
    void checkpointFailureAfterAppendRetriesAcrossRestartWithoutChangingArchive() {
        CredentialAuditEvent first = event(50, 0, 11, "a", "writer", Instant.EPOCH);
        CredentialAuditEvent second = event(50, 1, 12, "b", "writer", Instant.EPOCH);
        CredentialAuditEvent third = event(60, 0, 10, "c", "writer", Instant.EPOCH);
        List<CredentialAuditEvent> committed = List.of(first, second, third);
        FakeArchive archive = new FakeArchive();
        FakeCheckpoint durable = new FakeCheckpoint();
        VerifiedAuditCheckpointStore checkpoint = new VerifiedAuditCheckpointStore(durable);
        VerifiedAuditAppendOnlySink sink = new VerifiedAuditAppendOnlySink(archive::append);
        List<String> phases = new ArrayList<>();
        CredentialAuditForwarder.AuditSource source = (after, limit) -> committed.stream()
                .filter(event -> event.sourceCursor().compareTo(after) > 0)
                .limit(limit).collect(Collectors.toList());

        CredentialAuditForwarder worker = new CredentialAuditForwarder(
                STREAM, source, sink, checkpoint,
                (phase, stream, cursor, id) -> phases.add(phase), 1);
        assertEquals(1, worker.forwardNextBatch());
        durable.failNextAdvance.set(true);
        assertThrows(IllegalStateException.class, worker::forwardNextBatch);
        assertEquals(first.sourceCursor(), checkpoint.load().sourceCursor());
        assertEquals(2, archive.records.size());

        CredentialAuditForwarder restarted = new CredentialAuditForwarder(
                STREAM, source, sink, new VerifiedAuditCheckpointStore(durable),
                (phase, stream, cursor, id) -> phases.add(phase), 1);
        assertEquals(1, restarted.forwardNextBatch()); // Replay second after append.
        assertEquals(second.sourceCursor(), checkpoint.load().sourceCursor());
        assertEquals(1, restarted.forwardNextBatch());
        assertEquals(third.sourceCursor(), checkpoint.load().sourceCursor());
        assertEquals(0, restarted.forwardNextBatch());
        assertEquals(3, archive.records.size());
        assertEquals(4, archive.appendAttempts.get());
        assertEquals(List.of("checkpoint-write"), phases);
    }

    @Test
    void checkpointRejectsStaleCasRollbackAndFalseAcknowledgement() {
        FakeCheckpoint backend = new FakeCheckpoint();
        VerifiedAuditCheckpointStore workerA = new VerifiedAuditCheckpointStore(backend);
        VerifiedAuditCheckpointStore workerB = new VerifiedAuditCheckpointStore(backend);
        AuditCheckpoint original = workerA.load();
        assertTrue(workerA.advanceIfCurrent(original, new AuditSourceCursor(20, 0)));
        assertFalse(workerB.advanceIfCurrent(original, new AuditSourceCursor(30, 0)));
        assertEquals(new AuditSourceCursor(20, 0), workerB.load().sourceCursor());
        assertThrows(SecurityException.class,
                () -> workerA.advanceIfCurrent(workerA.load(), AuditSourceCursor.origin()));

        VerifiedAuditCheckpointStore dishonest = new VerifiedAuditCheckpointStore(
                new VerifiedAuditCheckpointStore.DurableCheckpointBackend() {
                    @Override public AuditCheckpoint load() { return original; }
                    @Override public AuditCheckpoint compareAndAdvance(
                            AuditCheckpoint expected, AuditSourceCursor next) {
                        return new AuditCheckpoint(STREAM, new AuditSourceCursor(999, 0));
                    }
                });
        assertThrows(SecurityException.class,
                () -> dishonest.advanceIfCurrent(original, new AuditSourceCursor(30, 0)));
    }

    @Test
    void outboxPersistsOnlyFailureMetadataAndMatchesReceipt() {
        List<AuditFailureNotice> persisted = new ArrayList<>();
        VerifiedAuditAlertSink notifications = new VerifiedAuditAlertSink(notice -> {
            persisted.add(notice);
            return new AuditDurableReceipt(notice.recordKey(), notice.contentSha256());
        });
        notifications.forwardingFailed("source-history-unavailable", STREAM,
                new AuditSourceCursor(50, 1), 0);
        notifications.forwardingFailed("source-history-unavailable", STREAM,
                new AuditSourceCursor(50, 1), 0);
        assertEquals(2, persisted.size());
        assertNotEquals(persisted.get(0).noticeId(), persisted.get(1).noticeId());
        AuditFailureNotice first = persisted.get(0);
        assertEquals("source-history-unavailable", first.phase());
        assertEquals(STREAM, first.streamId());
        assertEquals(new AuditSourceCursor(50, 1), first.sourceCursor());
        assertEquals(0, first.auditId());
        assertEquals(64, first.contentSha256().length());
        assertTrue(first.recordKey().startsWith("audit-alert:"));
        assertNotNull(first.noticeId());
        assertThrows(IllegalArgumentException.class, () -> new AuditFailureNotice(
                first.noticeId(), "password-exfiltration", STREAM,
                AuditSourceCursor.origin(), 0));
    }

    @Test
    void sourceFailurePreservesOriginalWhenAlertOutboxCannotAcknowledge() {
        FakeCheckpoint durable = new FakeCheckpoint();
        VerifiedAuditAlertSink rejected = new VerifiedAuditAlertSink(notice ->
                new AuditDurableReceipt("wrong-notice", notice.contentSha256()));
        IllegalStateException original = assertThrows(IllegalStateException.class,
                () -> new CredentialAuditForwarder(STREAM,
                        (cursor, limit) -> { throw new IllegalStateException("source offline"); },
                        event -> { throw new AssertionError("no append expected"); },
                        new VerifiedAuditCheckpointStore(durable), rejected, 1)
                        .forwardNextBatch());
        assertEquals("source offline", original.getMessage());
        assertEquals(1, original.getSuppressed().length);
        assertTrue(original.getSuppressed()[0] instanceof SecurityException);
        assertEquals(AuditSourceCursor.origin(), durable.load().sourceCursor());
    }

    private static CredentialAuditEvent event(long position, int ordinal, long id,
            String account, String actor, Instant changedAt) {
        return new CredentialAuditEvent(STREAM, new AuditSourceCursor(position, ordinal),
                id, account, actor, changedAt);
    }

    private static final class FakeArchive {
        private final Map<String, AuditArchiveRecord> records = new LinkedHashMap<>();
        private final AtomicInteger appendAttempts = new AtomicInteger();

        private AuditDurableReceipt append(AuditArchiveRecord record) {
            appendAttempts.incrementAndGet();
            AuditArchiveRecord prior = records.putIfAbsent(record.eventKey(), record);
            if (prior != null && !prior.contentSha256().equals(record.contentSha256())) {
                throw new SecurityException("immutable archive record conflict");
            }
            return new AuditDurableReceipt(record.eventKey(), record.contentSha256());
        }
    }

    private static final class FakeCheckpoint
            implements VerifiedAuditCheckpointStore.DurableCheckpointBackend {
        private final AtomicReference<AuditCheckpoint> current = new AtomicReference<>(
                new AuditCheckpoint(STREAM, AuditSourceCursor.origin()));
        private final AtomicBoolean failNextAdvance = new AtomicBoolean();

        @Override public AuditCheckpoint load() { return current.get(); }

        @Override public AuditCheckpoint compareAndAdvance(
                AuditCheckpoint expected, AuditSourceCursor next) {
            if (failNextAdvance.getAndSet(false)) {
                throw new IllegalStateException("checkpoint storage unavailable");
            }
            AuditCheckpoint existing = current.get();
            AuditCheckpoint updated = new AuditCheckpoint(expected.streamId(), next);
            return existing != null && existing.equals(expected)
                    && current.compareAndSet(existing, updated) ? updated : null;
        }
    }
}
