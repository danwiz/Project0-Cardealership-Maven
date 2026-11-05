package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.UserAccountRepository;
import com.revature.support.TestAccounts;

/** Disposable PostgreSQL 16-only v4-to-v5 schema migration/revision proof. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlSessionRevisionMigrationTest {

    @Test
    void migratingExistingAccountsAndSqlInsertsMintsUniqueDurableRevisions() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "This migration proof runs only in the disposable GitHub CI PostgreSQL 16 fixture");

        String schema = "sessionrev_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase owner = new JdbcDatabase(baseUrl, ownerName, ownerPassword);
        try (Connection connection = owner.openConnection(); Statement s = connection.createStatement()) {
            s.execute("CREATE SCHEMA " + schema);
        }

        JdbcDatabase database = new JdbcDatabase(baseUrl + "?currentSchema=" + schema,
                ownerName, ownerPassword);
        database.migrate();
        UserAccountRepository repository = new JdbcUserAccountRepository(database);
        TestAccounts.seed(repository, AccountRole.EMPLOYEE, "legacy_one", "TestPass123!");
        TestAccounts.seed(repository, AccountRole.CUSTOMER, "legacy_two", "TestPass456!");

        // Recreate the schema-v4 *shape* and version marker to rehearse the
        // additive upgrade with populated accounts; never run on user data.
        try (Connection connection = database.openConnection(); Statement s = connection.createStatement()) {
            s.execute("DROP TRIGGER account_session_revision_rotation ON accounts");
            s.execute("DROP FUNCTION rotate_account_session_revision()");
            s.execute("ALTER TABLE accounts DROP COLUMN session_revision");
            s.execute("DELETE FROM schema_history WHERE version = 5");
        }
        assertEquals(4, database.schemaVersion());
        database.migrate();
        assertEquals(5, database.schemaVersion());

        String first = repository.findByUsername("legacy_one").orElseThrow(AssertionError::new)
                .getSessionRevision();
        String second = repository.findByUsername("legacy_two").orElseThrow(AssertionError::new)
                .getSessionRevision();
        assertNotNull(first);
        assertNotNull(second);
        assertNotEquals(first, second, "migration must assign independent account incarnations");
        assertEquals(first, new JdbcUserAccountRepository(database)
                .findByUsername("legacy_one").orElseThrow(AssertionError::new).getSessionRevision());

        // Verify that bootstrap-style and other direct, trusted SQL inserts
        // receive a server-generated UUID when they omit session_revision.
        String encoded = new Pbkdf2PasswordHasher().hash("TestPass789!");
        try (Connection connection = database.openConnection();
                PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO accounts(username, encoded_credential, role) VALUES (?, ?, ?)")) {
            insert.setString(1, "sql_created");
            insert.setString(2, encoded);
            insert.setString(3, AccountRole.CUSTOMER.name());
            assertEquals(1, insert.executeUpdate());
        }
        User direct = repository.findByUsername("sql_created").orElseThrow(AssertionError::new);
        assertNotNull(direct.getSessionRevision());
        assertNotEquals(first, direct.getSessionRevision());
        assertNotEquals(second, direct.getSessionRevision());

        // All supported repository mutations must rotate the persisted version.
        assertTrue(repository.updateRoleIfCurrent("legacy_one",
                AccountRole.EMPLOYEE, AccountRole.CUSTOMER));
        String updated = repository.findByUsername("legacy_one").orElseThrow(AssertionError::new)
                .getSessionRevision();
        assertNotEquals(first, updated);

        // Even a direct SQL credential update must revoke an old session
        // without giving a restricted writer UPDATE on session_revision.
        try (Connection connection = database.openConnection();
                PreparedStatement directUpdate = connection.prepareStatement(
                        "UPDATE accounts SET encoded_credential = ? WHERE username = ?")) {
            directUpdate.setString(1, new Pbkdf2PasswordHasher().hash("A-New-Secret123!"));
            directUpdate.setString(2, "legacy_one");
            assertEquals(1, directUpdate.executeUpdate());
        }
        String rotated = repository.findByUsername("legacy_one").orElseThrow(AssertionError::new)
                .getSessionRevision();
        assertNotEquals(updated, rotated,
                "owner-owned SQL trigger must rotate a revision without a column grant");

        // A rolled-back change must not advance the durable generation.
        try (Connection connection = database.openConnection();
                PreparedStatement rollback = connection.prepareStatement(
                        "UPDATE accounts SET encoded_credential = ? WHERE username = ?")) {
            connection.setAutoCommit(false);
            rollback.setString(1, "rolled-back-test-hash");
            rollback.setString(2, "legacy_one");
            assertEquals(1, rollback.executeUpdate());
            connection.rollback();
        }
        assertEquals(rotated, repository.findByUsername("legacy_one")
                .orElseThrow(AssertionError::new).getSessionRevision());
    }
}
