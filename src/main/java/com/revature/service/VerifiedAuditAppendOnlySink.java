package com.revature.service;

import java.util.Objects;

/**
 * Verifies a provider-neutral archive receipt BEFORE the forwarder checkpoints.
 * It does not create a WORM store or prove a dishonest backend persisted data.
 */
public final class VerifiedAuditAppendOnlySink
        implements CredentialAuditForwarder.AppendOnlySink {
    private final ArchiveStore store;

    public VerifiedAuditAppendOnlySink(ArchiveStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public void appendIfAbsent(CredentialAuditEvent event) {
        AuditArchiveRecord record = new AuditArchiveRecord(event);
        AuditDurableReceipt receipt = store.appendIfAbsent(record);
        if (receipt == null || !record.eventKey().equals(receipt.recordKey())
                || !record.contentSha256().equals(receipt.contentSha256())) {
            throw new SecurityException("audit archive receipt does not match event");
        }
    }

    /** Immutable archive request; event never includes a credential or hash. */
    public static final class AuditArchiveRecord {
        private final CredentialAuditEvent event;
        private final String digest;

        public AuditArchiveRecord(CredentialAuditEvent event) {
            this.event = Objects.requireNonNull(event, "event");
            this.digest = AuditContentDigest.archive(event);
        }

        public CredentialAuditEvent event() { return event; }
        public String eventKey() { return event.eventKey(); }
        public String contentSha256() { return digest; }
    }

    @FunctionalInterface
    public interface ArchiveStore {
        /**
         * Must atomically append or check an existing immutable record.
         * Conflicting content for an existing key MUST fail, never overwrite.
         * Return the receipt ONLY after durable idempotent acknowledgement;
         * retention lock, independent access controls, encryption and replication
         * are the provider adapter's responsibility.
         */
        AuditDurableReceipt appendIfAbsent(AuditArchiveRecord record);
    }
}
