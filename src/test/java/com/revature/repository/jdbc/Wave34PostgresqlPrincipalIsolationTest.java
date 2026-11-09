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
 * W34.8 disposable PG16 role-isolation experiment.
 *
 * Writers connect with separate PostgreSQL LOGIN roles and cannot claim a
 * different SESSION_USER. An owner-controlled SECURITY DEFINER trigger checks
 * database session identity against an epoch registry. Test owner is a PG
 * superuser and can bypass controls: NOT independent external issuer trust,
 * HA leader fencing, production-grade policy, or permission to migrate.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlPrincipalIsolationTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String OWNER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String DDL =
            "docs/audit/wave34/wave34_8_DISPOSABLE_authenticated_principal_fence.sql";

    @Test
    @Timeout(90)
    void databaseAuthenticatedPrincipalNotCallerEpochControlsWrites()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            f.post(f.writerOne, "PAY-000001", 1);
            assertEquals(1, f.count());
            SQLException premature = assertThrows(SQLException.class,
                    () -> f.post(f.writerTwo, "PAY-000002", 2));
            assertEquals("55000", premature.getSQLState());
            assertEquals(1, f.count());

            f.freeze(1, 2);
            assertEquals("FROZEN", f.state().mode);
            SQLException old = assertThrows(SQLException.class,
                    () -> f.post(f.writerOne, "PAY-000002", 2));
            assertEquals("55000", old.getSQLState());
            SQLException frozen = assertThrows(SQLException.class,
                    () -> f.post(f.writerTwo, "PAY-000002", 2));
            assertEquals("55000", frozen.getSQLState());
            f.assertFinancialState(1, 23999);

            f.reopen(2);
            SQLException stale = assertThrows(SQLException.class,
                    () -> f.post(f.writerOne, "PAY-000002", 2));
            assertEquals("55000", stale.getSQLState());
            f.post(f.writerTwo, "PAY-000002", 2);
            f.assertFinancialState(2, 23998);
            assertEquals(5, f.schemaVersion());
            f.evidence("authenticated-epoch-transition");
        }
    }

    @Test
    @Timeout(90)
    void restrictedWriterCannotChangeEpochRegistryDisableTriggerOrImpersonateOperator()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            String fence = f.schema + ".w348_fence";
            String registry = f.schema + ".w348_principal_epoch";
            String ledger = f.schema + ".payment_transactions";
            try (Connection writer = f.connect(f.writerOne)) {
                assertEquals(f.writerOne.login, f.sessionUser(writer));
                assertSqlState("42501", () -> f.run(writer,
                        "UPDATE " + fence + " SET epoch=999 WHERE singleton_id=1"));
                assertSqlState("42501", () -> f.run(writer,
                        "UPDATE " + registry + " SET permitted_epoch=999"));
                assertSqlState("42501", () -> f.run(writer,
                        "ALTER TABLE " + ledger + " DISABLE TRIGGER w348_guard_ledger"));
                assertSqlState("42501", () -> f.run(writer,
                        "SET ROLE " + f.operator.login));
                assertSqlState("42501", () -> f.run(writer,
                        "SET SESSION AUTHORIZATION " + f.operator.login));
                // A client-controlled custom setting is not an authority:
                // the trigger relies on authenticated session_user instead.
                f.run(writer, "SET w347.writer_epoch = '999'");
                assertEquals(f.writerOne.login, f.sessionUser(writer));
            }
            try (Connection operator = f.connect(f.operator)) {
                assertSqlState("42501", () -> f.run(operator,
                        "INSERT INTO " + ledger
                        + "(transaction_id,customer_name,ownership_id,contract_id,"
                        + "amount,cumulative_paid,resulting_balance,recorded_at) VALUES "
                        + "('PAY-000001','buyer'," + f.ownership
                        + ",'ACQ-000001',1,1,23999,CURRENT_TIMESTAMP)"));
            }
            f.post(f.writerOne, "PAY-000001", 1);
            f.freeze(1, 2);
            try (Connection writer = f.connect(f.writerOne)) {
                f.run(writer, "SET w347.writer_epoch = '2'");
                assertSqlState("55000", () -> f.stagePayment(writer,
                        "PAY-000002", 2));
            }
            f.assertFinancialState(1, 23999);
            f.evidence("restricted-privilege-denials");
        }
    }

    @Test
    @Timeout(90)
    void independentOperatorFreezeWaitsForAuthenticatedWriterTransaction()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture();
                Connection writer = f.connect(f.writerOne)) {
            writer.setAutoCommit(false);
            f.stagePayment(writer, "PAY-000001", 1);
            try (Connection operator = f.connect(f.operator)) {
                operator.setAutoCommit(false);
                f.run(operator, "SET LOCAL lock_timeout = '750ms'");
                SQLException blocked = assertThrows(SQLException.class,
                        () -> f.freezeWithin(operator, 1, 2));
                assertEquals("55P03", blocked.getSQLState(),
                        "operator fence must wait until old writer drains");
                operator.rollback();
            }
            assertEquals("OPEN", f.state().mode);
            assertEquals(1L, f.state().epoch);
            writer.commit();
            f.freeze(1, 2);
            assertEquals("FROZEN", f.state().mode);
            assertSqlState("55000", () -> f.post(f.writerOne, "PAY-000002", 2));
            f.assertFinancialState(1, 23999);
            f.evidence("independent-operator-drain");
        }
    }

    private interface SqlCall { void execute() throws SQLException; }

    private static void assertSqlState(String expected, SqlCall call) {
        SQLException failure = assertThrows(SQLException.class, call::execute);
        assertEquals(expected, failure.getSQLState());
    }

    private static void requireDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && OWNER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD") != null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "Only the pinned exact-commit disposable PostgreSQL16 CI is allowed");
    }

    private static final class Role {
        final String login;
        final String password;
        Role(String name) {
            this.login = name;
            this.password = UUID.randomUUID().toString().replace("-", "");
        }
    }

    private static final class Fence {
        final long epoch;
        final String mode;
        Fence(long epoch, String mode) { this.epoch = epoch; this.mode = mode; }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema = "w348_" + UUID.randomUUID().toString().replace("-", "");
        final String shortId = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final Role writerOne = new Role("w348_" + shortId + "_w1");
        final Role writerTwo = new Role("w348_" + shortId + "_w2");
        final Role operator = new Role("w348_" + shortId + "_op");
        final JdbcDatabase admin = new JdbcDatabase(URL, OWNER, PASSWORD);
        final JdbcDatabase scoped = new JdbcDatabase(
                URL + "?currentSchema=" + schema, OWNER, PASSWORD);
        long ownership;

        Fixture() throws Exception {
            assertTrue(schema.matches("w348_[0-9a-f]{32}"));
            try (Connection c = admin.openConnection(); Statement sql = c.createStatement()) {
                try (ResultSet r = sql.executeQuery("SHOW server_version_num")) {
                    assertTrue(r.next());
                    int ver = Integer.parseInt(r.getString(1));
                    assertTrue(ver >= 160000 && ver < 170000, "real PostgreSQL16 required");
                }
                sql.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // Existing v5, only in randomized disposable schema
            try (Connection c = scoped.openConnection(); Statement sql = c.createStatement()) {
                sql.execute("INSERT INTO accounts(username,encoded_credential,role)"
                        + " VALUES('buyer','CI-only-credential','CUSTOMER')");
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
            try (Connection c = admin.openConnection(); Statement sql = c.createStatement()) {
                for (Role r : new Role[] {writerOne, writerTwo, operator}) {
                    assertTrue(r.login.matches("w348_[0-9a-f]{12}_(w1|w2|op)"));
                    sql.execute("CREATE ROLE " + r.login + " LOGIN PASSWORD '"
                            + r.password + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
                }
            }
            String ddl = Files.readString(Paths.get(
                    "docs/audit/wave34/wave34_8_DISPOSABLE_authenticated_principal_fence.sql"),
                    StandardCharsets.UTF_8).replace("__SCHEMA__", schema);
            String[] parts = ddl.split("(?m)^-- W348_STATEMENT_BREAK[ \\t]*\\r?$");
            assertEquals(7, parts.length);
            try (Connection c = scoped.openConnection(); Statement sql = c.createStatement()) {
                for (String part : parts) {
                    assertFalse(part.trim().isEmpty());
                    sql.execute(part);
                }
                sql.execute("INSERT INTO w348_principal_epoch(principal,permitted_epoch,enabled)"
                        + " VALUES ('" + writerOne.login + "',1,TRUE),"
                        + " ('" + writerTwo.login + "',2,TRUE)");
                for (Role r : new Role[] {writerOne, writerTwo, operator}) {
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + r.login);
                }
                for (Role r : new Role[] {writerOne, writerTwo}) {
                    sql.execute("GRANT INSERT ON payment_transactions TO " + r.login);
                    sql.execute("GRANT SELECT (ownership_id,amount_paid,balance),"
                            + " UPDATE (amount_paid,balance) ON payment_plans TO " + r.login);
                }
                sql.execute("GRANT SELECT, UPDATE ON w348_fence TO " + operator.login);
            }
            assertEquals(5, schemaVersion());
        }

        Connection connect(Role role) throws SQLException {
            return new JdbcDatabase(
                    URL + "?currentSchema=" + schema, role.login, role.password).openConnection();
        }

        String sessionUser(Connection c) throws SQLException {
            try (Statement sql = c.createStatement();
                    ResultSet r = sql.executeQuery("SELECT session_user")) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }

        void run(Connection c, String statement) throws SQLException {
            try (Statement sql = c.createStatement()) { sql.execute(statement); }
        }

        void stagePayment(Connection c, String id, int cumulative) throws SQLException {
            try (PreparedStatement plan = c.prepareStatement(
                    "UPDATE payment_plans SET amount_paid=amount_paid+1,"
                    + "balance=balance-1 WHERE ownership_id=?")) {
                plan.setLong(1, ownership);
                assertEquals(1, plan.executeUpdate());
            }
            try (PreparedStatement payment = c.prepareStatement(
                    "INSERT INTO payment_transactions "
                    + "(transaction_id,customer_name,ownership_id,contract_id,"
                    + "amount,cumulative_paid,resulting_balance,recorded_at)"
                    + " VALUES(?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                payment.setString(1, id);
                payment.setLong(2, ownership);
                payment.setInt(3, cumulative);
                payment.setInt(4, 24000 - cumulative);
                payment.setTimestamp(5, Timestamp.from(
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(cumulative)));
                assertEquals(1, payment.executeUpdate());
            }
        }

        void post(Role role, String id, int cumulative) throws SQLException {
            try (Connection c = connect(role)) {
                c.setAutoCommit(false);
                try {
                    stagePayment(c, id, cumulative);
                    c.commit();
                } catch (SQLException | RuntimeException e) {
                    c.rollback();
                    throw e;
                }
            }
        }

        int freezeWithin(Connection c, long oldEpoch, long newEpoch) throws SQLException {
            try (PreparedStatement p = c.prepareStatement(
                    "UPDATE w348_fence SET epoch=?, mode='FROZEN',frozen_at=clock_timestamp() "
                    + "WHERE singleton_id=1 AND epoch=? AND mode='OPEN'")) {
                p.setLong(1, newEpoch);
                p.setLong(2, oldEpoch);
                return p.executeUpdate();
            }
        }

        void freeze(long oldEpoch, long newEpoch) throws SQLException {
            try (Connection c = connect(operator)) {
                c.setAutoCommit(false);
                assertEquals(1, freezeWithin(c, oldEpoch, newEpoch));
                c.commit();
            }
        }
        void reopen(long epoch) throws SQLException {
            try (Connection c = connect(operator);
                    PreparedStatement p = c.prepareStatement(
                            "UPDATE w348_fence SET mode='OPEN',frozen_at=NULL "
                            + "WHERE singleton_id=1 AND epoch=? AND mode='FROZEN'")) {
                p.setLong(1, epoch);
                assertEquals(1, p.executeUpdate());
            }
        }

        Fence state() throws SQLException {
            try (Connection c = scoped.openConnection(); Statement sql = c.createStatement();
                    ResultSet r = sql.executeQuery(
                            "SELECT epoch,mode FROM w348_fence WHERE singleton_id=1")) {
                assertTrue(r.next());
                Fence result = new Fence(r.getLong(1), r.getString(2));
                assertFalse(r.next());
                return result;
            }
        }

        int schemaVersion() throws SQLException {
            return scalar("SELECT max(version) FROM schema_history");
        }
        int count() throws SQLException {
            return scalar("SELECT count(*) FROM payment_transactions");
        }
        int paid() throws SQLException {
            return scalar("SELECT amount_paid FROM payment_plans");
        }
        int balance() throws SQLException {
            return scalar("SELECT balance FROM payment_plans");
        }
        int scalar(String expression) throws SQLException {
            try (Connection c = scoped.openConnection(); Statement sql = c.createStatement();
                    ResultSet r = sql.executeQuery(expression)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }
        void assertFinancialState(int expectedPaid, int expectedBalance) throws SQLException {
            assertEquals(expectedPaid, count());
            assertEquals(expectedPaid, paid());
            assertEquals(expectedBalance, balance());
        }

        String ledgerDigest() throws Exception {
            StringBuilder source = new StringBuilder();
            try (Connection c = scoped.openConnection(); Statement sql = c.createStatement();
                    ResultSet r = sql.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,contract_id,"
                            + "amount,cumulative_paid,resulting_balance,recorded_at"
                            + " FROM payment_transactions ORDER BY transaction_id")) {
                while (r.next()) {
                    for (int i = 1; i <= 8; i++) {
                        source.append(r.getString(i)).append('\u0000');
                    }
                }
            }
            source.append("plan=").append(paid()).append(':').append(balance());
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(
                    source.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(64);
            for (byte b : bytes) hex.append(String.format("%02x", b & 255));
            return hex.toString();
        }

        void evidence(String scenario) throws Exception {
            Fence fence = state();
            Path dir = Paths.get("target/wave34-8-evidence");
            Files.createDirectories(dir);
            String json = "{\n"
                    + "\"scenario\":\"" + scenario + "\",\n"
                    + "\"head_sha\":\"" + System.getenv("EXPECTED_HEAD") + "\",\n"
                    + "\"fixture\":\"DISPOSABLE_PG16_ROLES_ONLY\",\n"
                    + "\"authorization_basis\":\"POSTGRESQL_SESSION_USER\",\n"
                    + "\"release\":\"NO-GO\",\n"
                    + "\"epoch\":" + fence.epoch + ",\n"
                    + "\"mode\":\"" + fence.mode + "\",\n"
                    + "\"schema_version\":" + schemaVersion() + ",\n"
                    + "\"synthetic_postings\":" + count() + ",\n"
                    + "\"synthetic_balance\":" + balance() + ",\n"
                    + "\"ledger_sha256\":\"" + ledgerDigest() + "\",\n"
                    + "\"external_epoch_issuer_verified\":false,\n"
                    + "\"production_fencing_verified\":false,\n"
                    + "\"production_authorized\":false\n"
                    + "}\n";
            Files.writeString(dir.resolve(scenario + ".json"), json, StandardCharsets.UTF_8);
        }

        @Override public void close() throws Exception {
            try (Connection c = admin.openConnection(); Statement sql = c.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                for (Role r : new Role[] {writerOne, writerTwo, operator}) {
                    sql.execute("DROP ROLE IF EXISTS " + r.login);
                }
            }
        }
    }
}
