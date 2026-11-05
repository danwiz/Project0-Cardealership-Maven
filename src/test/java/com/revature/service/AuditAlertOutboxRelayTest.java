package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.revature.service.AuditAlertOutboxRelay.LeasedNotice;
import com.revature.service.AuditAlertOutboxRelay.PendingNoticeStore;

/** Disposable outbox/transport fixtures; no actual notification is sent. */
class AuditAlertOutboxRelayTest {
    private static final Duration TTL = Duration.ofSeconds(10);
    private static final String STREAM = "fixture-alert-stream";

    @Test
    void persistsAndDeliversBoundedPagesThenStopsOnVerifiedIdleOutbox() {
        FakeOutbox queue = new FakeOutbox();
        VerifiedAuditAlertSink alerts = new VerifiedAuditAlertSink(queue::store);
        alerts.forwardingFailed("append", STREAM, new AuditSourceCursor(50, 0), 11);
        alerts.forwardingFailed("checkpoint-write", STREAM, new AuditSourceCursor(50, 1), 12);
        FakeTransport transport = new FakeTransport();
        AuditAlertOutboxRelay relay = new AuditAlertOutboxRelay(queue,
                transport::deliver, 1, TTL);

        assertEquals(1, relay.deliverNextBatch());
        assertEquals(1, relay.deliverNextBatch());
        assertEquals(0, relay.deliverNextBatch());
        assertEquals(2, transport.accepted.size());
        assertEquals(2, transport.attempts);
        assertEquals(2, queue.deliveredCount());
    }

    @Test
    void transportOutageRetainsNoticeUntilLeaseExpiresAndRestartRetries() {
        FakeOutbox queue = new FakeOutbox();
        queue.store(notice());
        FakeTransport transport = new FakeTransport();
        transport.failBeforeAcceptance.set(true);
        AuditAlertOutboxRelay first = new AuditAlertOutboxRelay(
                queue, transport::deliver, 1, TTL);
        assertThrows(IllegalStateException.class, first::deliverNextBatch);
        assertEquals(0, queue.deliveredCount());
        assertEquals(0, first.deliverNextBatch()); // Still leased; not lost.

        queue.advanceMillis(TTL.toMillis());
        AuditAlertOutboxRelay restarted = new AuditAlertOutboxRelay(
                queue, transport::deliver, 1, TTL);
        assertEquals(1, restarted.deliverNextBatch());
        assertEquals(1, queue.deliveredCount());
        assertEquals(1, transport.accepted.size());
    }

    @Test
    void ambiguousDeliveryReplaysIdempotentlyAfterCrashBeforeOutboxAck() {
        FakeOutbox queue = new FakeOutbox();
        AuditFailureNotice pending = notice();
        queue.store(pending);
        FakeTransport transport = new FakeTransport();
        transport.failAfterAcceptance.set(true);
        AuditAlertOutboxRelay first = new AuditAlertOutboxRelay(
                queue, transport::deliver, 1, TTL);
        assertThrows(IllegalStateException.class, first::deliverNextBatch);
        assertEquals(1, transport.accepted.size());
        assertEquals(0, queue.deliveredCount());

        queue.advanceMillis(TTL.toMillis());
        AuditAlertOutboxRelay recovered = new AuditAlertOutboxRelay(
                queue, transport::deliver, 1, TTL);
        assertEquals(1, recovered.deliverNextBatch());
        assertEquals(2, transport.attempts); // At-least-once, not exactly-once.
        assertEquals(1, transport.accepted.size()); // Stable notice key/digest.
        assertEquals(1, queue.deliveredCount());
    }

    @Test
    void wrongReceiptDoesNotAcknowledgeOrDropPendingNotice() {
        FakeOutbox queue = new FakeOutbox();
        queue.store(notice());
        AuditAlertOutboxRelay relay = new AuditAlertOutboxRelay(queue,
                notice -> new AuditDurableReceipt("wrong-alert", notice.contentSha256()),
                1, TTL);
        assertThrows(SecurityException.class, relay::deliverNextBatch);
        assertEquals(0, queue.deliveredCount());
        queue.advanceMillis(TTL.toMillis());
        assertEquals(1, new AuditAlertOutboxRelay(queue,
                new FakeTransport()::deliver, 1, TTL).deliverNextBatch());
    }

    @Test
    void expiredLeaseCannotAcknowledgeAReplacementWorkersClaim() {
        FakeOutbox queue = new FakeOutbox();
        AuditFailureNotice notice = notice();
        queue.store(notice);
        List<LeasedNotice> replacement = new ArrayList<>();
        AuditAlertOutboxRelay relay = new AuditAlertOutboxRelay(queue, current -> {
            queue.advanceMillis(TTL.toMillis());
            replacement.addAll(queue.claimPending(1, TTL));
            return new AuditDurableReceipt(current.recordKey(), current.contentSha256());
        }, 1, TTL);
        assertThrows(SecurityException.class, relay::deliverNextBatch);
        assertEquals(1, replacement.size());
        assertEquals(0, queue.deliveredCount());
        assertFalse(queue.markDelivered(new LeasedNotice(notice, UUID.randomUUID().toString())));
        assertTrue(queue.markDelivered(replacement.get(0)));
        assertEquals(1, queue.deliveredCount());
    }

