package com.revature.repository.jdbc;

import java.sql.SQLException;
import java.util.Objects;

import com.revature.service.AuditCheckpoint;

/**
 * Provider-neutral, non-deployed writer gate requiring independently verified
 * audit completeness before each credential-generation activation.
 *
 * This wrapper owns its FencedJdbcConnectionGate and does not expose a direct
 * activation bypass. A production authority must independently authenticate
 * the evidence, prove source completeness/quiescence and durable archive
 * acknowledgement, and supply restart-persistent generation/epoch floors.
 */
public final class ReconciledJdbcWriterGate {
    private static final String BLOCKED = "writer reconciliation unavailable";

    private final FencedJdbcConnectionGate connections;
    private final ReconciliationAuthority authority;
    private final String streamId;

    private long lastGeneration;
    private long lastRotationEpoch;
    private AuditCheckpoint lastArchiveCheckpoint;
    private volatile boolean active;

    public ReconciledJdbcWriterGate(FencedJdbcConnectionGate.ConnectionOpener opener,
            FencedJdbcConnectionGate.GenerationAttestor generationAttestor,
            ReconciliationAuthority authority, AuditCheckpoint initialArchiveCheckpoint,
            long initialGenerationFloor, long initialRotationEpoch) {
        if (initialGenerationFloor < 0 || initialRotationEpoch < 0) {
            throw new IllegalArgumentException("invalid independent recovery floor");
        }
        this.connections = new FencedJdbcConnectionGate(
                Objects.requireNonNull(opener, "opener"),
                Objects.requireNonNull(generationAttestor, "generationAttestor"));
        this.authority = Objects.requireNonNull(authority, "authority");
        this.lastArchiveCheckpoint = Objects.requireNonNull(
                initialArchiveCheckpoint, "initialArchiveCheckpoint");
        this.streamId = initialArchiveCheckpoint.streamId();
        this.lastGeneration = initialGenerationFloor;
        this.lastRotationEpoch = initialRotationEpoch;
    }

    /**
     * Rejects missing, stale, contradictory or unverified audit evidence.
     * Source and archive must report the same complete commit-order watermark;
     * archive progress cannot substitute for an independent source preflight.
     */
    public synchronized void activate(ReopenProof proof) {
        if (active || !structurallyValid(proof)) throw blocked();
        final boolean verified;
        try {
            verified = authority.verify(proof);
        } catch (RuntimeException unavailable) {
            // Do not leak verifier endpoint, credentials or provider errors.
            throw blocked();
        }
        if (!verified) throw blocked();

        // The underlying gate separately attests the new secret generation and
        // refuses activation while any old JDBC work is still tracked.
        connections.activate(proof.nextGeneration());
        lastArchiveCheckpoint = proof.archivedCheckpoint();
        lastGeneration = proof.nextGeneration();
        lastRotationEpoch = proof.rotationEpoch();
        active = true;
    }

    /** Blocks new work and drains tracked sessions before future activation. */
    public synchronized void pauseAndDrain(long expectedGeneration) {
        if (!active || expectedGeneration != lastGeneration) throw blocked();
        active = false; // Keep closed even if connection-close fails.
        connections.pauseAndDrain(expectedGeneration);
    }

    public <T> T withConnection(FencedJdbcConnectionGate.JdbcWork<T> work)
            throws SQLException {
        if (!active) throw blocked();
        return connections.withConnection(work);
    }

    private boolean structurallyValid(ReopenProof proof) {
        return proof != null
                && proof.previousGeneration() == lastGeneration
                && proof.nextGeneration() > lastGeneration
                && proof.nextGeneration() > 0
                && proof.rotationEpoch() > lastRotationEpoch
                && streamId.equals(proof.sourceHighWater().streamId())
                && streamId.equals(proof.archivedCheckpoint().streamId())
                && proof.sourceHighWater().equals(proof.archivedCheckpoint())
                && proof.archivedCheckpoint().sourceCursor().compareTo(
                        lastArchiveCheckpoint.sourceCursor()) >= 0;
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    @FunctionalInterface
    public interface ReconciliationAuthority {
        /**
         * Must authenticate a fresh source-quiescence and externally durable
         * archive/checkpoint proof for THIS requested rotation epoch.
         * Trust may not be derived solely from the caller-supplied fields.
         */
        boolean verify(ReopenProof proof);
    }

    /** Immutable, credential-free metadata; not a signature or receipt. */
    public static final class ReopenProof {
        private final long previousGeneration;
        private final long nextGeneration;
        private final long rotationEpoch;
        private final AuditCheckpoint sourceHighWater;
        private final AuditCheckpoint archivedCheckpoint;

        public ReopenProof(long previousGeneration, long nextGeneration,
                long rotationEpoch, AuditCheckpoint sourceHighWater,
                AuditCheckpoint archivedCheckpoint) {
            this.previousGeneration = previousGeneration;
            this.nextGeneration = nextGeneration;
            this.rotationEpoch = rotationEpoch;
            this.sourceHighWater = Objects.requireNonNull(
                    sourceHighWater, "sourceHighWater");
            this.archivedCheckpoint = Objects.requireNonNull(
                    archivedCheckpoint, "archivedCheckpoint");
        }

        public long previousGeneration() { return previousGeneration; }
        public long nextGeneration() { return nextGeneration; }
        public long rotationEpoch() { return rotationEpoch; }
        public AuditCheckpoint sourceHighWater() { return sourceHighWater; }
        public AuditCheckpoint archivedCheckpoint() { return archivedCheckpoint; }
    }
}
