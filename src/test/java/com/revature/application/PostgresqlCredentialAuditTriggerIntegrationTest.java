package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcCredentialAuditSource;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.CredentialAuditEvent;
import com.revature.service.AuditCheckpoint;
import com.revature.service.AuditSourceCursor;
import com.revature.service.CredentialAuditForwarder;
import com.revature.service.TrustedCredentialRotationService;
import com.revature.support.TestAccounts;

/**
 * PostgreSQL-only audit proof: even direct SQL credential changes by a
 * restricted writer fire a transactionally coupled, owner-held audit trigger.
 * It is not an external append-only/WORM audit system or production migration.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCredentialAuditTriggerIntegrationTest {

    @Test
    void serviceAndDirectSqlRotationsAreAuditedAndWriterCannotAlterAudit() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Credential audit integration only runs against exact disposable CI fixture");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "audit_" + suffix;
        String readRole = "identity_" + suffix;
        String writerRole = "rotate_" + suffix;
        String auditRole = "auditor_" + suffix;
        String readSecret = "ci-audit-reader-fixture-secret";
        String writeSecret = "ci-audit-writer-fixture-secret";
        String auditSecret = "ci-audit-reviewer-fixture-secret";
        String url = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase ownerServer = new JdbcDatabase(baseUrl, ownerName, ownerPassword);

        // UUID-generated identifiers, not operator-controlled input.
        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE ROLE " + readRole + " LOGIN PASSWORD '" + readSecret + "'");
            statement.execute("CREATE ROLE " + writerRole + " LOGIN PASSWORD '" + writeSecret + "'");
            statement.execute("CREATE ROLE " + auditRole + " LOGIN PASSWORD '" + auditSecret + "'");
        }

        JdbcDatabase migrator = new JdbcDatabase(url, ownerName, ownerPassword);
        migrator.migrate();
        JdbcUserAccountRepository ownerAccounts = new JdbcUserAccountRepository(migrator);
        String alice = "alice_" + suffix;
        String bob = "bob_" + suffix;
        TestAccounts.seed(ownerAccounts, AccountRole.CUSTOMER, alice, "first-alice");
        TestAccounts.seed(ownerAccounts, AccountRole.CUSTOMER, bob, "first-bob");

        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE " + schema + ".credential_rotation_audit ("
                    + "audit_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY, "
                    + "account_username VARCHAR(100) NOT NULL, "
                    + "database_actor VARCHAR(100) NOT NULL, "
                    + "changed_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP)");
            // Fully qualified objects and a trusted catalog-only search_path
            // avoid attacker-defined function or relation name resolution.
            statement.execute("CREATE FUNCTION " + schema + ".record_credential_rotation() "
                    + "RETURNS trigger LANGUAGE plpgsql SECURITY DEFINER "
                    + "SET search_path = pg_catalog AS $audit$ "
                    + "BEGIN "
                    + " IF OLD.encoded_credential IS DISTINCT FROM NEW.encoded_credential THEN "
                    + "  INSERT INTO " + schema
                    + ".credential_rotation_audit(account_username, database_actor) "
                    + "  VALUES (NEW.username, session_user); "
                    + " END IF; "
                    + " RETURN NEW; "
                    + "END; $audit$");
            statement.execute("REVOKE ALL ON FUNCTION " + schema
                    + ".record_credential_rotation() FROM PUBLIC");
            statement.execute("CREATE TRIGGER credential_rotation_audit_trigger "
                    + "AFTER UPDATE OF encoded_credential ON " + schema + ".accounts "
                    + "FOR EACH ROW EXECUTE FUNCTION " + schema + ".record_credential_rotation()");

            statement.execute("GRANT USAGE ON SCHEMA " + schema
                    + " TO " + readRole + ", " + writerRole + ", " + auditRole);
            statement.execute("GRANT SELECT ON " + schema + ".schema_history, "
                    + schema + ".accounts TO " + readRole + ", " + writerRole);
            statement.execute("GRANT UPDATE (encoded_credential) ON "
                    + schema + ".accounts TO " + writerRole);
            statement.execute("GRANT SELECT ON " + schema
                    + ".credential_rotation_audit TO " + auditRole);
        }

        JdbcDatabase readDatabase = new JdbcDatabase(url, readRole, readSecret);
        JdbcDatabase writerDatabase = new JdbcDatabase(url, writerRole, writeSecret);
        JdbcDatabase auditorDatabase = new JdbcDatabase(url, auditRole, auditSecret);
        TrustedCredentialRotationService trusted = new TrustedCredentialRotationService(
                new JdbcUserAccountRepository(readDatabase),
                new JdbcUserAccountRepository(writerDatabase));

        assertEquals(0, auditRows(ownerServer, schema).size());
        assertTrue(trusted.rotate(credentials(alice, "first-alice"), "second-alice"));

        // Simulate credential-writer SQL compromise: audit detects, but does
        // NOT prevent, this otherwise unauthorized reset of Bob's hash.
        try (Connection connection = writerDatabase.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET encoded_credential = ? WHERE username = ?")) {
            statement.setString(1, "compromised-encoded-hash");
            statement.setString(2, bob);
            assertEquals(1, statement.executeUpdate());
        }
        List<AuditRow> rows = auditRows(ownerServer, schema);
        assertEquals(2, rows.size());
        assertEquals(alice, rows.get(0).username);
        assertEquals(bob, rows.get(1).username);
        assertEquals(writerRole, rows.get(0).databaseActor);
        assertEquals(writerRole, rows.get(1).databaseActor);

        // Trigger insertion participates in the original SQL transaction:
        // rolled-back credential updates must not generate audit records.
        try (Connection connection = writerDatabase.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "UPDATE accounts SET encoded_credential = ? WHERE username = ?")) {
            connection.setAutoCommit(false);
            statement.setString(1, "rolled-back-hash");
            statement.setString(2, alice);
            assertEquals(1, statement.executeUpdate());
            connection.rollback();
        }
        assertEquals(2, auditRows(ownerServer, schema).size());

        assertSqlDenied(() -> {
            try (Connection connection = writerDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM credential_rotation_audit");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = writerDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE credential_rotation_audit "
                        + "SET database_actor = 'forged'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = writerDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER TABLE accounts DISABLE TRIGGER credential_rotation_audit_trigger");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = writerDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeQuery("SELECT * FROM credential_rotation_audit");
            }
        });

        // An independent auditor can read the ledger but has no write grants.
        assertEquals(2, auditRows(auditorDatabase, schema).size());
        assertSqlDenied(() -> {
            try (Connection connection = auditorDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM credential_rotation_audit");
            }
        });

        // Provider-neutral export proof with a fake external sink/checkpoint.
        // The SELECT-only auditor account, not the credential writer, polls.
        String auditStream = "disposable-schema-" + suffix;
        AtomicReference<AuditCheckpoint> acknowledged =
                new AtomicReference<>(new AuditCheckpoint(
                        auditStream, AuditSourceCursor.origin()));
        List<CredentialAuditEvent> exported = new ArrayList<>();
        CredentialAuditForwarder exporter = new CredentialAuditForwarder(
                auditStream,
                new JdbcCredentialAuditSource(auditorDatabase, auditStream),
                exported::add,
                new CredentialAuditForwarder.CheckpointStore() {
                    @Override public AuditCheckpoint load() { return acknowledged.get(); }
                    @Override public boolean advanceIfCurrent(
                            AuditCheckpoint expected, AuditSourceCursor next) {
                        AuditCheckpoint current = acknowledged.get();
                        return next.compareTo(expected.sourceCursor()) > 0 && current != null
                                && current.equals(expected) && acknowledged.compareAndSet(
                                current, new AuditCheckpoint(auditStream, next));
                    }
                },
                (phase, stream, cursor, id) -> {
                    throw new AssertionError("unexpected audit failure");
                },
                10);
        assertEquals(2, exporter.forwardNextBatch());
        assertEquals(0, exporter.forwardNextBatch());
        assertEquals(2, exported.size());
        assertEquals(alice, exported.get(0).accountUsername());
        assertEquals(bob, exported.get(1).accountUsername());
        assertEquals(writerRole, exported.get(0).databaseActor());
        assertTrue(acknowledged.get().sourceCursor().compareTo(
                AuditSourceCursor.origin()) > 0);
    }

    private static List<AuditRow> auditRows(JdbcDatabase database, String schema)
            throws SQLException {
        List<AuditRow> rows = new ArrayList<>();
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery(
                        "SELECT account_username, database_actor FROM "
                                + schema + ".credential_rotation_audit ORDER BY audit_id")) {
            while (result.next()) {
                rows.add(new AuditRow(result.getString(1), result.getString(2)));
            }
        }
        return rows;
    }

    private static void assertSqlDenied(Executable attempt) {
        SQLException failure = assertThrows(SQLException.class, attempt);
        assertEquals("42501", failure.getSQLState());
    }

    private static User credentials(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        return value;
    }

    private static final class AuditRow {
        private final String username;
        private final String databaseActor;
        private AuditRow(String username, String databaseActor) {
            this.username = username;
            this.databaseActor = databaseActor;
        }
    }
}
