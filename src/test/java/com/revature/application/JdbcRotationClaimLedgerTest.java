package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.JdbcRotationClaimLedger.Floor;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;

/** Fixture-owned SQL ledger; intentionally NOT an independent audit authority. */
class JdbcRotationClaimLedgerTest {
    private static final String STREAM = "ci-durable-rotation";

    @Test
    void persistsAndReloadsStrictlyMonotonicRecoveryFloor() throws Exception {
        JdbcDatabase database = fixture();
        JdbcRotationClaimLedger writerA = new JdbcRotationClaimLedger(database::openConnection);
        Floor initial = writerA.load(STREAM);
        assertEquals(0, initial.generation());
        assertEquals(0, initial.epoch());
        assertEquals(checkpoint(0), initial.checkpoint());

        ReopenProof next = proof(0, 1, 8, 20);
        assertTrue(writerA.claim(next));
        assertFalse(writerA.claim(next), "replayed claim must not advance floor");

        // Separate client, representing a freshly started worker.
        JdbcRotationClaimLedger restarted = new JdbcRotationClaimLedger(database::openConnection);
        Floor recovered = restarted.load(STREAM);
        assertEquals(1, recovered.generation());
        assertEquals(8, recovered.epoch());
        assertEquals(checkpoint(20), recovered.checkpoint());
        assertFalse(restarted.claim(proof(0, 2, 9, 21)));
        assertFalse(restarted.claim(proof(1, 2, 8, 21)));
        assertFalse(restarted.claim(proof(1, 2, 9, 19)),
                "archive rollback must not be allowed");
        assertTrue(restarted.claim(proof(1, 2, 9, 20)));
        assertEquals(2, writerA.load(STREAM).generation());
        assertEquals(9, writerA.load(STREAM).epoch());
    }

    @Test
    void twoIndependentGatesCannotClaimSameEpoch() throws Exception {
        JdbcDatabase database = fixture();
        JdbcRotationClaimLedger ledgerA = new JdbcRotationClaimLedger(database::openConnection);
        JdbcRotationClaimLedger ledgerB = new JdbcRotationClaimLedger(database::openConnection);
        // The proof checker here only demonstrates atomic DB claiming, NOT
        // actual external source/archive verification or server-side fencing.
        ReconciledJdbcWriterGate gateA = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> generation == 1,
                ledgerA::claim, checkpoint(0), 0, 0);
        ReconciledJdbcWriterGate gateB = new ReconciledJdbcWriterGate(
                database::openConnection, generation -> generation == 1,
                ledgerB::claim, checkpoint(0), 0, 0);
        ReopenProof candidate = proof(0, 1, 1, 3);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch race = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = workers.submit(() -> activate(gateA, candidate, ready, race));
            Future<Boolean> second = workers.submit(() -> activate(gateB, candidate, ready, race));
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            race.countDown();
            boolean aWon = first.get(10, TimeUnit.SECONDS);
            boolean bWon = second.get(10, TimeUnit.SECONDS);
            assertTrue(aWon != bWon, "exactly one worker may claim an epoch");
            assertEquals(1, ledgerA.load(STREAM).generation());
            assertEquals(1, ledgerB.load(STREAM).epoch());
            assertEquals(Boolean.TRUE, (aWon ? gateA : gateB).withConnection(c -> true));
            assertThrows(SecurityException.class,
                    () -> (aWon ? gateB : gateA).withConnection(c -> true));
        } finally {
            race.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test
    void missingRowAndSqlOutageAreFailClosedWithoutLeakingDetails() throws Exception {
        JdbcDatabase database = fixture();
        JdbcRotationClaimLedger ledger = new JdbcRotationClaimLedger(database::openConnection);
        SecurityException missing = assertThrows(SecurityException.class,
                () -> ledger.load("other-stream"));
        assertEquals("rotation claim authority unavailable", missing.getMessage());
        assertThrows(SecurityException.class, () -> ledger.claim(new ReopenProof(
                0, 1, 1, checkpoint("other-stream", 1), checkpoint("other-stream", 1))));
        JdbcRotationClaimLedger failed = new JdbcRotationClaimLedger(() -> {
            throw new SQLException("sensitive-connection-details");
        });
        SecurityException sanitized = assertThrows(SecurityException.class,
                () -> failed.claim(proof(0, 1, 1, 1)));
        assertEquals("rotation claim authority unavailable", sanitized.getMessage());
        assertThrows(SecurityException.class, () -> failed.load(STREAM));
        assertEquals(0, ledger.load(STREAM).generation());
    }

    private static boolean activate(ReconciledJdbcWriterGate gate, ReopenProof proof,
            CountDownLatch ready, CountDownLatch race) throws Exception {
        ready.countDown();
        if (!race.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race timed out");
        try {
            gate.activate(proof);
            return true;
        } catch (SecurityException denied) {
            return false;
        }
    }

    private static JdbcDatabase fixture() throws SQLException {
        JdbcDatabase db = JdbcDatabase.inMemory("rotation_claim_" +
                UUID.randomUUID().toString().replace("-", ""));
        try (Connection connection = db.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE writer_rotation_floors ("
                    + "stream_id VARCHAR(100) PRIMARY KEY, credential_generation BIGINT NOT NULL, "
                    + "rotation_epoch BIGINT NOT NULL, cursor_position BIGINT NOT NULL, "
                    + "cursor_ordinal INTEGER NOT NULL)");
            statement.execute("INSERT INTO writer_rotation_floors VALUES ('" + STREAM + "', 0, 0, 0, 0)");
        }
        return db;
    }

    private static AuditCheckpoint checkpoint(long position) {
        return checkpoint(STREAM, position);
    }

    private static AuditCheckpoint checkpoint(String stream, long position) {
        return new AuditCheckpoint(stream, new AuditSourceCursor(position, 0));
    }

    private static ReopenProof proof(long previous, long next, long epoch, long position) {
        return new ReopenProof(previous, next, epoch,
                checkpoint(position), checkpoint(position));
    }
}
