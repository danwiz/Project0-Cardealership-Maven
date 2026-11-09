package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wave34.5 DISPOSABLE PostgreSQL16-only migration rehearsal:
 * database-assigned ledger identity mapping of immutable historical IDs.
 * Neither application runtime code nor schema v5 is modified.
 * NOT proof of production deployment/migration, money semantics or external
 * provider reconciliation. No customer data or live payment provider.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlLedgerIdentityMigrationRehearsalTest {
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String PG_USER = "dealership_ci_owner";
    private static final String PG_PASSWORD = "ci-only-postgres-fixture";
    private static final String DDL =
            "docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql";

    @Test
    @Timeout(90)
    void historicalSixAndSevenDigitIdsRemainUnchangedUnderAdditiveMapping()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            f.seed("PAY-999999", "PAY-1000000");
            f.installTestMapping();
            f.backfill();
            assertEquals(2, f.mappingCount());
            assertEquals(2, f.legacyCount());
            assertEquals(5, f.currentSchemaVersion(),
                    "rehearsal does not increment the production version");
            try (Connection c = f.scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT legacy_transaction_id,ledger_entry_id "
                            + "FROM w345_ledger_identity_map ORDER BY legacy_transaction_id")) {
                assertTrue(r.next());
                assertEquals("PAY-1000000", r.getString(1));
                long sevenDigitKey = r.getLong(2);
                assertTrue(sevenDigitKey > 0);
                assertTrue(r.next());
                assertEquals("PAY-999999", r.getString(1));
                long sixDigitKey = r.getLong(2);
                assertTrue(sixDigitKey > 0);
                assertNotEquals(sevenDigitKey, sixDigitKey);
                assertFalse(r.next());
            }
            f.assertPlan(2, 23998);
            // Replaying the migration must neither duplicate nor rewrite a
            // preserved legacy ID. NOT evidence for production dual-write.
            f.backfill();
            assertEquals(2, f.mappingCount());
            f.assertPlan(2, 23998);
        }
    }

    @Test
    @Timeout(90)
    void concurrentFirstIdentityMappingsRemainUniqueWithoutLatestRowLock()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            String[] refs = new String[32];
            for (int i = 0; i < refs.length; i++) {
                refs[i] = String.format("PAY-%06d", i + 1);
            }
            f.seed(refs);
            f.installTestMapping();
            assertEquals(0, f.mappingCount());

            Set<Long> identities = Collections.synchronizedSet(new HashSet<>());
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch ready = new CountDownLatch(4);
                CountDownLatch fire = new CountDownLatch(1);
                java.util.List<Future<?>> futures = new java.util.ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    final int begin = worker * 8;
                    futures.add(workers.submit(() -> {
                        try (Connection c = f.scoped.openConnection()) {
                            ready.countDown();
                            if (!fire.await(15, TimeUnit.SECONDS)) {
                                throw new AssertionError("concurrency rendezvous failure");
                            }
                            for (int i = begin; i < begin + 8; i++) {
                                try (PreparedStatement p = c.prepareStatement(
                                        "INSERT INTO w345_ledger_identity_map "
                                        + "(legacy_transaction_id) VALUES (?) "
                                        + "RETURNING ledger_entry_id")) {
                                    p.setString(1, refs[i]);
                                    try (ResultSet next = p.executeQuery()) {
                                        if (!next.next() || !identities.add(next.getLong(1))
                                                || next.next()) {
                                            throw new AssertionError("duplicate DB identity");
                                        }
                                    }
                                }
                            }
                        } catch (Exception ex) {
                            throw new IllegalStateException(ex);
                        }
                    }));
                }
                assertTrue(ready.await(15, TimeUnit.SECONDS));
                fire.countDown();
                for (Future<?> future : futures) {
                    future.get(45, TimeUnit.SECONDS);
                }
            } finally {
                workers.shutdownNow();
            }
            assertEquals(32, identities.size());
            assertEquals(32, f.mappingCount());
            assertEquals(32, f.legacyCount());
            f.assertPlan(32, 23968);

            // Same external legacy references are NOT new mappings on replay.
            f.backfill();
            assertEquals(32, f.mappingCount());
            f.assertPlan(32, 23968);
        }
    }

    @Test
    @Timeout(90)
    void rolledBackMappingLeavesNoOrphanAndNeverReusesIssuedSequenceValue()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            f.seed("PAY-000001", "PAY-000002");
            f.installTestMapping();
            long abandoned;
            try (Connection c = f.scoped.openConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO w345_ledger_identity_map (legacy_transaction_id) "
                        + "VALUES ('PAY-000001') RETURNING ledger_entry_id");
                        ResultSet result = p.executeQuery()) {
                    assertTrue(result.next());
                    abandoned = result.getLong(1);
                }
                SQLException violation = assertThrows(SQLException.class, () -> {
                    try (PreparedStatement p = c.prepareStatement(
                            "INSERT INTO w345_ledger_identity_map "
                            + "(legacy_transaction_id) VALUES ('PAY-NO-SUCH-ID')")) {
                        p.executeUpdate();
                    }
                });
                assertEquals("23503", violation.getSQLState(), "missing legacy FK must fail");
                c.rollback(); // entire provisional mapping transaction rolled back
            }
            assertEquals(0, f.mappingCount());
            assertEquals(2, f.legacyCount());
            f.assertPlan(2, 23998);

            long committed;
            try (Connection c = f.scoped.openConnection();
                    PreparedStatement p = c.prepareStatement(
                            "INSERT INTO w345_ledger_identity_map "
                            + "(legacy_transaction_id) VALUES ('PAY-000001') "
                            + "RETURNING ledger_entry_id");
                    ResultSet result = p.executeQuery()) {
                assertTrue(result.next());
                committed = result.getLong(1);
            }
            assertTrue(committed > abandoned,
                    "PostgreSQL sequence values are not rolled back or gapless");
            assertEquals(1, f.mappingCount());
            f.assertPlan(2, 23998);
        }
    }

    private static void requireDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && PG_URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && PG_USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PG_PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD")),
                "Wave34.5 operates only in explicitly pinned disposable PG16 Actions");
    }

    private static final class Fixture implements AutoCloseable {
        private final String schema = "w345_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcDatabase owner = new JdbcDatabase(PG_URL, PG_USER, PG_PASSWORD);
        private final JdbcDatabase scoped = new JdbcDatabase(
                PG_URL + "?currentSchema=" + schema, PG_USER, PG_PASSWORD);
        private long ownership;

        Fixture() throws Exception {
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                try (ResultSet version = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int value = Integer.parseInt(version.getString(1));
                    assertTrue(value >= 160000 && value < 170000,
                            "PostgreSQL16 required, not H2 or an assumed server");
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // v5 in disposable schema, no application migration change
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO accounts(username,encoded_credential,role) "
                        + "VALUES('buyer','ci-test-only','CUSTOMER')");
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
        }

        void seed(String... refs) throws SQLException {
            try (Connection c = scoped.openConnection()) {
                c.setAutoCommit(false);
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO payment_transactions "
                        + "(transaction_id,customer_name,ownership_id,contract_id,"
                        + "amount,cumulative_paid,resulting_balance,recorded_at) "
                        + "VALUES (?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                    for (int i = 0; i < refs.length; i++) {
                        p.setString(1, refs[i]);
                        p.setLong(2, ownership);
                        p.setInt(3, i + 1);
                        p.setInt(4, 24000 - i - 1);
                        p.setTimestamp(5, Timestamp.from(Instant.parse(
                                "2026-01-01T00:00:00Z").plusSeconds(i)));
                        p.addBatch();
                    }
                    assertEquals(refs.length, p.executeBatch().length);
                }
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE payment_plans SET amount_paid=?,balance=? "
                        + "WHERE ownership_id=?")) {
                    p.setInt(1, refs.length);
                    p.setInt(2, 24000 - refs.length);
                    p.setLong(3, ownership);
                    assertEquals(1, p.executeUpdate());
                }
                c.commit();
            }
        }

        void installTestMapping() throws Exception {
            String ddl = new String(Files.readAllBytes(Paths.get(DDL)),
                    StandardCharsets.UTF_8);
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.execute(ddl);
            }
        }

        void backfill() throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.executeUpdate(
                        "INSERT INTO w345_ledger_identity_map(legacy_transaction_id) "
                        + "SELECT transaction_id FROM payment_transactions "
                        + "ORDER BY recorded_at,transaction_id "
                        + "ON CONFLICT(legacy_transaction_id) DO NOTHING");
            }
        }

        int mappingCount() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w345_ledger_identity_map");
        }

        int legacyCount() throws SQLException {
            return scalar("SELECT COUNT(*) FROM payment_transactions");
        }

        int currentSchemaVersion() throws SQLException {
            return scalar("SELECT MAX(version) FROM schema_history");
        }

        int scalar(String query) throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(query)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }

        void assertPlan(int paid, int balance) throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT amount_paid,balance FROM payment_plans")) {
                assertTrue(r.next());
                assertEquals(paid, r.getInt(1));
                assertEquals(balance, r.getInt(2));
                assertFalse(r.next());
            }
        }

        @Override
        public void close() throws Exception {
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
