package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcRotationClaimLedger;
import com.revature.repository.jdbc.ReconciledJdbcWriterGate.ReopenProof;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;

/**
 * Isolated PostgreSQL enforcement rehearsal. The database, not an application
 * preflight or caller-supplied epoch, must serialize and reject stale writes.
 * Never reuse these owner-role DDL and fault-injection steps outside CI.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlWriterEpochFenceIntegrationTest {
    private static final String SECRET = "ci-only-epoch-fence-secret";

    @Test
    void oldCommitCompletesBeforeCutoverThenSameSessionIsFenced() throws Exception {
        Fixture fixture = fixture();
        JdbcDatabase oldWriter = new JdbcDatabase(
                fixture.schemaUrl, fixture.oldRole, SECRET);
        JdbcDatabase nextWriter = new JdbcDatabase(
                fixture.schemaUrl, fixture.nextRole, SECRET);

        try (Connection premature = nextWriter.openConnection()) {
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> updateCredential(premature, "before-cutover")).getSQLState());
        }
        try (Connection stale = oldWriter.openConnection()) {
            stale.setAutoCommit(false);
            assertEquals(1, updateCredential(stale, "committed-before-fence"));

            // The old transaction holds a database row lock acquired by the
            // BEFORE UPDATE trigger until it commits. A cutover UPDATE must
            // wait, even though it runs through a different SQL connection.
            CountDownLatch rotationStarted = new CountDownLatch(1);
            AtomicInteger rotationPid = new AtomicInteger();
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<Boolean> rotation = worker.submit(() ->
                        new JdbcRotationClaimLedger(() -> {
                            Connection claimant = fixture.claimantDb.openConnection();
                            try (Statement statement = claimant.createStatement();
                                    ResultSet pid = statement.executeQuery(
                                            "SELECT pg_backend_pid()")) {
                                assertTrue(pid.next());
                                rotationPid.set(pid.getInt(1));
                            }
                            rotationStarted.countDown();
                            return claimant;
                        }).claim(proof()));
                assertTrue(rotationStarted.await(15, TimeUnit.SECONDS));
                awaitSqlLock(fixture, rotationPid.get());
                assertFalse(rotation.isDone(),
                        "cutover must not commit across an active old write");
                assertEquals(0, auditCount(fixture),
                        "old audit row is uncommitted and invisible to the owner");
                // A separate session must see no uncommitted audit event.
                // Release the transaction before the cutover can complete.
                stale.commit();
                assertTrue(rotation.get(15, TimeUnit.SECONDS));
            } finally {
                stale.rollback();
                worker.shutdownNow();
                assertTrue(worker.awaitTermination(15, TimeUnit.SECONDS));
            }

            assertEquals(2, activeEpoch(fixture));
            assertFalse(new JdbcRotationClaimLedger(fixture.claimantDb::openConnection)
                    .claim(proof()), "the committed epoch cannot be replayed");
            assertEquals("committed-before-fence", credential(fixture));
            assertEquals(1, auditCount(fixture));

            // Still connected and authenticated using the old login, but the
            // database rejects the credential mutation. A caller-controlled
            // GUC cannot impersonate the new epoch or trusted DB principal.
            try (Statement statement = stale.createStatement()) {
                statement.execute("SET app.writer_epoch = '2'");
                try (ResultSet alive = statement.executeQuery("SELECT 1")) {
                    assertTrue(alive.next());
                }
            }
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> updateCredential(stale, "stale-session-write")).getSQLState());
            stale.rollback();
            assertEquals("42501", assertThrows(SQLException.class, () -> {
                try (Statement statement = stale.createStatement()) {
                    statement.executeUpdate("UPDATE writer_rotation_floors SET rotation_epoch = 3");
                }
            }).getSQLState());
            stale.rollback();
            assertEquals("42501", assertThrows(SQLException.class, () -> {
                try (Statement statement = stale.createStatement()) {
                    statement.execute("ALTER TABLE credential_state "
                            + "DISABLE TRIGGER credential_epoch_fence");
                }
            }).getSQLState());
            stale.rollback();

            try (Connection current = nextWriter.openConnection()) {
                assertEquals(1, updateCredential(current, "new-epoch-committed"));
            }
            assertEquals("new-epoch-committed", credential(fixture));
            assertEquals(2, auditCount(fixture));
            assertAuditActors(fixture);
        }
    }

    @Test
    void transactionOpenedBeforeCutoverCannotWriteAfterIt() throws Exception {
        Fixture fixture = fixture();
        JdbcDatabase oldWriter = new JdbcDatabase(
                fixture.schemaUrl, fixture.oldRole, SECRET);
        JdbcDatabase nextWriter = new JdbcDatabase(
                fixture.schemaUrl, fixture.nextRole, SECRET);
        try (Connection oldSession = oldWriter.openConnection()) {
            oldSession.setAutoCommit(false);
            try (Statement statement = oldSession.createStatement();
                    ResultSet result = statement.executeQuery("SELECT 1")) {
                assertTrue(result.next());
            }
            // This transaction began before rotation but had not obtained
            // the trigger's shared guard-row lock.
            advanceEpoch(fixture);
            assertEquals("42501", assertThrows(SQLException.class,
                    () -> updateCredential(oldSession, "too-late")).getSQLState());
            oldSession.rollback();
            assertEquals("initial", credential(fixture));
            assertEquals(0, auditCount(fixture));
            try (Connection current = nextWriter.openConnection()) {
                assertEquals(1, updateCredential(current, "new-writer-only"));
            }
            assertEquals(1, auditCount(fixture));
        }
    }

    private static Fixture fixture() throws SQLException {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Server-side epoch fence tests require exact disposable CI PostgreSQL");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "epoch_" + suffix;
        String oldRole = "epoch_old_" + suffix;
        String nextRole = "epoch_new_" + suffix;
        String claimantRole = "epoch_claimant_" + suffix;
        JdbcDatabase owner = new JdbcDatabase(baseUrl, ownerName, ownerPassword);
        try (Connection connection = owner.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE ROLE " + oldRole + " LOGIN PASSWORD '" + SECRET + "'");
            sql.execute("CREATE ROLE " + nextRole + " LOGIN PASSWORD '" + SECRET + "'");
            sql.execute("CREATE ROLE " + claimantRole + " LOGIN PASSWORD '" + SECRET + "'");
            sql.execute("CREATE TABLE " + schema + ".writer_rotation_floors ("
                    + "stream_id VARCHAR(100) PRIMARY KEY, "
                    + "credential_generation BIGINT NOT NULL, rotation_epoch BIGINT NOT NULL, "
                    + "cursor_position BIGINT NOT NULL, cursor_ordinal INTEGER NOT NULL)");
            sql.execute("INSERT INTO " + schema
                    + ".writer_rotation_floors VALUES ('ci-fence-stream', 1, 1, 0, 0)");
            sql.execute("CREATE TABLE " + schema + ".writer_role_epoch ("
                    + "role_name TEXT PRIMARY KEY, allowed_epoch BIGINT NOT NULL)");
            sql.execute("INSERT INTO " + schema + ".writer_role_epoch VALUES ('"
                    + oldRole + "', 1), ('" + nextRole + "', 2)");
            sql.execute("CREATE TABLE " + schema + ".credential_state ("
                    + "id INTEGER PRIMARY KEY, encoded_credential TEXT NOT NULL)");
            sql.execute("INSERT INTO " + schema + ".credential_state VALUES (1, 'initial')");
            sql.execute("CREATE TABLE " + schema + ".rotation_audit ("
                    + "id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                    + "state_id INTEGER NOT NULL, actor TEXT NOT NULL)");
            sql.execute("CREATE FUNCTION " + schema + ".enforce_writer_epoch() "
                    + "RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER "
                    + "SET search_path = pg_catalog AS $fence$ "
                    + "DECLARE live_epoch BIGINT; actor_epoch BIGINT; "
                    + "BEGIN "
                    + "SELECT rotation_epoch INTO STRICT live_epoch FROM " + schema
                    + ".writer_rotation_floors WHERE stream_id = 'ci-fence-stream' "
                    + "FOR SHARE; "
                    + "SELECT allowed_epoch INTO STRICT actor_epoch FROM " + schema
                    + ".writer_role_epoch WHERE role_name = session_user; "
                    + "IF actor_epoch <> live_epoch THEN "
                    + "RAISE EXCEPTION 'writer epoch fenced' USING ERRCODE = '42501'; "
                    + "END IF; RETURN NEW; "
                    + "EXCEPTION WHEN NO_DATA_FOUND THEN "
                    + "RAISE EXCEPTION 'writer epoch unavailable' USING ERRCODE = '42501'; "
                    + "END; $fence$");
            sql.execute("REVOKE ALL ON FUNCTION " + schema
                    + ".enforce_writer_epoch() FROM PUBLIC");
            sql.execute("CREATE TRIGGER credential_epoch_fence "
                    + "BEFORE UPDATE OF encoded_credential ON " + schema
                    + ".credential_state FOR EACH ROW EXECUTE FUNCTION " + schema
                    + ".enforce_writer_epoch()");
            sql.execute("CREATE FUNCTION " + schema + ".audit_credential_change() "
                    + "RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER "
                    + "SET search_path = pg_catalog AS $audit$ BEGIN "
                    + "IF OLD.encoded_credential IS DISTINCT FROM NEW.encoded_credential THEN "
                    + "INSERT INTO " + schema
                    + ".rotation_audit(state_id, actor) VALUES (NEW.id, session_user); "
                    + "END IF; RETURN NEW; END; $audit$");
            sql.execute("REVOKE ALL ON FUNCTION " + schema
                    + ".audit_credential_change() FROM PUBLIC");
            sql.execute("CREATE TRIGGER credential_audit AFTER UPDATE OF "
                    + "encoded_credential ON " + schema + ".credential_state "
                    + "FOR EACH ROW EXECUTE FUNCTION " + schema + ".audit_credential_change()");
            for (String role : new String[] {oldRole, nextRole}) {
                sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + role);
                sql.execute("GRANT SELECT ON " + schema + ".credential_state TO " + role);
                sql.execute("GRANT UPDATE (encoded_credential) ON " + schema
                        + ".credential_state TO " + role);
            }
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + claimantRole);
            sql.execute("GRANT SELECT, UPDATE ON " + schema
                    + ".writer_rotation_floors TO " + claimantRole);
        }
        return new Fixture(owner, schema, baseUrl + "?currentSchema=" + schema,
                oldRole, nextRole, claimantRole);
    }

    private static int updateCredential(Connection connection, String value)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "UPDATE credential_state SET encoded_credential = ? WHERE id = 1")) {
            statement.setString(1, value);
            return statement.executeUpdate();
        }
    }

    private static void advanceEpoch(Fixture fixture) throws SQLException {
        assertTrue(new JdbcRotationClaimLedger(fixture.claimantDb::openConnection)
                .claim(proof()));
    }

    private static ReopenProof proof() {
        AuditCheckpoint checkpoint = new AuditCheckpoint("ci-fence-stream",
                new AuditSourceCursor(1, 0));
        return new ReopenProof(1, 2, 2, checkpoint, checkpoint);
    }

    private static void awaitSqlLock(Fixture fixture, int pid) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) {
            try (Connection owner = fixture.owner.openConnection();
                    PreparedStatement statement = owner.prepareStatement(
                            "SELECT wait_event_type FROM pg_stat_activity WHERE pid = ?")) {
                statement.setInt(1, pid);
                try (ResultSet row = statement.executeQuery()) {
                    if (row.next() && "Lock".equals(row.getString(1))) return;
                }
            }
            Thread.sleep(50);
        }
        fail("rotation SQL was not observed waiting for old transaction's lock");
    }

    private static String credential(Fixture fixture) throws SQLException {
        try (Connection owner = fixture.owner.openConnection();
                Statement statement = owner.createStatement();
                ResultSet row = statement.executeQuery("SELECT encoded_credential FROM "
                        + fixture.schema + ".credential_state WHERE id = 1")) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static int activeEpoch(Fixture fixture) throws SQLException {
        return (int) new JdbcRotationClaimLedger(fixture.claimantDb::openConnection)
                .load("ci-fence-stream").epoch();
    }

    private static int auditCount(Fixture fixture) throws SQLException {
        try (Connection owner = fixture.owner.openConnection();
                Statement statement = owner.createStatement();
                ResultSet row = statement.executeQuery("SELECT count(*) FROM "
                        + fixture.schema + ".rotation_audit")) {
            assertTrue(row.next());
            return row.getInt(1);
        }
    }

    private static void assertAuditActors(Fixture fixture) throws SQLException {
        try (Connection owner = fixture.owner.openConnection();
                Statement statement = owner.createStatement();
                ResultSet row = statement.executeQuery("SELECT actor FROM "
                        + fixture.schema + ".rotation_audit ORDER BY id")) {
            assertTrue(row.next());
            assertEquals(fixture.oldRole, row.getString(1));
            assertTrue(row.next());
            assertEquals(fixture.nextRole, row.getString(1));
            assertFalse(row.next());
        }
    }

    private static final class Fixture {
        private final JdbcDatabase owner;
        private final String schema;
        private final String schemaUrl;
        private final String oldRole;
        private final String nextRole;
        private final JdbcDatabase claimantDb;

        private Fixture(JdbcDatabase owner, String schema, String schemaUrl,
                String oldRole, String nextRole, String claimantRole) {
            this.owner = owner;
            this.schema = schema;
            this.schemaUrl = schemaUrl;
            this.oldRole = oldRole;
            this.nextRole = nextRole;
            this.claimantDb = new JdbcDatabase(schemaUrl, claimantRole, SECRET);
        }
    }
}
