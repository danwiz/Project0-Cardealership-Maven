package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;

/** Fixture-only proof metadata, not an independent audit archive or provider. */
class ReconciledJdbcWriterGateTest {
    private static final String STREAM = "ci-audit-stream";

    @Test
    void neverOpensWithoutConsistentFreshAndIndependentlyVerifiedEvidence()
            throws Exception {
        JdbcDatabase database = database();
        AtomicInteger verifications = new AtomicInteger();
        ReconciledJdbcWriterGate gate = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> generation == 1 || generation == 2,
                proof -> {
                    verifications.incrementAndGet();
                    return true;
                }, checkpoint(STREAM, 0), 0, 0);

        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
        assertDenied(() -> gate.activate(null));
        assertDenied(() -> gate.activate(proof(0, 1, 1,
                checkpoint(STREAM, 8), checkpoint(STREAM, 7))));
        assertDenied(() -> gate.activate(proof(0, 1, 1,
                checkpoint("other-stream", 8), checkpoint("other-stream", 8))));
        assertDenied(() -> gate.activate(proof(0, 1, 0,
                checkpoint(STREAM, 8), checkpoint(STREAM, 8))));
        assertDenied(() -> gate.activate(proof(1, 2, 1,
                checkpoint(STREAM, 8), checkpoint(STREAM, 8))));
        assertEquals(0, verifications.get(), "invalid evidence must not reach the authority");

        gate.activate(proof(0, 1, 1,
                checkpoint(STREAM, 8), checkpoint(STREAM, 8)));
        assertEquals(1, verifications.get());
        assertEquals(Boolean.TRUE, gate.withConnection(c -> !c.isClosed()));
        assertDenied(() -> gate.activate(proof(1, 2, 2,
                checkpoint(STREAM, 9), checkpoint(STREAM, 9))));
        gate.pauseAndDrain(1);
        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
        gate.activate(proof(1, 2, 2,
                checkpoint(STREAM, 9), checkpoint(STREAM, 9)));
        assertEquals(Boolean.TRUE, gate.withConnection(c -> true));
    }

    @Test
    void rejectsReplayArchiveRollbackAndWrongGenerationAfterDrain() throws Exception {
        JdbcDatabase database = database();
        ReconciledJdbcWriterGate gate = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> true, proof -> true,
                checkpoint(STREAM, 30), 5, 12);

        assertDenied(() -> gate.activate(proof(4, 6, 13,
                checkpoint(STREAM, 31), checkpoint(STREAM, 31))));
        assertDenied(() -> gate.activate(proof(5, 5, 13,
                checkpoint(STREAM, 31), checkpoint(STREAM, 31))));
        assertDenied(() -> gate.activate(proof(5, 6, 13,
                checkpoint(STREAM, 29), checkpoint(STREAM, 29))));
        gate.activate(proof(5, 6, 13,
                checkpoint(STREAM, 30), checkpoint(STREAM, 30)));
        gate.pauseAndDrain(6);

        assertDenied(() -> gate.activate(proof(5, 6, 13,
                checkpoint(STREAM, 30), checkpoint(STREAM, 30))));
        assertDenied(() -> gate.activate(proof(6, 7, 13,
                checkpoint(STREAM, 31), checkpoint(STREAM, 31))));
        assertDenied(() -> gate.activate(proof(6, 7, 14,
                checkpoint(STREAM, 31), checkpoint(STREAM, 30))));
        gate.activate(proof(6, 7, 14,
                checkpoint(STREAM, 31), checkpoint(STREAM, 31)));
        assertEquals(Boolean.TRUE, gate.withConnection(c -> true));
    }

    @Test
    void authorityOutageAndRejectedSecretGenerationKeepConnectionsClosed()
            throws Exception {
        JdbcDatabase database = database();
        AtomicInteger authorityCalls = new AtomicInteger();
        ReconciledJdbcWriterGate gate = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> generation == 4,
                proof -> {
                    int call = authorityCalls.incrementAndGet();
                    if (call == 1) return false;
                    if (call == 2) throw new IllegalStateException("sensitive-provider-detail");
                    return true;
                }, checkpoint(STREAM, 0), 0, 0);
        ReopenProof candidate = proof(0, 4, 1,
                checkpoint(STREAM, 1), checkpoint(STREAM, 1));
        assertDenied(() -> gate.activate(candidate));
        SecurityException sanitized = assertThrows(SecurityException.class,
                () -> gate.activate(candidate));
        assertEquals("writer reconciliation unavailable", sanitized.getMessage());
        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
        gate.activate(candidate);
        assertEquals(Boolean.TRUE, gate.withConnection(c -> true));

        gate.pauseAndDrain(4);
        assertThrows(SecurityException.class, () -> gate.activate(proof(4, 5, 2,
                checkpoint(STREAM, 2), checkpoint(STREAM, 2))));
        assertThrows(SecurityException.class, () -> gate.withConnection(c -> true));
    }

    @Test
    void cannotReopenUntilOldTrackedCallbackLeaves() throws Exception {
        JdbcDatabase database = database();
        ReconciledJdbcWriterGate gate = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> true, proof -> true,
                checkpoint(STREAM, 0), 0, 0);
        gate.activate(proof(0, 1, 1,
                checkpoint(STREAM, 0), checkpoint(STREAM, 0)));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try {
            Future<Boolean> pending = worker.submit(() -> gate.withConnection(c -> {
                entered.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new SQLException("CI synchronization timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new SQLException("CI synchronization interrupted", interrupted);
                }
                return true;
            }));
            assertTrue(entered.await(10, TimeUnit.SECONDS));
            gate.pauseAndDrain(1);
            ReopenProof next = proof(1, 2, 2,
                    checkpoint(STREAM, 1), checkpoint(STREAM, 1));
            assertThrows(SecurityException.class, () -> gate.activate(next));
            release.countDown();
            ExecutionException rejected = assertThrows(ExecutionException.class,
                    () -> pending.get(10, TimeUnit.SECONDS));
            assertTrue(rejected.getCause() instanceof SecurityException);
            gate.activate(next);
            assertEquals(Boolean.TRUE, gate.withConnection(c -> true));
        } finally {
            release.countDown();
            worker.shutdownNow();
            assertTrue(worker.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void rejectsInvalidIndependentRecoveryFloors() {
        JdbcDatabase database = database();
        assertThrows(IllegalArgumentException.class, () -> new ReconciledJdbcWriterGate(
                database::openConnection, generation -> true, proof -> true,
                checkpoint(STREAM, 0), -1, 0));
        assertThrows(IllegalArgumentException.class, () -> new ReconciledJdbcWriterGate(
                database::openConnection, generation -> true, proof -> true,
                checkpoint(STREAM, 0), 0, -1));
    }

    private static JdbcDatabase database() {
        return JdbcDatabase.inMemory("reconciled_gate_" +
                UUID.randomUUID().toString().replace("-", ""));
    }

    private static AuditCheckpoint checkpoint(String stream, long position) {
        return new AuditCheckpoint(stream, new AuditSourceCursor(position, 0));
    }

    private static ReopenProof proof(long previous, long next, long epoch,
            AuditCheckpoint source, AuditCheckpoint archive) {
        return new ReopenProof(previous, next, epoch, source, archive);
    }

    private static void assertDenied(CheckedAction action) {
        assertThrows(SecurityException.class, action::run);
    }

    private interface CheckedAction {
        void run() throws Exception;
    }
}
