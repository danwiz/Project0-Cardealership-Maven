package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.CanonicalSignedReceiptTransport;
import com.revature.service.SignedReceiptPinIndex.TrustedRevisionFloor;
import com.revature.service.SignedRotationEvidenceAuthority.Kind;

/**
 * Provider-neutral, persistence-backed minimum signed-pin revision authority.
 *
 * A separately administered, rollback-resistant monotonic witness is required:
 * SQL alone cannot detect rollback caused by backup restores or privileged DDL.
 * This adapter never creates, resets, deletes or seeds trusted floor rows.
 */
public final class JdbcSignedReceiptPinRevisionFloor implements TrustedRevisionFloor {
    private static final String BLOCKED = "signed pin revision floor unavailable";
    private static final String READ = "SELECT minimum_revision FROM signed_receipt_pin_floors "
            + "WHERE canonical_key = ?";
    private static final String ADVANCE = "UPDATE signed_receipt_pin_floors "
            + "SET minimum_revision = ? WHERE canonical_key = ? "
            + "AND minimum_revision = ? AND minimum_revision < ?";

    private final String namespace;
    private final Kind kind;
    private final FencedJdbcConnectionGate.ConnectionOpener reader;
    private final FencedJdbcConnectionGate.ConnectionOpener publisher;
    private final MonotonicWitness witness;

    public JdbcSignedReceiptPinRevisionFloor(String namespace, Kind kind,
            FencedJdbcConnectionGate.ConnectionOpener reader,
            FencedJdbcConnectionGate.ConnectionOpener publisher,
            MonotonicWitness witness) {
        this.namespace = requireNamespace(namespace);
        this.kind = Objects.requireNonNull(kind, "kind");
        this.reader = Objects.requireNonNull(reader, "reader");
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.witness = Objects.requireNonNull(witness, "witness");
    }

    /**
     * Enforce an independently anchored floor on every read; a restored or
     * stale SQL value below the witness causes denial, never silent fallback.
     */
    @Override
    public long minimumRevision(ReopenProof proof, String canonicalKey) {
        String key = requireKey(proof, canonicalKey);
        try {
            long persisted = readFrom(reader, key);
            long anchored = witness.minimumRevision(key);
            if (persisted <= 0 || anchored <= 0 || persisted < anchored) throw blocked();
            return persisted;
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    /**
     * Publisher-only CAS. A witness reservation happens BEFORE SQL mutation:
     * a crash/uncertain commit may block reads until an authorized repair,
     * but cannot silently make a lower restored SQL floor acceptable.
     *
     * Calling this requires independent authentication/authorization of the
     * new signed pin record. It does not publish or sign pin documents.
     */
    public boolean advance(ReopenProof proof, String canonicalKey,
            long expectedRevision, long nextRevision) {
        String key = requireKey(proof, canonicalKey);
        if (expectedRevision <= 0 || nextRevision <= expectedRevision) return false;
        try {
            // Avoid poisoning the witness on an obviously stale publisher;
            // the final SQL WHERE remains the atomic cross-process CAS.
            if (readFrom(reader, key) != expectedRevision) return false;
            long reserved = witness.reserveAtLeast(key, nextRevision);
            if (reserved != nextRevision) throw blocked();
            try (Connection connection = publisher.open()) {
                connection.setAutoCommit(false);
                try (PreparedStatement update = connection.prepareStatement(ADVANCE)) {
                    update.setLong(1, nextRevision);
                    update.setString(2, key);
                    update.setLong(3, expectedRevision);
                    update.setLong(4, nextRevision);
                    if (update.executeUpdate() != 1) {
                        connection.rollback();
                        return false;
                    }
                    connection.commit();
                    return true;
                } catch (SQLException | RuntimeException failed) {
                    try { connection.rollback(); } catch (SQLException ignored) { /* deny */ }
                    throw failed;
                }
            }
        } catch (Exception unavailable) {
            throw blocked();
        }
    }

    private String requireKey(ReopenProof proof, String canonicalKey) {
        try {
            String derived = CanonicalSignedReceiptTransport.receiptKey(namespace,
                    kind, proof);
            if (!derived.equals(canonicalKey)) throw blocked();
            return derived;
        } catch (RuntimeException unavailable) {
            throw blocked();
        }
    }

    private static long readFrom(FencedJdbcConnectionGate.ConnectionOpener opener,
            String key) throws SQLException {
        try (Connection connection = opener.open();
                PreparedStatement read = connection.prepareStatement(READ)) {
            read.setString(1, key);
            try (ResultSet rows = read.executeQuery()) {
                if (!rows.next()) throw blocked();
                long revision = rows.getLong(1);
                if (rows.wasNull() || revision <= 0 || rows.next()) throw blocked();
                return revision;
            }
        }
    }

    private static String requireNamespace(String namespace) {
        if (namespace == null || !namespace.matches("[a-z][a-z0-9-]{0,62}")) {
            throw new IllegalArgumentException("invalid artifact namespace");
        }
        return namespace;
    }

    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    /**
     * Independent, durable, strongly consistent monotonic lower bound.
     * Implementations must never lower a value and must durably reserve first.
     * This interface does not imply a particular cloud or self-hosted product.
     */
    public interface MonotonicWitness {
        long minimumRevision(String canonicalKey) throws Exception;
        /** Returns the effective revision after an atomic max reservation. */
        long reserveAtLeast(String canonicalKey, long nextRevision) throws Exception;
    }
}
