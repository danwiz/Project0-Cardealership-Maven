package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import java.time.Duration;
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
 * W34.12 disposable restricted-principal and uncertain LOCAL acknowledgement tests.
 * Issuer role is a DB-local fixture that can mint arbitrary test intents: it is
 * NOT an independently authenticated merchant, cryptographic verifier or KMS.
 * SECURITY DEFINER is owned by a privileged CI role; production non-bypassability
 * is NOT established. No external charge is ever submitted or recovered.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlRestrictedRecoveryTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String ADMIN = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String DDL =
            "docs/audit/wave34/wave34_12_DISPOSABLE_restricted_intents.sql";

    @Test
    @Timeout(90)
    void executorCanOnlyUseControlledFunctionAndIssuerCannotPostDirectly()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            UUID authorization = f.issue("REQ-001", "PAY-000001", 1, 60);
            try (Connection executor = f.connect(f.executor)) {
                assertEquals(f.executor.login, f.currentSessionUser(executor));
                assertSqlState("42501", () -> f.sql(executor,
                        "UPDATE " + f.schema + ".payment_plans "
                        + "SET balance=balance-1 WHERE ownership_id=" + f.ownership));
                assertSqlState("42501", () -> f.sql(executor,
                        "INSERT INTO " + f.schema + ".w3412_authorized_intents "
                        + "(auth_id,tenant,request_id,subject,ownership_id,amount,"
                        + "transaction_id,expires_at) VALUES("
                        + "'" + UUID.randomUUID() + "','t','forged','buyer',"
                        + f.ownership + ",1,'PAY-FAKE',clock_timestamp()+interval '1 minute')"));
                assertSqlState("42501", () -> f.sql(executor,
                        "ALTER TABLE " + f.schema
                        + ".payment_transactions DISABLE TRIGGER ALL"));
                assertSqlState("42501", () -> f.sql(executor,
                        "SET ROLE " + f.issuer.login));
                assertSqlState("42501", () -> f.sql(executor,
                        "SET SESSION AUTHORIZATION " + f.issuer.login));
            }
            try (Connection issuer = f.connect(f.issuer)) {
                assertSqlState("42501", () -> f.sql(issuer,
                        "UPDATE " + f.schema + ".payment_plans"
                        + " SET balance=balance-1 WHERE ownership_id=" + f.ownership));
                assertSqlState("42501", () -> f.sql(issuer,
                        "SELECT " + f.schema + ".w3412_apply_intent('"
                        + authorization + "'::uuid)"));
            }
            assertEquals("PAY-000001", f.apply(authorization));
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            assertEquals(5, f.schemaVersion());
            f.evidence("privilege-and-function-gate");
        }
    }

    @Test
    @Timeout(90)
    void lostLocalAcknowledgementRetryReturnsSameResultWithoutDuplicatePosting()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            UUID original = f.issue("REQ-RECOVER", "PAY-000001", 1, 60);
            // Simulate a local caller losing the response AFTER the SQL COMMIT.
            f.apply(original);
            assertEquals(1, f.postings());
            assertEquals("PAY-000001", f.apply(original),
                    "recovery through fresh JDBC connection returns original reference");
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            assertEquals(1, f.consumed());
            assertSqlState("23505", () ->
                    f.issue("REQ-RECOVER", "PAY-ALIAS", 1, 60));
            assertEquals(1, f.issued());
            assertEquals("PAY-000001", f.apply(original));

            UUID distinct = f.issue("REQ-DISTINCT", "PAY-000002", 1, 60);
            assertEquals("PAY-000002", f.apply(distinct));
            assertEquals(2, f.postings());
            assertEquals(2, f.paid());
            assertEquals(23998, f.balance());
            f.evidence("lost-local-ack-idempotent");
        }
    }

    @Test
    @Timeout(90)
    void simultaneousRetryOfSameIntentIsSerializedAndOnlyPostedOnce()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            UUID authorization = f.issue("REQ-RACE", "PAY-000001", 1, 60);
            ExecutorService threads = Executors.newFixedThreadPool(4);
            try {
                CountDownLatch prepared = new CountDownLatch(4);
                CountDownLatch start = new CountDownLatch(1);
                java.util.List<Future<String>> attempts = new java.util.ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    attempts.add(threads.submit(() -> {
                        prepared.countDown();
                        if (!start.await(10, TimeUnit.SECONDS))
                            throw new AssertionError("test worker setup timed out");
                        return f.apply(authorization);
                    }));
                }
                assertTrue(prepared.await(10, TimeUnit.SECONDS));
                start.countDown();
                for (Future<String> attempt : attempts) {
                    assertEquals("PAY-000001", attempt.get(30, TimeUnit.SECONDS));
                }
            } finally {
                threads.shutdownNow();
            }
            assertEquals(1, f.postings());
            assertEquals(1, f.issued());
            assertEquals(1, f.consumed());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            f.evidence("concurrent-ack-replay");
        }
    }

    @Test
    @Timeout(90)
    void duplicateLedgerIdRollsBackPlanAndIntentAndRevokedExpiredRequestsFail()
            throws Exception {
        requiredDisposable();
        try (Fixture f = new Fixture()) {
            UUID first = f.issue("REQ-A", "PAY-000001", 1, 60);
            f.apply(first);
            UUID colliding = f.issue("REQ-B", "PAY-000001", 1, 60);
            String beforeFailedApply = f.digest();
            assertSqlState("23505", () -> f.apply(colliding));
            assertEquals(1, f.postings());
            assertEquals(1, f.consumed(),
                    "failed insert must not mark new request consumed");
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            assertEquals(beforeFailedApply, f.digest(),
                    "failed ledger insert cannot mutate plan, consumption or source ledger");
            f.revoke(colliding);
            assertSqlState("55000", () -> f.apply(colliding));
            UUID expired = f.issue("REQ-EXPIRED", "PAY-000002", 1, -5);
            assertSqlState("55000", () -> f.apply(expired));
            assertSqlState("55000", () -> f.apply(UUID.randomUUID()));
            assertEquals(1, f.postings());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            f.evidence("failed-post-and-expiry");
        }
    }

    private interface SqlTask { void run() throws Exception; }
    private static void assertSqlState(String state, SqlTask task) {
        SQLException error = assertThrows(SQLException.class, task::run);
        assertEquals(state, error.getSQLState());
    }

    private static void requiredDisposable() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && ADMIN.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD"))
                && System.getenv("EXPECTED_HEAD") != null
                && System.getenv("EXPECTED_HEAD").matches("[0-9a-f]{40}"),
                "only the exact-commit disposable PostgreSQL16 environment may run this fixture");
    }

    private static final class Login {
        final String login;
        final String password = UUID.randomUUID().toString().replace("-", "");
        Login(String login) { this.login = login; }
    }

    private static final class Fixture implements AutoCloseable {
        final String schema = "w3412_" + UUID.randomUUID().toString().replace("-", "");
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final Login executor = new Login("w3412_" + suffix + "_ex");
        final Login issuer = new Login("w3412_" + suffix + "_is");
        final JdbcDatabase owner = new JdbcDatabase(URL, ADMIN, PASSWORD);
        final JdbcDatabase scoped = new JdbcDatabase(
                URL + "?currentSchema=" + schema, ADMIN, PASSWORD);
        long ownership;

        Fixture() throws Exception {
            assertTrue(schema.matches("w3412_[0-9a-f]{32}"));
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                try (ResultSet version = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int number = Integer.parseInt(version.getString(1));
                    assertTrue(number >= 160000 && number < 170000,
                            "actual PostgreSQL16 is required");
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // EXISTING schema version 5; new disposable schema only.
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.execute("INSERT INTO accounts(username,encoded_credential,role)"
                        + " VALUES('buyer','CI-only','CUSTOMER')");
            }
            try (Connection c = scoped.openConnection();
                    PreparedStatement p = c.prepareStatement(
                            "INSERT INTO owned_vehicles"
                            + "(owner_username,make,model,vehicle_year,contract_id)"
                            + " VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                            Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1, p.executeUpdate());
                try (ResultSet rs = p.getGeneratedKeys()) {
                    assertTrue(rs.next());
                    ownership = rs.getLong(1);
                }
            }
            try (Connection c = scoped.openConnection();
                    PreparedStatement p = c.prepareStatement(
                            "INSERT INTO payment_plans"
                            + "(ownership_id,purchase_price,payment_months,"
                            + "monthly_payment,amount_paid,balance)"
                            + " VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1, ownership);
                assertEquals(1, p.executeUpdate());
            }
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                for (Login role : new Login[] {executor, issuer}) {
                    assertTrue(role.login.matches("w3412_[0-9a-f]{12}_(ex|is)"));
                    s.execute("CREATE ROLE " + role.login + " LOGIN PASSWORD '"
                            + role.password
                            + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
                }
            }
            String fixture = Files.readString(Paths.get(DDL), StandardCharsets.UTF_8);
            String[] blocks = fixture.replace("__SCHEMA__", schema)
                    .replace("__EXECUTOR__", executor.login)
                    .split("(?m)^-- W3412_STATEMENT_BREAK[ \\t]*\\r?$");
            assertEquals(4, blocks.length);
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                for (String block : blocks) {
                    assertFalse(block.trim().isEmpty());
                    s.execute(block);
                }
                s.execute("REVOKE ALL ON SCHEMA " + schema + " FROM PUBLIC");
                for (Login role : new Login[] {executor, issuer})
                    s.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role.login);
                s.execute("GRANT EXECUTE ON FUNCTION " + schema
                        + ".w3412_apply_intent(UUID) TO " + executor.login);
                s.execute("GRANT INSERT(auth_id,tenant,request_id,subject,"
                        + "ownership_id,amount,transaction_id,expires_at) "
                        + "ON w3412_authorized_intents TO " + issuer.login);
                s.execute("GRANT SELECT(auth_id),UPDATE(revoked_at) "
                        + "ON w3412_authorized_intents TO " + issuer.login);
            }
            assertEquals(5, schemaVersion());
        }

        Connection connect(Login role) throws SQLException {
            return new JdbcDatabase(URL + "?currentSchema=" + schema,
                    role.login, role.password).openConnection();
        }
        void sql(Connection c, String command) throws SQLException {
            try (Statement statement = c.createStatement()) {
                statement.execute(command);
            }
        }
        String currentSessionUser(Connection c) throws SQLException {
            try (Statement s = c.createStatement();
                    ResultSet result = s.executeQuery("SELECT session_user")) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }

        UUID issue(String requestId, String transactionId, int amount, int ttlSeconds)
                throws SQLException {
            UUID id = UUID.randomUUID();
            try (Connection issuerConnection = connect(issuer);
                    PreparedStatement insert = issuerConnection.prepareStatement(
                            "INSERT INTO w3412_authorized_intents"
                            + "(auth_id,tenant,request_id,subject,ownership_id,amount,"
                            + "transaction_id,expires_at) "
                            + "VALUES(?,'synthetic-tenant',?,'buyer',?,?,?,"
                            + "clock_timestamp()+(? * interval '1 second'))")) {
                insert.setObject(1, id);
                insert.setString(2, requestId);
                insert.setLong(3, ownership);
                insert.setInt(4, amount);
                insert.setString(5, transactionId);
                insert.setInt(6, ttlSeconds);
                assertEquals(1, insert.executeUpdate());
            }
            return id;
        }
        void revoke(UUID id) throws SQLException {
            try (Connection issuerConnection = connect(issuer);
                    PreparedStatement update = issuerConnection.prepareStatement(
                            "UPDATE w3412_authorized_intents SET revoked_at=clock_timestamp()"
                            + " WHERE auth_id=?")) {
                update.setObject(1, id);
                assertEquals(1, update.executeUpdate());
            }
        }
        String apply(UUID id) throws SQLException {
            // Every invocation uses a new independently authenticated executor
            // DB connection to characterize ACK loss and reconnect.
            try (Connection executorConnection = connect(executor);
                    PreparedStatement call = executorConnection.prepareStatement(
                            "SELECT w3412_apply_intent(?)")) {
                call.setObject(1, id);
                try (ResultSet result = call.executeQuery()) {
                    assertTrue(result.next());
                    String value = result.getString(1);
                    assertFalse(result.next());
                    return value;
                }
            }
        }
        int postings() throws SQLException {
            return scalar("SELECT COUNT(*) FROM payment_transactions");
        }
        int issued() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w3412_authorized_intents");
        }
        int consumed() throws SQLException {
            return scalar("SELECT COUNT(*) FROM w3412_authorized_intents "
                    + "WHERE consumed_at IS NOT NULL");
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
        int scalar(String query) throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet result = s.executeQuery(query)) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }
        String digest() throws Exception {
            StringBuilder rows = new StringBuilder();
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement();
                    ResultSet r = s.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,"
                            + "contract_id,amount,cumulative_paid,resulting_balance "
                            + "FROM payment_transactions ORDER BY transaction_id")) {
                while (r.next()) {
                    for (int i = 1; i <= 7; i++)
                        rows.append(r.getString(i)).append('\u0000');
                }
            }
            rows.append("intent-count:").append(issued())
                .append(";consumed:").append(consumed()).append(";paid:")
                .append(paid()).append(";balance:").append(balance());
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(rows.toString().getBytes(StandardCharsets.UTF_8));
            char[] abc = "0123456789abcdef".toCharArray();
            StringBuilder out = new StringBuilder();
            for (byte b : hash) out.append(abc[(b >>> 4) & 15]).append(abc[b & 15]);
            return out.toString();
        }
        void evidence(String scenario) throws Exception {
            Path dir = Paths.get("target/wave34-12-evidence");
            Files.createDirectories(dir);
            String json = "{\n"
                    + "\"scenario\":\"" + scenario + "\",\n"
                    + "\"source_head\":\"" + System.getenv("EXPECTED_HEAD") + "\",\n"
                    + "\"fixture\":\"DISPOSABLE_PG16_RESTRICTED_SQL_ONLY\",\n"
                    + "\"schema_version\":" + schemaVersion() + ",\n"
                    + "\"synthetic_intents\":" + issued() + ",\n"
                    + "\"synthetic_consumed\":" + consumed() + ",\n"
                    + "\"synthetic_postings\":" + postings() + ",\n"
                    + "\"synthetic_balance\":" + balance() + ",\n"
                    + "\"source_digest_sha256\":\"" + digest() + "\",\n"
                    + "\"independent_issuer_verified\":false,\n"
                    + "\"non_superuser_function_owner_verified\":false,\n"
                    + "\"real_provider_ack_recovered\":false,\n"
                    + "\"production_authorized\":false,\n"
                    + "\"release_disposition\":\"NO-GO\"\n"
                    + "}\n";
            Files.write(dir.resolve(scenario + ".json"),
                    json.getBytes(StandardCharsets.UTF_8));
        }
        @Override public void close() throws Exception {
            try (Connection c = owner.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                for (Login role : new Login[] {executor, issuer})
                    s.execute("DROP ROLE IF EXISTS " + role.login);
            }
        }
    }
}
