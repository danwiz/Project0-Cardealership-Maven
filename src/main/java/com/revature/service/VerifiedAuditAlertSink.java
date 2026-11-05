package com.revature.service;

import java.util.Objects;
import java.util.UUID;

/**
 * Provider-neutral durable ALERT OUTBOX seam. Recording an alert is not proof
 * that an operator was notified; outbox delivery/monitoring is a separate gate.
 */
public final class VerifiedAuditAlertSink implements CredentialAuditForwarder.AlertSink {
    private final AlertOutbox outbox;

    public VerifiedAuditAlertSink(AlertOutbox outbox) {
        this.outbox = Objects.requireNonNull(outbox, "outbox");
    }

    @Override
    public void forwardingFailed(String phase, String streamId,
            AuditSourceCursor sourceCursor, long auditId) {
        AuditFailureNotice notice = new AuditFailureNotice(UUID.randomUUID().toString(),
                phase, streamId, sourceCursor, auditId);
        AuditDurableReceipt receipt = outbox.store(notice);
        if (receipt == null || !notice.recordKey().equals(receipt.recordKey())
                || !notice.contentSha256().equals(receipt.contentSha256())) {
            throw new SecurityException("audit alert outbox receipt mismatch");
        }
    }

    @FunctionalInterface
    public interface AlertOutbox {
        /**
         * Must persist notice and return only after durable acknowledgement.
         * External delivery, retries, dead-letter handling and a separate
         * monitor for outbox outages are required in production.
         */
        AuditDurableReceipt store(AuditFailureNotice notice);
    }
}
