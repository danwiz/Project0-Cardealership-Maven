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

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;

/** Disposable GitHub CI PostgreSQL only, never staging or production. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlRotationClaimIntegrationTest {
    @Test
    void twoSqlClientsSerializeClaimAndPersistWinnerAcrossRestart() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String owner = System.getenv("DEALERSHIP_PG_TEST_USER");
        String password = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(owner)
                        && "ci-only-postgres-fixture".equals(password),
                "Rotation claim integration uses exact disposable CI PostgreSQL only");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "claim_" + suffix;
        String claimant = "claim_operator_" + suffix;
        String appRole = "claim_application_" + suffix;
        String secret = "ci-only-claim-secret";
        JdbcDatabase admin = new JdbcDatabase(baseUrl, owner, password);
        try (Connection connection = admin.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE ROLE " + claimant + " LOGIN PASSWORD '" + secret + "'");
            sql.execute("CREATE ROLE " + appRole + " LOGIN PASSWORD '" + secret + "'");
            sql.execute("CREATE TABLE " + schema + ".writer_rotation_floors ("
                    + "stream_id VARCHAR(100) PRIMARY KEY, credential_generation BIGINT NOT NULL, "
                    + "rotation_epoch BIGINT NOT NULL, cursor_position BIGINT NOT NULL, "
                    + "cursor_ordinal INTEGER NOT NULL)");
            sql.execute("INSERT INTO " + schema + ".writer_rotation_floors "
                    + "VALUES ('ci-postgres-stream', 0, 0, 0, 0)");
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + claimant);
            sql.execute("GRANT SELECT, UPDATE ON " + schema
                    + ".writer_rotation_floors TO " + claimant);
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + appRole);
        }

        String scopedUrl = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase claimantDb = new JdbcDatabase(scopedUrl, claimant, secret);
        JdbcRotationClaimLedger first = new JdbcRotationClaimLedger(claimantDb::openConnection);
        JdbcRotationClaimLedger second = new JdbcRotationClaimLedger(claimantDb::openConnection);
        JdbcRotationClaimLedger application = new JdbcRotationClaimLedger(
                new JdbcDatabase(scopedUrl, appRole, secret)::openConnection);
        assertThrows(SecurityException.class, () -> application.load("ci-postgres-stream"));
        assertThrows(SecurityException.class, () -> application.claim(proof(0, 1, 1)));

        ReopenProof candidate = proof(0, 1, 1);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> a = workers.submit(() -> claim(first, candidate, ready, release));
            Future<Boolean> b = workers.submit(() -> claim(second, candidate, ready, release));
            assertTrue(ready.await(15, TimeUnit.SECONDS));
            release.countDown();
            boolean aWon = a.get(15, TimeUnit.SECONDS);
            boolean bWon = b.get(15, TimeUnit.SECONDS);
            assertTrue(aWon != bWon, "only one transaction can claim this epoch");
        } finally {
            release.countDown();
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(15, TimeUnit.SECONDS));
        }

        // A new SQL client, not either process-local cache, observes the win.
        JdbcRotationClaimLedger restarted = new JdbcRotationClaimLedger(claimantDb::openConnection);
        assertEquals(1, restarted.load("ci-postgres-stream").generation());
        assertEquals(1, restarted.load("ci-postgres-stream").epoch());
        assertFalse(restarted.claim(candidate));
        assertTrue(restarted.claim(proof(1, 2, 2)));
        assertEquals(2, first.load("ci-postgres-stream").generation());
    }

    private static boolean claim(JdbcRotationClaimLedger ledger, ReopenProof proof,
            CountDownLatch ready, CountDownLatch release) throws Exception {
        ready.countDown();
        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("race timed out");
        return ledger.claim(proof);
    }

    private static ReopenProof proof(long previous, long next, long epoch) {
        AuditCheckpoint checkpoint = new AuditCheckpoint("ci-postgres-stream",
                new AuditSourceCursor(epoch, 0));
        return new ReopenProof(previous, next, epoch, checkpoint, checkpoint);
    }
}
