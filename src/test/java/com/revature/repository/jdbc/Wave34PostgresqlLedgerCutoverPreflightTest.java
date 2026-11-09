package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * W34.6 DISPOSABLE PG16 cutover PREFLIGHT CHARACTERIZATION ONLY.
 *
 * A passing preflight is a snapshot-consistency experiment, NOT a production
 * cutover permission. It cannot establish a quiesced writer epoch, a valid
 * merchant charge, currency correctness, or operator authorization.
 * Uses the W34.5 additive mapping table inside randomized test schemas.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlLedgerCutoverPreflightTest {
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String PG_USER = "dealership_ci_owner";
    private static final String PG_PASSWORD = "ci-only-postgres-fixture";

    @Test
    @Timeout(90)
    void mappingCoverageAndImmutableSourceDigestMustMatchBeforeSyntheticPreflight()
            throws Exception {
        requiredDisposableCi();
        try (Fixture fixture = new Fixture()) {
            fixture.seed("PAY-000001", "PAY-999999", "PAY-1000000");
            fixture.installMapping();
            Snapshot baseline = fixture.snapshot();
            Preflight missing = fixture.preflight(baseline);
            assertFalse(missing.ready);
            assertEquals(3, missing.missing);
            assertEquals(0, missing.orphans);
            assertEquals(0, missing.mapped);

            fixture.mapOne("PAY-000001");
            assertFalse(fixture.preflight(baseline).ready);
            assertEquals(2, fixture.preflight(baseline).missing);

            fixture.backfill();
            Preflight complete = fixture.preflight(baseline);
            assertTrue(complete.ready, "coverage alone is meaningful only with unchanged source digest and plan");
            assertEquals(0, complete.missing);
            assertEquals(0, complete.orphans);
            assertEquals(3, complete.mapped);
            assertEquals(5, complete.schemaVersion);
            Map<String, Long> firstIds = fixture.mapping();
            assertEquals(3, firstIds.size());
            assertNotEquals(firstIds.get("PAY-999999"), firstIds.get("PAY-1000000"));

            fixture.backfill();
            assertEquals(firstIds, fixture.mapping(),
                    "idempotent backfill cannot replace an existing numeric identity");
            assertEquals(baseline.digest, fixture.snapshot().digest,
                    "original legacy ledger source bytes must remain unchanged");
            assertEquals(baseline.paid, fixture.snapshot().paid);
            assertEquals(baseline.balance, fixture.snapshot().balance);
            assertTrue(fixture.preflight(baseline).ready);
        }
    }

    @Test
    @Timeout(90)
    void sourceDriftInvalidatesEarlierPassingSnapshotEvenWhenMappingIsRepaired()
            throws Exception {
        requiredDisposableCi();
        try (Fixture fixture = new Fixture()) {
            fixture.seed("PAY-000001", "PAY-000002");
            fixture.installMapping();
            fixture.backfill();
            Snapshot oldBaseline = fixture.snapshot();
            assertTrue(fixture.preflight(oldBaseline).ready);
            Map<String, Long> prior = fixture.mapping();

            // Synthetic writer moves the source after an old readiness snapshot.
            // Without a writer-fence a preflight result must never authorize cutover.
            fixture.seed("PAY-000003");
            Preflight unaccounted = fixture.preflight(oldBaseline);
            assertFalse(unaccounted.ready);
            assertEquals(1, unaccounted.missing);
            assertNotEquals(oldBaseline.digest, fixture.snapshot().digest);

            fixture.backfill();
            Preflight stale = fixture.preflight(oldBaseline);
            assertFalse(stale.ready,
                    "full mapping coverage cannot validate stale approved-source evidence");
            assertEquals(0, stale.missing);
            assertEquals(3, stale.mapped);
            for (Map.Entry<String, Long> entry : prior.entrySet()) {
                assertEquals(entry.getValue(), fixture.mapping().get(entry.getKey()),
                        "existing legacy identity mapping cannot be reassigned");
            }
            Snapshot newObservation = fixture.snapshot();
            assertTrue(fixture.preflight(newObservation).ready,
                    "fresh synthetic evidence can pass; real cutover still needs hard writer fencing");
            assertEquals(3, newObservation.paid);
            assertEquals(23997, newObservation.balance);
        }
    }

    @Test
    @Timeout(90)
    void failedMappingTransactionRevertsAllMappingsAndKeepsSourceSnapshotIntact()
            throws Exception {
        requiredDisposableCi();
        try (Fixture fixture = new Fixture()) {
            fixture.seed("PAY-000001", "PAY-000002");
            fixture.installMapping();
            Snapshot baseline = fixture.snapshot();
            try (Connection c = fixture.scoped.openConnection()) {
                c.setAutoCommit(false);
                try (Statement statement = c.createStatement()) {
                    statement.executeUpdate(
                            "INSERT INTO w345_ledger_identity_map(legacy_transaction_id) "
                            + "VALUES('PAY-000001')");
                    SQLException failure = assertThrows(SQLException.class,
                            () -> statement.executeUpdate(
                                    "INSERT INTO w345_ledger_identity_map(legacy_transaction_id) "
                                    + "VALUES('PAY-FORGED-NONEXISTENT')"));
                    assertEquals("23503", failure.getSQLState(),
                            "foreign-key failure must abort the candidate mapping transaction");
                } finally {
                    c.rollback();
                }
            }
            assertEquals(0, fixture.mapping().size());
            assertEquals(2, fixture.preflight(baseline).missing);
            assertEquals(baseline.digest, fixture.snapshot().digest);
            assertEquals(baseline.paid, fixture.snapshot().paid);
            assertEquals(baseline.balance, fixture.snapshot().balance);

            fixture.backfill();
            assertTrue(fixture.preflight(baseline).ready);
            assertEquals(2, fixture.mapping().size());
        }
    }

    private static void requiredDisposableCi() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && PG_URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && PG_USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PG_PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD")),
                "Only the pinned GitHub Actions disposable PG16 fixture is authorized");
    }

    private static final class Snapshot {
        final int count, paid, balance;
        final String digest;
        Snapshot(int count, int paid, int balance, String digest) {
            this.count = count;
            this.paid = paid;
            this.balance = balance;
            this.digest = digest;
        }
    }

    private static final class Preflight {
        final boolean ready;
        final int missing, orphans, mapped, schemaVersion;
        Preflight(boolean ready, int missing, int orphans, int mapped, int schemaVersion) {
            this.ready = ready;
            this.missing = missing;
            this.orphans = orphans;
            this.mapped = mapped;
            this.schemaVersion = schemaVersion;
        }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema = "w346_" + UUID.randomUUID().toString().replace("-", "");
        final JdbcDatabase owner = new JdbcDatabase(PG_URL, PG_USER, PG_PASSWORD);
        final JdbcDatabase scoped = new JdbcDatabase(
                PG_URL + "?currentSchema=" + schema, PG_USER, PG_PASSWORD);
        long ownership;

        Fixture() throws Exception {
            try (Connection connection = owner.openConnection();
                    Statement statement = connection.createStatement()) {
                try (ResultSet r = statement.executeQuery("SHOW server_version_num")) {
                    assertTrue(r.next());
                    int serverVersion = Integer.parseInt(r.getString(1));
                    assertTrue(serverVersion >= 160000 && serverVersion < 170000,
                            "real PostgreSQL16 required");
                }
                statement.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // Only existing schema v5, disposable schema.
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO accounts(username,encoded_credential,role)"
                        + " VALUES('buyer','ci-test-credential','CUSTOMER')");
            }
            try (Connection c = scoped.openConnection(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year,contract_id)"
                    + " VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1, p.executeUpdate());
                try (ResultSet r = p.getGeneratedKeys()) {
                    assertTrue(r.next());
                    ownership = r.getLong(1);
                }
            }
            try (Connection c = scoped.openConnection(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO payment_plans(ownership_id,purchase_price,payment_months,"
                    + "monthly_payment,amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1, ownership);
                assertEquals(1, p.executeUpdate());
            }
        }

        void seed(String... refs) throws SQLException {
            try (Connection c = scoped.openConnection()) {
                c.setAutoCommit(false);
                int current = count(c, "SELECT count(*) FROM payment_transactions");
                try (PreparedStatement p = c.prepareStatement(
                        "INSERT INTO payment_transactions "
                        + "(transaction_id,customer_name,ownership_id,contract_id,"
                        + "amount,cumulative_paid,resulting_balance,recorded_at) "
                        + "VALUES (?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                    for (int i = 0; i < refs.length; i++) {
                        int cumulative = current + i + 1;
                        p.setString(1, refs[i]);
                        p.setLong(2, ownership);
                        p.setInt(3, cumulative);
                        p.setInt(4, 24000 - cumulative);
                        p.setTimestamp(5, Timestamp.from(
                                Instant.parse("2026-01-01T00:00:00Z").plusSeconds(cumulative)));
                        p.addBatch();
                    }
                    int[] results = p.executeBatch();
                    assertEquals(refs.length, results.length);
                }
                try (PreparedStatement p = c.prepareStatement(
                        "UPDATE payment_plans SET amount_paid=?, balance=? WHERE ownership_id=?")) {
                    int total = current + refs.length;
                    p.setInt(1, total);
                    p.setInt(2, 24000 - total);
                    p.setLong(3, ownership);
                    assertEquals(1, p.executeUpdate());
                }
                c.commit();
            }
        }

        void installMapping() throws Exception {
            String sql = new String(Files.readAllBytes(Paths.get(
                    "docs/audit/wave34/wave34_5_disposable_ledger_identity_mapping.sql")),
                    StandardCharsets.UTF_8);
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.execute(sql);
            }
        }

        void mapOne(String id) throws SQLException {
            try (Connection c = scoped.openConnection(); PreparedStatement p = c.prepareStatement(
                    "INSERT INTO w345_ledger_identity_map(legacy_transaction_id) VALUES(?)")) {
                p.setString(1, id);
                assertEquals(1, p.executeUpdate());
            }
        }

        void backfill() throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.executeUpdate("INSERT INTO w345_ledger_identity_map(legacy_transaction_id) "
                        + "SELECT transaction_id FROM payment_transactions "
                        + "ORDER BY recorded_at,transaction_id "
                        + "ON CONFLICT(legacy_transaction_id) DO NOTHING");
            }
        }

        Map<String, Long> mapping() throws SQLException {
            Map<String, Long> rows = new LinkedHashMap<>();
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT legacy_transaction_id, ledger_entry_id "
                            + "FROM w345_ledger_identity_map ORDER BY legacy_transaction_id")) {
                while (r.next()) {
                    rows.put(r.getString(1), r.getLong(2));
                }
            }
            return rows;
        }

        Snapshot snapshot() throws Exception {
            try (Connection c = scoped.openConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                c.setReadOnly(true);
                c.setAutoCommit(false);
                Snapshot observed = snapshotWithin(c);
                c.rollback();
                return observed;
            }
        }

        Preflight preflight(Snapshot captured) throws Exception {
            try (Connection c = scoped.openConnection()) {
                c.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
                c.setReadOnly(true);
                c.setAutoCommit(false);
                Snapshot current = snapshotWithin(c);
                int missing = count(c, "SELECT count(*) FROM payment_transactions p "
                        + "LEFT JOIN w345_ledger_identity_map m "
                        + "ON m.legacy_transaction_id=p.transaction_id "
                        + "WHERE m.legacy_transaction_id IS NULL");
                int orphans = count(c, "SELECT count(*) FROM w345_ledger_identity_map m "
                        + "LEFT JOIN payment_transactions p "
                        + "ON p.transaction_id=m.legacy_transaction_id "
                        + "WHERE p.transaction_id IS NULL");
                int mapped = count(c, "SELECT count(*) FROM w345_ledger_identity_map");
                int version = count(c, "SELECT MAX(version) FROM schema_history");
                boolean ready = captured.count == current.count
                        && captured.paid == current.paid
                        && captured.balance == current.balance
                        && captured.digest.equals(current.digest)
                        && missing == 0 && orphans == 0
                        && mapped == current.count && version == 5;
                c.rollback();
                return new Preflight(ready, missing, orphans, mapped, version);
            }
        }

        Snapshot snapshotWithin(Connection c) throws Exception {
            StringBuilder source = new StringBuilder();
            int counter = 0;
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,contract_id,"
                            + "amount,cumulative_paid,resulting_balance,recorded_at "
                            + "FROM payment_transactions ORDER BY transaction_id")) {
                while (r.next()) {
                    counter++;
                    for (int i = 1; i <= 8; i++) {
                        source.append(r.getString(i)).append('\u0000');
                    }
                }
            }
            int paid, balance;
            try (Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT amount_paid,balance FROM payment_plans")) {
                assertTrue(r.next());
                paid = r.getInt(1);
                balance = r.getInt(2);
                assertFalse(r.next());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : bytes) hex.append(String.format("%02x", b & 0xff));
            return new Snapshot(counter, paid, balance, hex.toString());
        }

        int count(Connection connection, String query) throws SQLException {
            try (Statement s = connection.createStatement();
                    ResultSet r = s.executeQuery(query)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }

        @Override public void close() throws Exception {
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
