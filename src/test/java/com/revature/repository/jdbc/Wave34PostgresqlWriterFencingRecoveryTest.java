package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wave34.7 DISPOSABLE PG16 characterization of a cooperative writer epoch.
 * The test role owns the database and can defeat the fixture; client supplied
 * GUCs are spoofable. This is not production security, leader quorum, provider
 * authentication or an approved operator migration. Schema v5 is unchanged.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlWriterFencingRecoveryTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String DDL =
            "docs/audit/wave34/wave34_7_DISPOSABLE_cooperative_writer_fence.sql";

    @Test
    @Timeout(90)
    void committedFreezeDeniesStaleAndFrozenWritersUntilNewEpochIsOpened()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            f.post(1, "PAY-000001", 1);
            assertEquals(1, f.count());
            assertEquals(1, f.paid());
            f.freeze(1, 2);
            assertEquals("FROZEN", f.state().mode);
            assertEquals(2L, f.state().epoch);

            SQLException stale = assertThrows(SQLException.class,
                    () -> f.post(1, "PAY-000002", 2));
            assertEquals("55000", stale.getSQLState());
            SQLException frozen = assertThrows(SQLException.class,
                    () -> f.post(2, "PAY-000002", 2));
            assertEquals("55000", frozen.getSQLState());
            assertEquals(1, f.count());
            assertEquals(1, f.paid());

            f.open(2, 3);
            SQLException oldLease = assertThrows(SQLException.class,
                    () -> f.post(1, "PAY-000002", 2));
            assertEquals("55000", oldLease.getSQLState());
            f.post(3, "PAY-000002", 2);
            assertEquals(2, f.count());
            assertEquals(2, f.paid());
            assertEquals(23998, f.balance());
            f.emitEvidence("committed-freeze");
        }
    }

    @Test
    @Timeout(90)
    void activeWriterShareLockPreventsFenceCommitUntilWriterCompletes()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture();
                Connection writer = f.scoped.openConnection()) {
            writer.setAutoCommit(false);
            f.stagePayment(writer, 1, "PAY-000001", 1);
            try (Connection operator = f.scoped.openConnection()) {
                operator.setAutoCommit(false);
                try (Statement timeout = operator.createStatement()) {
                    timeout.execute("SET LOCAL lock_timeout = '750ms'");
                }
                SQLException locked = assertThrows(SQLException.class,
                        () -> f.freezeWithin(operator, 1, 2));
                assertEquals("55P03", locked.getSQLState(),
                        "freeze must be serialized behind active row-level writer");
                operator.rollback();
            }
            assertEquals("OPEN", f.state().mode);
            assertEquals(1L, f.state().epoch);
            writer.commit();
            assertEquals(1, f.count());

            f.freeze(1, 2);
            assertEquals("FROZEN", f.state().mode);
            SQLException rejected = assertThrows(SQLException.class,
                    () -> f.post(1, "PAY-000002", 2));
            assertEquals("55000", rejected.getSQLState());
            assertEquals(1, f.count());
            f.emitEvidence("writer-drain-barrier");
        }
    }

    @Test
    @Timeout(90)
    void abortedOperatorFreezeRollsBackEpochAndPreservesLedgerDigest()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            f.post(1, "PAY-000001", 1);
            String original = f.digest();
            try (Connection operator = f.scoped.openConnection()) {
                operator.setAutoCommit(false);
                assertEquals(1, f.freezeWithin(operator, 1, 2));
                SQLException badState = assertThrows(SQLException.class, () -> {
                    try (Statement sql = operator.createStatement()) {
                        sql.executeUpdate(
                                "UPDATE w347_writer_fence SET mode='INVALID' WHERE singleton_id=1");
                    }
                });
                assertEquals("23514", badState.getSQLState());
                operator.rollback();
            }
            assertEquals("OPEN", f.state().mode);
            assertEquals(1L, f.state().epoch);
            assertEquals(original, f.digest());
            f.post(1, "PAY-000002", 2);
            f.freeze(1, 2);
            SQLException stale = assertThrows(SQLException.class,
                    () -> f.post(1, "PAY-000003", 3));
            assertEquals("55000", stale.getSQLState());
            assertEquals(2, f.count());
            assertEquals(23998, f.balance());
            assertNotEquals(original, f.digest());
            f.emitEvidence("aborted-operator-recovery");
        }
    }

    private static void requireDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD") != null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "The exact-SHA disposable PostgreSQL 16 Actions environment is required");
    }

    private static final class Fence {
        final long epoch;
        final String mode;
        Fence(long epoch, String mode) {
            this.epoch = epoch;
            this.mode = mode;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema = "w347_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcDatabase operatorDb = new JdbcDatabase(URL, USER, PASSWORD);
        final JdbcDatabase scoped =
                new JdbcDatabase(URL + "?currentSchema=" + schema, USER, PASSWORD);
        long ownership;

        Fixture() throws Exception {
            try (Connection c = operatorDb.openConnection();
                    Statement s = c.createStatement()) {
                try (ResultSet server = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(server.next());
                    int num = Integer.parseInt(server.getString(1));
                    assertTrue(num >= 160000 && num < 170000, "real PostgreSQL16 required");
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // Only synthetic schema v5.
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO accounts(username,encoded_credential,role) "
                        + "VALUES('buyer','ci-only','CUSTOMER')");
            }
            try (Connection c = scoped.openConnection(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO owned_vehicles "
                    + "(owner_username,make,model,vehicle_year,contract_id) "
                    + "VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1, p.executeUpdate());
                try (ResultSet r = p.getGeneratedKeys()) {
                    assertTrue(r.next());
                    ownership = r.getLong(1);
                }
            }
            try (Connection c = scoped.openConnection(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO payment_plans "
                    + "(ownership_id,purchase_price,payment_months,monthly_payment,"
                    + "amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1, ownership);
                assertEquals(1, p.executeUpdate());
            }
            String ddl = new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8);
            String[] statements = ddl.split("(?m)^-- W347_STATEMENT_BREAK[ \\t]*\\r?$");
            assertEquals(5, statements.length, "test-only DDL must contain five ordered statements");
            try (Connection c = scoped.openConnection(); Statement statement = c.createStatement()) {
                for (String sql : statements) {
                    assertFalse(sql.trim().isEmpty());
                    statement.execute(sql);
                }
            }
            assertEquals(5, schemaVersion());
        }

        void stagePayment(Connection connection, long epoch, String id, int cumulative)
                throws SQLException {
            try (PreparedStatement config = connection.prepareStatement(
                    "SELECT set_config('w347.writer_epoch', ?, true)")) {
                config.setString(1, Long.toString(epoch));
                try (ResultSet r = config.executeQuery()) {
                    assertTrue(r.next());
                    assertEquals(Long.toString(epoch), r.getString(1));
                }
            }
            try (PreparedStatement plan = connection.prepareStatement(
                    "UPDATE payment_plans SET amount_paid=amount_paid+1,"
                    + "balance=balance-1 WHERE ownership_id=?")) {
                plan.setLong(1, ownership);
                assertEquals(1, plan.executeUpdate());
            }
            try (PreparedStatement ledger = connection.prepareStatement(
                    "INSERT INTO payment_transactions "
                    + "(transaction_id,customer_name,ownership_id,contract_id,"
                    + "amount,cumulative_paid,resulting_balance,recorded_at) "
                    + "VALUES(?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                ledger.setString(1, id);
                ledger.setLong(2, ownership);
                ledger.setInt(3, cumulative);
                ledger.setInt(4, 24000 - cumulative);
                ledger.setTimestamp(5, Timestamp.from(
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(cumulative)));
                assertEquals(1, ledger.executeUpdate());
            }
        }

        void post(long epoch, String id, int cumulative) throws SQLException {
            try (Connection c = scoped.openConnection()) {
                c.setAutoCommit(false);
                try {
                    stagePayment(c, epoch, id, cumulative);
                    c.commit();
                } catch (SQLException | RuntimeException failure) {
                    c.rollback();
                    throw failure;
                }
            }
        }

        int freezeWithin(Connection c, long fromEpoch, long nextEpoch) throws SQLException {
            try (PreparedStatement statement = c.prepareStatement(
                    "UPDATE w347_writer_fence SET epoch=?, mode='FROZEN',"
                    + " frozen_at=clock_timestamp() WHERE singleton_id=1"
                    + " AND epoch=? AND mode='OPEN'")) {
                statement.setLong(1, nextEpoch);
                statement.setLong(2, fromEpoch);
                return statement.executeUpdate();
            }
        }

        void freeze(long oldEpoch, long nextEpoch) throws SQLException {
            try (Connection c = scoped.openConnection()) {
                c.setAutoCommit(false);
                assertEquals(1, freezeWithin(c, oldEpoch, nextEpoch));
                c.commit();
            }
        }

        void open(long oldEpoch, long nextEpoch) throws SQLException {
            try (Connection c = scoped.openConnection(); PreparedStatement statement = c.prepareStatement(
                    "UPDATE w347_writer_fence SET epoch=?, mode='OPEN',frozen_at=NULL "
                    + "WHERE singleton_id=1 AND epoch=? AND mode='FROZEN'")) {
                statement.setLong(1, nextEpoch);
                statement.setLong(2, oldEpoch);
                assertEquals(1, statement.executeUpdate());
            }
        }

        Fence state() throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT epoch,mode FROM w347_writer_fence WHERE singleton_id=1")) {
                assertTrue(r.next());
                Fence f = new Fence(r.getLong(1), r.getString(2));
                assertFalse(r.next());
                return f;
            }
        }

        int count() throws SQLException {
            return scalar("SELECT COUNT(*) FROM payment_transactions");
        }
        int paid() throws SQLException {
            return scalar("SELECT amount_paid FROM payment_plans");
        }
        int balance() throws SQLException {
            return scalar("SELECT balance FROM payment_plans");
        }
        int schemaVersion() throws SQLException {
            return scalar("SELECT MAX(version) FROM schema_history");
        }
        int scalar(String sql) throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(sql)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }

        String digest() throws Exception {
            StringBuilder canonical = new StringBuilder();
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,"
                            + "contract_id,amount,cumulative_paid,resulting_balance,recorded_at "
                            + "FROM payment_transactions ORDER BY transaction_id")) {
                while (r.next()) {
                    for (int i = 1; i <= 8; i++) {
                        canonical.append(r.getString(i)).append('\u0000');
                    }
                }
            }
            canonical.append("plan=").append(paid()).append(':').append(balance());
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(
                    canonical.toString().getBytes(StandardCharsets.UTF_8));
            char[] alphabet = "0123456789abcdef".toCharArray();
            StringBuilder hex = new StringBuilder(64);
            for (byte b : hash) {
                hex.append(alphabet[(b >>> 4) & 15]).append(alphabet[b & 15]);
            }
            return hex.toString();
        }

        void emitEvidence(String scenario) throws Exception {
            Fence fence = state();
            Path directory = Paths.get("target/wave34-7-evidence");
            Files.createDirectories(directory);
            String json = "{\n"
                    + "  \"scenario\":\"" + scenario + "\",\n"
                    + "  \"head_sha\":\"" + System.getenv("EXPECTED_HEAD") + "\",\n"
                    + "  \"fixture\":\"DISPOSABLE_PG16_ONLY\",\n"
                    + "  \"release\":\"NO-GO\",\n"
                    + "  \"epoch\":" + fence.epoch + ",\n"
                    + "  \"mode\":\"" + fence.mode + "\",\n"
                    + "  \"schema_version\":" + schemaVersion() + ",\n"
                    + "  \"synthetic_postings\":" + count() + ",\n"
                    + "  \"synthetic_amount_paid\":" + paid() + ",\n"
                    + "  \"synthetic_balance\":" + balance() + ",\n"
                    + "  \"ledger_sha256\":\"" + digest() + "\",\n"
                    + "  \"production_writer_fenced\":false,\n"
                    + "  \"production_authorized\":false\n"
                    + "}\n";
            Files.writeString(directory.resolve(scenario + ".json"), json,
                    StandardCharsets.UTF_8);
        }

        @Override public void close() throws Exception {
            try (Connection c = operatorDb.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
