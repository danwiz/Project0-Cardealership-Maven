package com.revature.service;

import java.util.Objects;

/**
 * Provider-neutral wrapper for a durable cross-process checkpoint CAS backend.
 * The backend must persist outside the source database and only acknowledge
 * after a durable atomic change; the wrapper verifies the returned values.
 */
public final class VerifiedAuditCheckpointStore
        implements CredentialAuditForwarder.CheckpointStore {
    private final DurableCheckpointBackend backend;

    public VerifiedAuditCheckpointStore(DurableCheckpointBackend backend) {
        this.backend = Objects.requireNonNull(backend, "backend");
    }

    @Override
    public AuditCheckpoint load() { return backend.load(); }

    @Override
    public boolean advanceIfCurrent(AuditCheckpoint expected, AuditSourceCursor next) {
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(next, "next");
        if (next.compareTo(expected.sourceCursor()) <= 0) {
            throw new SecurityException("audit checkpoint must advance monotonically");
        }
        AuditCheckpoint written = backend.compareAndAdvance(expected, next);
        if (written == null) return false; // Stale CAS; do not advance this worker.
        if (!new AuditCheckpoint(expected.streamId(), next).equals(written)) {
            throw new SecurityException("audit checkpoint receipt does not match state");
        }
        return true;
    }

    public interface DurableCheckpointBackend {
        AuditCheckpoint load();

        /**
         * Atomically compare stream ID and cursor VALUE, persist the new state,
         * then return the exact durable checkpoint; null on stale CAS.
         * Never acknowledge before storage commit or reset a stream silently.
         */
        AuditCheckpoint compareAndAdvance(AuditCheckpoint expected, AuditSourceCursor next);
    }
}
