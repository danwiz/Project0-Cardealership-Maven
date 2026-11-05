package com.revature.service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral, single-instance audit forwarding seam (not a deployed job).
 * The real destination must durably append/idempotently acknowledge eventKey,
 * and the checkpoint must live outside the database being audited.
 * The sourceCursor is distinct from the database's audit ID. Production MUST
 * use a commit-ordered source cursor or a proven reconciliation scan: PostgreSQL
 * sequence IDs alone can skip late-committing rows.
 */
public final class CredentialAuditForwarder {
    private final String streamId;
    private final AuditSource source;
    private final AppendOnlySink sink;
    private final CheckpointStore checkpoint;
    private final AlertSink alerts;
    private final int batchLimit;

    public CredentialAuditForwarder(String streamId, AuditSource source,
            AppendOnlySink sink, CheckpointStore checkpoint, AlertSink alerts,
            int batchLimit) {
        if (streamId == null || streamId.trim().isEmpty()) {
            throw new IllegalArgumentException("audit stream identity required");
        }
        if (batchLimit <= 0 || batchLimit > 1000) {
            throw new IllegalArgumentException("audit batch limit must be 1..1000");
        }
        this.streamId = streamId;
        this.source = Objects.requireNonNull(source, "source");
        this.sink = Objects.requireNonNull(sink, "sink");
        this.checkpoint = Objects.requireNonNull(checkpoint, "checkpoint");
        this.alerts = Objects.requireNonNull(alerts, "alerts");
        this.batchLimit = batchLimit;
    }

    /**
     * Preserve strict SOURCE-CURSOR order, allow arbitrary audit-ID order/gaps,
     * and never advance the checkpoint on a failed durable append.
     * Synchronization covers one JVM only; production needs an external lease.
     */
    public synchronized int forwardNextBatch() {
        AuditCheckpoint acknowledged;
        try {
            acknowledged = checkpoint.load();
            if (acknowledged == null || !streamId.equals(acknowledged.streamId())) {
                throw new SecurityException("audit checkpoint stream mismatch or missing");
            }
        } catch (RuntimeException failure) {
            alert("checkpoint-read", AuditSourceCursor.origin(), 0, failure);
            throw failure;
        }

        AuditSourceCursor cursor = acknowledged.sourceCursor();

        List<CredentialAuditEvent> events;
        try {
            List<CredentialAuditEvent> fetched = source.readAfter(cursor, batchLimit);
            if (fetched == null || fetched.size() > batchLimit) {
                throw new SecurityException("invalid audit source batch");
            }
            events = new ArrayList<>(fetched);
            AuditSourceCursor previous = cursor;
            Set<String> eventKeys = new HashSet<>();
            for (CredentialAuditEvent event : events) {
                if (event == null || !streamId.equals(event.streamId())
                        || event.sourceCursor().compareTo(previous) <= 0) {
                    throw new SecurityException("audit source stream/order violation");
                }
                // Two distinct source positions cannot represent the same
                // immutable audit row within one page. Cross-page collisions
                // still require the external sink to reject conflicting data.
                if (!eventKeys.add(event.eventKey())) {
                    throw new SecurityException("duplicate audit event key in source batch");
                }
                previous = event.sourceCursor();
            }
        } catch (RuntimeException failure) {
            alert(failure instanceof AuditHistoryUnavailableException
                    ? "source-history-unavailable" : "source-read-or-order", cursor, 0, failure);
            throw failure;
        }

        int delivered = 0;
        for (CredentialAuditEvent event : events) {
            try {
                // Must return only after durable idempotent acknowledgement.
                sink.appendIfAbsent(event);
            } catch (RuntimeException failure) {
                alert("append", event.sourceCursor(), event.auditId(), failure);
                throw failure;
            }
            try {
                if (!checkpoint.advanceIfCurrent(acknowledged, event.sourceCursor())) {
                    throw new SecurityException("audit checkpoint advanced by another worker");
                }
                acknowledged = new AuditCheckpoint(streamId, event.sourceCursor());
            } catch (RuntimeException failure) {
                // If append succeeded, retries MUST be deduplicated by eventKey.
                alert("checkpoint-write", event.sourceCursor(), event.auditId(), failure);
                throw failure;
            }
            delivered++;
        }
        return delivered;
    }

    private void alert(String phase, AuditSourceCursor sourceCursor, long auditId,
            RuntimeException original) {
        try {
            // No password, hash, username or exception message crosses this API.
            alerts.forwardingFailed(phase, streamId, sourceCursor, auditId);
        } catch (RuntimeException alertFailure) {
            original.addSuppressed(alertFailure);
        }
    }

    @FunctionalInterface
    public interface AuditSource {
        /**
         * Must return events in stable commit-cursor order, not audit-ID order.
         * MUST throw AuditHistoryUnavailableException if the requested cursor
         * cannot be resumed completely; never disguise missing history as EOF.
         */
        List<CredentialAuditEvent> readAfter(AuditSourceCursor lastAcknowledgedCursor,
                int limit);
    }

    /** Source cannot establish complete replay from the requested cursor. */
    public static final class AuditHistoryUnavailableException extends IllegalStateException {
        public AuditHistoryUnavailableException() {
            super("complete audit replay history unavailable");
        }
    }

    @FunctionalInterface
    public interface AppendOnlySink {
        /**
         * Must implement durable, append-only, idempotent acknowledgement.
         * Repeated keys with identical immutable event content are replay;
         * repeated keys with CONFLICTING content are integrity errors, not
         * successful deduplication. The sink must reject them without mutating
         * the existing record, including collisions across pages/restarts.
         */
        void appendIfAbsent(CredentialAuditEvent event);
    }

    public interface CheckpointStore {
        /** Missing checkpoints fail closed; bootstrap must explicitly provision one. */
        AuditCheckpoint load();

        /**
         * Atomic cross-process compare-and-set of stream ID and cursor VALUES,
         * not Java object identity (deserialized checkpoints are equivalent).
         * False means a competing worker or source generation changed the state.
         * This operation must be durable on success; leader fencing is separate.
         */
        boolean advanceIfCurrent(AuditCheckpoint expected, AuditSourceCursor nextSourceCursor);
    }

    @FunctionalInterface
    public interface AlertSink {
        /** Alert metadata only; real adapter must reliably deliver/retry alerts. */
        void forwardingFailed(String phase, String streamId,
                AuditSourceCursor sourceCursor, long auditId);
    }
}
