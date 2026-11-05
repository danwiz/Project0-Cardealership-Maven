package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;

/**
 * Provider-neutral JDBC adapter for one durable, cross-process rotation claim.
 *
 * The independently administered writer_rotation_floors table must already
 * exist and contain exactly one trusted row for each stream. This class never
 * creates or resets floors, and MUST be called only after an independent
 * source/archive reconciliation authority authenticates the entire proof.
 * Claiming an epoch is not itself a secret attestation or distributed DB fence.
 */
public final class JdbcRotationClaimLedger {
    private static final String BLOCKED = "rotation claim authority unavailable";
    private static final String READ = "SELECT credential_generation, rotation_epoch, "
            + "cursor_position, cursor_ordinal FROM writer_rotation_floors "
            + "WHERE stream_id = ?";
    private static final String LOCK = READ + " FOR UPDATE";
    private static final String ADVANCE = "UPDATE writer_rotation_floors SET "
            + "credential_generation = ?, rotation_epoch = ?, cursor_position = ?, "
            + "cursor_ordinal = ? WHERE stream_id = ?";

    private final FencedJdbcConnectionGate.ConnectionOpener opener;

    public JdbcRotationClaimLedger(FencedJdbcConnectionGate.ConnectionOpener opener) {
        this.opener = Objects.requireNonNull(opener, "opener");
    }

    /** Read a recovery floor from the shared store, never a caller default. */
    public Floor load(String streamId) {
        requireStream(streamId);
        try (Connection connection = opener.open();
                PreparedStatement statement = connection.prepareStatement(READ)) {
            statement.setString(1, streamId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw blocked();
                Floor floor = floor(streamId, rows);
                if (rows.next()) throw blocked();
                return floor;
            }
        } catch (SQLException | RuntimeException unavailable) {
            throw blocked();
        }
    }

    /**
     * Serialize competing claims on the persisted stream row, then commit the
     * new epoch/generation/checkpoint together. No missing-row initialization.
     * A rejected/stale claim returns false and leaves the row unchanged.
     */
    public boolean claim(ReopenProof proof) {
        if (proof == null) throw blocked();
        String streamId = proof.archivedCheckpoint().streamId();
        requireStream(streamId);
        try (Connection connection = opener.open()) {
            connection.setAutoCommit(false);
            try {
                Floor previous;
                try (PreparedStatement statement = connection.prepareStatement(LOCK)) {
                    statement.setString(1, streamId);
                    try (ResultSet rows = statement.executeQuery()) {
                        if (!rows.next()) throw blocked();
                        previous = floor(streamId, rows);
                        if (rows.next()) throw blocked();
                    }
                }
                if (!eligible(previous, proof)) {
                    connection.rollback();
                    return false;
                }
                try (PreparedStatement update = connection.prepareStatement(ADVANCE)) {
                    update.setLong(1, proof.nextGeneration());
                    update.setLong(2, proof.rotationEpoch());
                    update.setLong(3, proof.archivedCheckpoint().sourceCursor().commitPosition());
                    update.setInt(4, proof.archivedCheckpoint().sourceCursor().eventOrdinal());
                    update.setString(5, streamId);
                    if (update.executeUpdate() != 1) throw blocked();
                }
                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failed) {
                try { connection.rollback(); } catch (SQLException ignored) { /* fail closed */ }
                throw blocked();
            }
        } catch (SQLException | RuntimeException unavailable) {
            throw blocked();
        }
    }

    private static boolean eligible(Floor floor, ReopenProof proof) {
        return floor.generation == proof.previousGeneration()
                && proof.nextGeneration() > floor.generation
                && proof.nextGeneration() > 0
                && proof.rotationEpoch() > floor.epoch
                && floor.checkpoint.streamId().equals(proof.sourceHighWater().streamId())
                && proof.sourceHighWater().equals(proof.archivedCheckpoint())
                && proof.archivedCheckpoint().sourceCursor().compareTo(
                        floor.checkpoint.sourceCursor()) >= 0;
    }

    private static Floor floor(String streamId, ResultSet rows) throws SQLException {
        long generation = rows.getLong(1);
        long epoch = rows.getLong(2);
        long position = rows.getLong(3);
        int ordinal = rows.getInt(4);
        if (generation < 0 || epoch < 0) throw blocked();
        return new Floor(new AuditCheckpoint(streamId,
                new AuditSourceCursor(position, ordinal)), generation, epoch);
    }

    private static void requireStream(String streamId) {
        if (streamId == null || streamId.trim().isEmpty()) throw blocked();
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    /** Snapshot used only to reconstruct a gate after independent validation. */
    public static final class Floor {
        private final AuditCheckpoint checkpoint;
        private final long generation;
        private final long epoch;

        private Floor(AuditCheckpoint checkpoint, long generation, long epoch) {
            this.checkpoint = checkpoint;
            this.generation = generation;
            this.epoch = epoch;
        }

        public AuditCheckpoint checkpoint() { return checkpoint; }
        public long generation() { return generation; }
        public long epoch() { return epoch; }
    }
}
