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
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/**
 * Wave34.9 disposable PG16 lease-lifecycle characterization.
 *
 * A randomly generated bearer UUID must be possessed by the authenticated
 * PostgreSQL LOGIN role to write. Token issuance/revocation is performed by
 * an independent *test role* within this database, not an external issuer.
 * Client-controlled bearer tokens can be stolen; the CI superuser can bypass
 * all guards. NO provider money, production auth or release approval.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
class Wave34PostgresqlLeaseLifecycleTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";
    private static final String DDL = "docs/audit/wave34/wave34_9_DISPOSABLE_lease_fence.sql";

    @Test
    @Timeout(90)
    void issuerCanGrantAndRevokeLeaseButWriterCannotReuseRevokedToken()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            assertSqlState("55000",
                    () -> f.post(f.writerOne, null, "PAY-000001", 1));
            UUID lease = f.issue(f.writerOne, 1, 30);
            f.post(f.writerOne, lease, "PAY-000001", 1);
            assertSqlState("55000",
                    () -> f.post(f.writerOne, UUID.randomUUID(), "PAY-000002", 2));
            f.revoke(lease);
            assertSqlState("55000",
                    () -> f.post(f.writerOne, lease, "PAY-000002", 2));
            assertEquals(1, f.count());
            assertEquals(1, f.paid());
            assertEquals(23999, f.balance());
            assertEquals(5, f.schemaVersion());
            f.evidence("lease-issuance-revocation");
        }
    }

    @Test
    @Timeout(90)
    void expiredOrOtherPrincipalLeaseFailsAndRestrictedRolesCannotEscalate()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            UUID shortLease = f.issue(f.writerOne, 1, 1);
            Thread.sleep(1400L); // real PostgreSQL clock-based expiry, no mocked timestamps
            assertSqlState("55000",
                    () -> f.post(f.writerOne, shortLease, "PAY-000001", 1));
            UUID fresh = f.issue(f.writerOne, 1, 30);
            assertSqlState("55000",
                    () -> f.post(f.writerTwo, fresh, "PAY-000001", 1));
            try (Connection writer = f.connect(f.writerOne)) {
                assertEquals(f.writerOne.login, f.sessionUser(writer));
                assertSqlState("42501", () -> f.run(writer,
                        "UPDATE " + f.schema + ".w349_epoch_fence "
                        + "SET epoch=999 WHERE singleton_id=1"));
                assertSqlState("42501", () -> f.run(writer,
                        "SELECT lease_token FROM " + f.schema + ".w349_issued_lease"));
                assertSqlState("42501", () -> f.run(writer,
                        "UPDATE " + f.schema + ".w349_issued_lease "
                        + "SET revoked_at=clock_timestamp()"));
                assertSqlState("42501", () -> f.run(writer,
                        "ALTER TABLE " + f.schema
                        + ".payment_transactions DISABLE TRIGGER w349_guard_ledger"));
                assertSqlState("42501", () -> f.run(writer,
                        "SET ROLE " + f.operator.login));
                assertSqlState("42501", () -> f.run(writer,
                        "SET SESSION AUTHORIZATION " + f.issuer.login));
            }
            try (Connection operator = f.connect(f.operator)) {
                assertSqlState("42501", () -> f.run(operator,
                        "INSERT INTO " + f.schema + ".payment_transactions "
                        + "(transaction_id,customer_name,ownership_id,contract_id,"
                        + "amount,cumulative_paid,resulting_balance,recorded_at)"
                        + " VALUES('PAY-000001','buyer'," + f.ownership
                        + ",'ACQ-000001',1,1,23999,CURRENT_TIMESTAMP)"));
                assertSqlState("42501", () -> f.run(operator,
                        "UPDATE " + f.schema + ".w349_issued_lease "
                        + "SET revoked_at=clock_timestamp()"));
            }
            try (Connection issuer = f.connect(f.issuer)) {
                assertSqlState("42501", () -> f.run(issuer,
                        "UPDATE " + f.schema + ".w349_epoch_fence "
                        + "SET epoch=999 WHERE singleton_id=1"));
            }
            f.post(f.writerOne, fresh, "PAY-000001", 1);
            assertEquals(1, f.count());
            assertEquals(23999, f.balance());
            f.evidence("expiry-and-role-denials");
        }
    }

    @Test
    @Timeout(90)
    void freezeDrainsInFlightLeaseAndRequiresNewEpochTokenAfterReopen()
            throws Exception {
        requireDisposable();
        try (Fixture f = new Fixture()) {
            UUID oldLease = f.issue(f.writerOne, 1, 30);
            try (Connection writer = f.connect(f.writerOne)) {
                writer.setAutoCommit(false);
                f.stagePayment(writer, oldLease, "PAY-000001", 1);
                try (Connection operator = f.connect(f.operator)) {
                    operator.setAutoCommit(false);
                    f.run(operator, "SET LOCAL lock_timeout = '750ms'");
                    SQLException timeout = assertThrows(SQLException.class,
                            () -> f.freezeWithin(operator, 1, 2));
                    assertEquals("55P03", timeout.getSQLState(),
                            "the active writer must hold the fence-row SHARE lock");
                    operator.rollback();
                }
                writer.commit();
            }
            assertEquals(1, f.count());
            f.freeze(1, 2);
            assertEquals("FROZEN", f.state().mode);
            assertSqlState("55000",
                    () -> f.post(f.writerOne, oldLease, "PAY-000002", 2));

            UUID newLease = f.issue(f.writerTwo, 2, 30);
            assertSqlState("55000",
                    () -> f.post(f.writerTwo, newLease, "PAY-000002", 2));
            f.reopen(2);
            assertSqlState("55000",
                    () -> f.post(f.writerOne, oldLease, "PAY-000002", 2));
            f.post(f.writerTwo, newLease, "PAY-000002", 2);
            assertEquals(2, f.count());
            assertEquals(2, f.paid());
            assertEquals(23998, f.balance());
            f.evidence("epoch-cutover-stale-token");
        }
    }

    private interface SqlCall { void call() throws SQLException; }

    private static void assertSqlState(String expected, SqlCall call) {
        SQLException exception = assertThrows(SQLException.class, call::call);
        assertEquals(expected, exception.getSQLState());
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
                "only pinned disposable PostgreSQL16 Actions may run this lease test");
    }

    private static final class Role {
        final String login;
        final String password = UUID.randomUUID().toString().replace("-", "");
        Role(String login) { this.login = login; }
    }

    private static final class Fence {
        final long epoch;
        final String mode;
        Fence(long epoch, String mode) {this.epoch=epoch;this.mode=mode;}
    }

    private static final class Fixture implements AutoCloseable {
        final String schema = "w349_" + UUID.randomUUID().toString().replace("-", "");
        final String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final Role writerOne = new Role("w349_" + suffix + "_w1");
        final Role writerTwo = new Role("w349_" + suffix + "_w2");
        final Role issuer = new Role("w349_" + suffix + "_is");
        final Role operator = new Role("w349_" + suffix + "_op");
        final JdbcDatabase admin = new JdbcDatabase(URL, USER, PASSWORD);
        final JdbcDatabase scoped =
                new JdbcDatabase(URL + "?currentSchema=" + schema, USER, PASSWORD);
        long ownership;

        Fixture() throws Exception {
            assertTrue(schema.matches("w349_[0-9a-f]{32}"));
            try (Connection conn = admin.openConnection(); Statement s = conn.createStatement()) {
                try (ResultSet version = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int number = Integer.parseInt(version.getString(1));
                    assertTrue(number >= 160000 && number < 170000);
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // Existing v5 only in disposable schema.
            try (Connection conn = scoped.openConnection(); Statement s = conn.createStatement()) {
                s.execute("INSERT INTO accounts(username,encoded_credential,role) "
                        + "VALUES('buyer','CI-only','CUSTOMER')");
            }
            try (Connection conn = scoped.openConnection(); PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO owned_vehicles "
                    + "(owner_username,make,model,vehicle_year,contract_id) "
                    + "VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1, p.executeUpdate());
                try (ResultSet result = p.getGeneratedKeys()) {
                    assertTrue(result.next());
                    ownership = result.getLong(1);
                }
            }
            try (Connection conn = scoped.openConnection(); PreparedStatement p = conn.prepareStatement(
                    "INSERT INTO payment_plans "
                    + "(ownership_id,purchase_price,payment_months,monthly_payment,"
                    + "amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")) {
                p.setLong(1, ownership);
                assertEquals(1, p.executeUpdate());
            }
            try (Connection conn = admin.openConnection(); Statement s = conn.createStatement()) {
                for (Role role : new Role[] {writerOne,writerTwo,issuer,operator}) {
                    assertTrue(role.login.matches("w349_[0-9a-f]{12}_(w1|w2|is|op)"));
                    s.execute("CREATE ROLE " + role.login + " LOGIN PASSWORD '"
                            + role.password + "' NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT");
                }
            }
            String ddl=Files.readString(Paths.get(DDL),StandardCharsets.UTF_8)
                    .replace("__SCHEMA__",schema);
            String[] statements=ddl.split("(?m)^-- W349_STATEMENT_BREAK[ \\t]*\\r?$");
            assertEquals(7,statements.length);
            try (Connection conn = scoped.openConnection(); Statement s = conn.createStatement()) {
                for (String sql : statements) {
                    assertFalse(sql.trim().isEmpty());
                    s.execute(sql);
                }
                for (Role role : new Role[] {writerOne,writerTwo,issuer,operator}) {
                    s.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role.login);
                }
                for (Role role : new Role[] {writerOne,writerTwo}) {
                    s.execute("GRANT INSERT ON payment_transactions TO " + role.login);
                    s.execute("GRANT SELECT (ownership_id,amount_paid,balance), "
                            + "UPDATE (amount_paid,balance) ON payment_plans TO " + role.login);
                }
                s.execute("GRANT SELECT,UPDATE ON w349_epoch_fence TO " + operator.login);
                s.execute("GRANT INSERT ON w349_issued_lease TO " + issuer.login);
                s.execute("GRANT SELECT (lease_token), UPDATE (revoked_at) "
                        + "ON w349_issued_lease TO " + issuer.login);
            }
            assertEquals(5,schemaVersion());
        }

        Connection connect(Role r) throws SQLException {
            return new JdbcDatabase(URL+"?currentSchema="+schema,r.login,r.password)
                    .openConnection();
        }

        void run(Connection c,String command) throws SQLException {
            try (Statement s = c.createStatement()) {s.execute(command);}
        }
        String sessionUser(Connection c) throws SQLException {
            try (Statement s=c.createStatement();
                    ResultSet r=s.executeQuery("SELECT session_user")) {
                assertTrue(r.next());
                return r.getString(1);
            }
        }

        UUID issue(Role recipient,long epoch,int durationSeconds) throws SQLException {
            UUID value=UUID.randomUUID();
            try (Connection conn=connect(issuer);
                    PreparedStatement p=conn.prepareStatement(
                            "INSERT INTO w349_issued_lease(lease_token,principal,epoch,expires_at)"
                            + " VALUES(?,?,?,clock_timestamp() + (? * interval '1 second'))")) {
                p.setObject(1,value);
                p.setString(2,recipient.login);
                p.setLong(3,epoch);
                p.setInt(4,durationSeconds);
                assertEquals(1,p.executeUpdate());
            }
            return value;
        }

        void revoke(UUID token) throws SQLException {
            try (Connection conn=connect(issuer);
                    PreparedStatement p=conn.prepareStatement(
                            "UPDATE w349_issued_lease SET revoked_at=clock_timestamp()"
                            + " WHERE lease_token=?")) {
                p.setObject(1,token);
                assertEquals(1,p.executeUpdate());
            }
        }

        void stagePayment(Connection c,UUID token,String id,int cumulative)
                throws SQLException {
            if (token!=null) {
                try (PreparedStatement p=c.prepareStatement(
                        "SELECT set_config('w349.lease_token',?,true)")) {
                    p.setString(1,token.toString());
                    try (ResultSet r=p.executeQuery()) {
                        assertTrue(r.next());
                        assertEquals(token.toString(),r.getString(1));
                    }
                }
            }
            try (PreparedStatement plan=c.prepareStatement(
                    "UPDATE payment_plans SET amount_paid=amount_paid+1,"
                    + "balance=balance-1 WHERE ownership_id=?")) {
                plan.setLong(1,ownership);
                assertEquals(1,plan.executeUpdate());
            }
            try (PreparedStatement p=c.prepareStatement(
                    "INSERT INTO payment_transactions "
                    + "(transaction_id,customer_name,ownership_id,contract_id,"
                    + "amount,cumulative_paid,resulting_balance,recorded_at)"
                    + " VALUES(?,'buyer',?,'ACQ-000001',1,?,?,?)")) {
                p.setString(1,id);
                p.setLong(2,ownership);
                p.setInt(3,cumulative);
                p.setInt(4,24000-cumulative);
                p.setTimestamp(5,Timestamp.from(
                        Instant.parse("2026-01-01T00:00:00Z").plusSeconds(cumulative)));
                assertEquals(1,p.executeUpdate());
            }
        }

        void post(Role writer,UUID token,String id,int cumulative) throws SQLException {
            try (Connection conn=connect(writer)) {
                conn.setAutoCommit(false);
                try {
                    stagePayment(conn,token,id,cumulative);
                    conn.commit();
                } catch (SQLException | RuntimeException exception) {
                    conn.rollback();
                    throw exception;
                }
            }
        }

        int freezeWithin(Connection c,long oldEpoch,long next) throws SQLException {
            try (PreparedStatement p=c.prepareStatement(
                    "UPDATE w349_epoch_fence SET epoch=?,mode='FROZEN',"
                    + " frozen_at=clock_timestamp() WHERE singleton_id=1"
                    + " AND epoch=? AND mode='OPEN'")) {
                p.setLong(1,next);
                p.setLong(2,oldEpoch);
                return p.executeUpdate();
            }
        }

        void freeze(long oldEpoch,long next) throws SQLException {
            try (Connection c=connect(operator)) {
                c.setAutoCommit(false);
                assertEquals(1,freezeWithin(c,oldEpoch,next));
                c.commit();
            }
        }
        void reopen(long epoch) throws SQLException {
            try (Connection c=connect(operator);
                    PreparedStatement p=c.prepareStatement(
                            "UPDATE w349_epoch_fence SET mode='OPEN',"
                            + " frozen_at=NULL WHERE singleton_id=1 AND epoch=?"
                            + " AND mode='FROZEN'")) {
                p.setLong(1,epoch);
                assertEquals(1,p.executeUpdate());
            }
        }

        Fence state() throws SQLException {
            try (Connection c=scoped.openConnection(); Statement s=c.createStatement();
                    ResultSet r=s.executeQuery(
                            "SELECT epoch,mode FROM w349_epoch_fence WHERE singleton_id=1")) {
                assertTrue(r.next());
                Fence f=new Fence(r.getLong(1),r.getString(2));
                assertFalse(r.next());
                return f;
            }
        }

        int count() throws SQLException {return scalar("SELECT count(*) FROM payment_transactions");}
        int paid() throws SQLException {return scalar("SELECT amount_paid FROM payment_plans");}
        int balance() throws SQLException {return scalar("SELECT balance FROM payment_plans");}
        int schemaVersion() throws SQLException {return scalar("SELECT max(version) FROM schema_history");}
        int scalar(String expression) throws SQLException {
            try (Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery(expression)) {
                assertTrue(r.next());
                return r.getInt(1);
            }
        }

        String digest() throws Exception {
            StringBuilder src=new StringBuilder();
            try (Connection c=scoped.openConnection();Statement s=c.createStatement();
                    ResultSet r=s.executeQuery(
                            "SELECT transaction_id,customer_name,ownership_id,contract_id,"
                            + "amount,cumulative_paid,resulting_balance,recorded_at "
                            + "FROM payment_transactions ORDER BY transaction_id")) {
                while (r.next()) {
                    for(int i=1;i<=8;i++) src.append(r.getString(i)).append('\u0000');
                }
            }
            src.append("plan=").append(paid()).append(':').append(balance());
            byte[] hash=MessageDigest.getInstance("SHA-256").digest(
                    src.toString().getBytes(StandardCharsets.UTF_8));
            char[] chars="0123456789abcdef".toCharArray();
            StringBuilder hex=new StringBuilder(64);
            for(byte b:hash) {hex.append(chars[(b>>>4)&15]).append(chars[b&15]);}
            return hex.toString();
        }

        void evidence(String scenario) throws Exception {
            Fence fence=state();
            Path directory=Paths.get("target/wave34-9-evidence");
            Files.createDirectories(directory);
            String json="{\n"
                    + "\"scenario\":\""+scenario+"\",\n"
                    + "\"head_sha\":\""+System.getenv("EXPECTED_HEAD")+"\",\n"
                    + "\"fixture\":\"DISPOSABLE_PG16_LEASE_ROLES_ONLY\",\n"
                    + "\"authorization_basis\":\"SESSION_USER_PLUS_UNGUESSABLE_TEST_BEARER\",\n"
                    + "\"release\":\"NO-GO\",\n"
                    + "\"epoch\":"+fence.epoch+",\n"
                    + "\"mode\":\""+fence.mode+"\",\n"
                    + "\"schema_version\":"+schemaVersion()+",\n"
                    + "\"synthetic_postings\":"+count()+",\n"
                    + "\"synthetic_balance\":"+balance()+",\n"
                    + "\"ledger_sha256\":\""+digest()+"\",\n"
                    + "\"real_external_issuer_verified\":false,\n"
                    + "\"stolen_bearer_replay_prevented\":false,\n"
                    + "\"production_fencing_verified\":false,\n"
                    + "\"production_authorized\":false\n"
                    + "}\n";
            Files.writeString(directory.resolve(scenario+".json"),json,StandardCharsets.UTF_8);
        }

        @Override public void close() throws Exception {
            try (Connection c=admin.openConnection();Statement s=c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
                for(Role r:new Role[]{writerOne,writerTwo,issuer,operator}) {
                    s.execute("DROP ROLE IF EXISTS "+r.login);
                }
            }
        }
    }
}
