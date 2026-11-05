package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Provider-neutral, non-deployed gate for scoped writer JDBC sessions.
 *
 * Starts paused, admits connections only after an independently attested
 * credential generation, and fences results from sessions opened before a
 * drain. Connection.close() is driver-specific and cannot prove a server-side
 * transaction was cancelled or a DB login revoked; production requires
 * independent server-side revocation and reconciliation.
 */
public final class FencedJdbcConnectionGate {
    private static final String BLOCKED = "writer connection temporarily unavailable";

    private final ConnectionOpener opener;
    private final GenerationAttestor attestor;
    private final Set<Connection> active = Collections.newSetFromMap(
            new IdentityHashMap<Connection, Boolean>());
    private boolean open;
    private boolean draining;
    private boolean closeFailure;
    private long generation;
    private long fence;

    public FencedJdbcConnectionGate(ConnectionOpener opener, GenerationAttestor attestor) {
        this.opener = Objects.requireNonNull(opener, "opener");
        this.attestor = Objects.requireNonNull(attestor, "attestor");
        // Nothing can open before independently validating a current generation.
    }

    /**
     * No raw connection is returned; the operation must not retain it or any
     * JDBC object derived from it beyond the scoped callback.
     */
    public <T> T withConnection(JdbcWork<T> work) throws SQLException {
        Objects.requireNonNull(work, "work");
        final long openedUnderFence;
        synchronized (this) {
            if (!open || closeFailure) throw blocked();
            openedUnderFence = fence;
        }

        // Open outside the monitor; a concurrent drain can invalidate us.
        final Connection connection;
        try {
            connection = opener.open();
        } catch (RuntimeException openerFailure) {
            throw blocked();
        }
        if (connection == null) throw blocked();
        try {
            synchronized (this) {
                if (!open || closeFailure || fence != openedUnderFence
                        || !active.add(connection)) {
                    throw blocked();
                }
            }
            T result = work.run(connection);
            synchronized (this) {
                if (!open || closeFailure || fence != openedUnderFence) {
                    throw blocked();
                }
            }
            return result;
        } finally {
            // Keep tracked until close completes, so activation can't race it.
            try {
                connection.close();
            } catch (SQLException | RuntimeException closeFailure) {
                synchronized (this) {
                    this.closeFailure = true;
                    open = false;
                    fence++;
                }
                throw blocked();
            } finally {
                synchronized (this) {
                    active.remove(connection);
                }
            }
        }
    }

    /**
     * Fence current work and close all tracked sessions. Remains paused even
     * on failure; calls in progress can still have server-side effects.
     */
    public void pauseAndDrain(long expectedGeneration) {
        final List<Connection> toClose;
        synchronized (this) {
            if (!open || draining || closeFailure || generation != expectedGeneration) {
                throw blocked();
            }
            open = false;
            draining = true;
            fence++;
            toClose = new ArrayList<>(active);
        }
        boolean failed = false;
        for (Connection connection : toClose) {
            try {
                connection.close();
            } catch (SQLException | RuntimeException connectionFailure) {
                failed = true;
            }
        }
        synchronized (this) {
            draining = false;
            if (failed) closeFailure = true;
        }
        if (failed) throw blocked();
    }

    /**
     * Activate only a strictly newer generation after all tracked work has
     * left and a separate authoritative provider confirms readiness.
     */
    public void activate(long nextGeneration) {
        final long observedFence;
        synchronized (this) {
            if (open || draining || closeFailure || !active.isEmpty()
                    || nextGeneration <= generation || nextGeneration <= 0) {
                throw blocked();
            }
            observedFence = fence;
        }

        final boolean attested;
        try {
            attested = attestor.verifyCurrentGeneration(nextGeneration);
        } catch (RuntimeException attestationFailure) {
            throw blocked();
        }
        if (!attested) throw blocked();
        synchronized (this) {
            if (open || draining || closeFailure || !active.isEmpty()
                    || nextGeneration <= generation || observedFence != fence) {
                throw blocked();
            }
            generation = nextGeneration;
            open = true;
            fence++;
        }
    }

    /**
     * Irrecoverable local close failure requires fresh process/server-side
     * reconciliation; never silently re-enable this instance.
     */
    private static SecurityException blocked() { return new SecurityException(BLOCKED); }

    public interface ConnectionOpener {
        Connection open() throws SQLException;
    }

    public interface GenerationAttestor {
        boolean verifyCurrentGeneration(long generation);
    }

    public interface JdbcWork<T> {
        T run(Connection connection) throws SQLException;
    }
}
