package com.revature.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Provider-neutral, at-least-once alert outbox relay (not a deployed worker).
 * The independently controlled outbox must persist notices and lease state;
 * the alert transport must deduplicate by notice key and immutable digest.
 * Acknowledgement happens ONLY after a matching delivery receipt. Expired
 * leases, ambiguous delivery and crashes can cause duplicate sends.
 */
public final class AuditAlertOutboxRelay {
    private final PendingNoticeStore pending;
    private final DeliveryTransport transport;
    private final int batchLimit;
    private final Duration leaseTtl;

    public AuditAlertOutboxRelay(PendingNoticeStore pending, DeliveryTransport transport,
            int batchLimit, Duration leaseTtl) {
        if (batchLimit <= 0 || batchLimit > 1000) {
            throw new IllegalArgumentException("alert relay batch limit must be 1..1000");
        }
        if (leaseTtl == null || leaseTtl.compareTo(Duration.ofSeconds(1)) < 0
                || leaseTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("invalid alert delivery lease TTL");
        }
        this.pending = Objects.requireNonNull(pending, "pending");
        this.transport = Objects.requireNonNull(transport, "transport");
        this.batchLimit = batchLimit;
        this.leaseTtl = leaseTtl;
    }

    /**
     * One bounded attempt; scheduled retries and independent outbox monitoring
     * are the provider's responsibility. synchronized only guards this JVM.
     */
    public synchronized int deliverNextBatch() {
        List<LeasedNotice> claims = pending.claimPending(batchLimit, leaseTtl);
        if (claims == null || claims.size() > batchLimit) {
            throw new SecurityException("invalid audit alert claim batch");
        }
        List<LeasedNotice> batch = new ArrayList<>(claims);
        Set<String> unique = new HashSet<>();
        for (LeasedNotice claim : batch) {
            if (claim == null || !unique.add(claim.notice().recordKey())) {
                throw new SecurityException("duplicate or null audit alert claim");
            }
        }

        int delivered = 0;
        for (LeasedNotice claim : batch) {
            AuditFailureNotice notice = claim.notice();
            // Transport acknowledgement must itself represent a durable or
            // idempotently accepted delivery, not a queued local network write.
            AuditDurableReceipt receipt = transport.deliver(notice);
            if (receipt == null || !notice.recordKey().equals(receipt.recordKey())
                    || !notice.contentSha256().equals(receipt.contentSha256())) {
                throw new SecurityException("audit alert delivery receipt mismatch");
            }
            // The backend atomically checks the claim token AND notice digest.
            // A stale worker cannot acknowledge a newer worker's lease.
            if (!pending.markDelivered(claim)) {
                throw new SecurityException("audit alert lease expired or was superseded");
            }
            delivered++;
        }
        return delivered;
    }

    /** Immutable notice plus an opaque per-claim fencing token. */
    public static final class LeasedNotice {
        private final AuditFailureNotice notice;
        private final String leaseToken;

        public LeasedNotice(AuditFailureNotice notice, String leaseToken) {
            this.notice = Objects.requireNonNull(notice, "notice");
            this.leaseToken = UUID.fromString(Objects.requireNonNull(
                    leaseToken, "leaseToken")).toString();
        }

        public AuditFailureNotice notice() { return notice; }
        public String leaseToken() { return leaseToken; }
    }

    public interface PendingNoticeStore {
        /**
         * Atomically claim at most limit pending notices with unique lease
         * tokens that expire after TTL. Expired undelivered notices must be
         * eligible for re-claim. Never discard on attempted delivery alone.
         */
        List<LeasedNotice> claimPending(int limit, Duration ttl);

        /**
         * Durable atomic conditional acknowledgement for EXACT current token
         * and immutable notice content. False on stale/replaced/expired lease.
         * Do not delete immutable outbox evidence before its retention expires.
         */
        boolean markDelivered(LeasedNotice claim);
    }

    @FunctionalInterface
    public interface DeliveryTransport {
        /**
         * Must accept at least once and deduplicate by notice key and digest,
         * returning matching receipt only after durable delivery acceptance.
         * Duplicate invocation after an ambiguous result is expected.
         */
        AuditDurableReceipt deliver(AuditFailureNotice notice);
    }
}