    @Test
    void checkpointRejectionAfterDeliveryRequiresIdempotentTransportReplay() {
        FakeOutbox queue = new FakeOutbox();
        AuditFailureNotice notice = notice();
        queue.store(notice);
        queue.failNextMark.set(true);
        FakeTransport transport = new FakeTransport();
        AuditAlertOutboxRelay relay = new AuditAlertOutboxRelay(
                queue, transport::deliver, 1, TTL);
        assertThrows(IllegalStateException.class, relay::deliverNextBatch);
        assertEquals(0, queue.deliveredCount());
        queue.advanceMillis(TTL.toMillis());
        assertEquals(1, relay.deliverNextBatch());
        assertEquals(2, transport.attempts);
        assertEquals(1, transport.accepted.size());
        assertEquals(1, queue.deliveredCount());
    }

    @Test
    void rejectsDuplicateClaimsBeforeAnyOutboundMessage() {
        AuditFailureNotice notice = notice();
        LeasedNotice first = new LeasedNotice(notice, UUID.randomUUID().toString());
        LeasedNotice second = new LeasedNotice(notice, UUID.randomUUID().toString());
        PendingNoticeStore malformed = new PendingNoticeStore() {
            @Override public List<LeasedNotice> claimPending(int limit, Duration ttl) {
                return List.of(first, second);
            }
            @Override public boolean markDelivered(LeasedNotice claim) { return true; }
        };
        FakeTransport transport = new FakeTransport();
        AuditAlertOutboxRelay relay = new AuditAlertOutboxRelay(
                malformed, transport::deliver, 2, TTL);
        assertThrows(SecurityException.class, relay::deliverNextBatch);
        assertEquals(0, transport.attempts);
    }

    @Test
    void validatesLeaseAndBatchConfiguration() {
        FakeOutbox queue = new FakeOutbox();
        FakeTransport transport = new FakeTransport();
        assertThrows(IllegalArgumentException.class,
                () -> new AuditAlertOutboxRelay(queue, transport::deliver, 0, TTL));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditAlertOutboxRelay(queue, transport::deliver, 1,
                        Duration.ofNanos(1)));
        assertThrows(IllegalArgumentException.class,
                () -> new AuditAlertOutboxRelay(queue, transport::deliver, 1,
                        Duration.ofDays(2)));
        assertThrows(IllegalArgumentException.class,
                () -> new LeasedNotice(notice(), "not-a-uuid"));
    }

    private static AuditFailureNotice notice() {
        return new AuditFailureNotice(UUID.randomUUID().toString(), "append", STREAM,
                new AuditSourceCursor(50, 0), 11);
    }

    private static final class FakeTransport {
        private final Map<String, String> accepted = new LinkedHashMap<>();
        private final AtomicBoolean failBeforeAcceptance = new AtomicBoolean();
        private final AtomicBoolean failAfterAcceptance = new AtomicBoolean();
        private int attempts;

        private AuditDurableReceipt deliver(AuditFailureNotice notice) {
            attempts++;
            if (failBeforeAcceptance.getAndSet(false)) {
                throw new IllegalStateException("fixture transport down");
            }
            String prior = accepted.putIfAbsent(notice.recordKey(), notice.contentSha256());
            if (prior != null && !prior.equals(notice.contentSha256())) {
                throw new SecurityException("alert notice mutated on retry");
            }
            if (failAfterAcceptance.getAndSet(false)) {
                throw new IllegalStateException("fixture connection lost after acceptance");
            }
            return new AuditDurableReceipt(notice.recordKey(), notice.contentSha256());
        }
    }

    private static final class FakeOutbox implements PendingNoticeStore {
        private final Map<String, Entry> rows = new LinkedHashMap<>();
        private final AtomicBoolean failNextMark = new AtomicBoolean();
        private long nowMillis;

        private AuditDurableReceipt store(AuditFailureNotice notice) {
            Entry existing = rows.putIfAbsent(notice.recordKey(), new Entry(notice));
            if (existing != null && !existing.notice.contentSha256().equals(
                    notice.contentSha256())) {
                throw new SecurityException("immutable outbox key conflict");
            }
            return new AuditDurableReceipt(notice.recordKey(), notice.contentSha256());
        }

        @Override
        public List<LeasedNotice> claimPending(int limit, Duration ttl) {
            List<LeasedNotice> claims = new ArrayList<>();
            for (Entry row : rows.values()) {
                if (!row.delivered && (row.token == null || nowMillis >= row.expiresAt)) {
                    row.token = UUID.randomUUID().toString();
                    row.expiresAt = nowMillis + ttl.toMillis();
                    claims.add(new LeasedNotice(row.notice, row.token));
                    if (claims.size() == limit) break;
                }
            }
            return claims;
        }

        @Override
        public boolean markDelivered(LeasedNotice claim) {
            if (failNextMark.getAndSet(false)) {
                throw new IllegalStateException("fixture outbox checkpoint failure");
            }
            Entry row = rows.get(claim.notice().recordKey());
            if (row == null || row.delivered || !claim.leaseToken().equals(row.token)
                    || nowMillis >= row.expiresAt
                    || !claim.notice().contentSha256().equals(row.notice.contentSha256())) {
                return false;
            }
            row.delivered = true;
            return true;
        }

        private void advanceMillis(long delta) { nowMillis += delta; }

        private long deliveredCount() {
            return rows.values().stream().filter(row -> row.delivered).count();
        }
    }

    private static final class Entry {
        private final AuditFailureNotice notice;
        private String token;
        private long expiresAt;
        private boolean delivered;

        private Entry(AuditFailureNotice notice) { this.notice = notice; }
    }
}
