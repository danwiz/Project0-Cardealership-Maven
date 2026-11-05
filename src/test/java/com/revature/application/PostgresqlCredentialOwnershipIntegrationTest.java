package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * Proof of end-user-scoped SQL login / RLS credential ownership on a disposable
 * PostgreSQL CI fixture. This is NOT wired into the shared-login application.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCredentialOwnershipIntegrationTest {

    @Test
    void separateAuthenticatedSqlLoginsCannotReadOrRotateAnotherCustomersHash() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Credential ownership integration only runs against exact disposable CI fixture");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "cred_" + suffix;
        String alice = "cust_a_" + suffix;
        String bob = "cust_b_" + suffix;
        String aliceSqlSecret = "ci-alice-fixture-db-secret";
        String bobSqlSecret = "ci-bob-fixture-db-secret";
        String url = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase fixtureServer = new JdbcDatabase(baseUrl, ownerName, ownerPassword);

        // Identifiers originate solely from a test-generated UUID.
        try (Connection connection = fixtureServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE ROLE " + alice + " LOGIN PASSWORD '" + aliceSqlSecret + "'");
            statement.execute("CREATE ROLE " + bob + " LOGIN PASSWORD '" + bobSqlSecret + "'");
        }

        JdbcDatabase migrator = new JdbcDatabase(url, ownerName, ownerPassword);
        migrator.migrate();
        JdbcUserAccountRepository fixtureWriter = new JdbcUserAccountRepository(migrator);
        TestAccounts.seed(fixtureWriter, AccountRole.CUSTOMER, alice, "alice-password");
        TestAccounts.seed(fixtureWriter, AccountRole.CUSTOMER, bob, "bob-password");
        String originalBobHash = fixtureWriter.findByUsername(bob)
                .orElseThrow(AssertionError::new).getPassword();

        try (Connection connection = fixtureServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".accounts ENABLE ROW LEVEL SECURITY");
            // session_user is the authenticated SQL login, not an application SET variable.
            statement.execute("CREATE POLICY customer_own_select ON " + schema
                    + ".accounts FOR SELECT TO " + alice + ", " + bob
                    + " USING (username = session_user)");
            statement.execute("CREATE POLICY customer_own_credential_update ON " + schema
                    + ".accounts FOR UPDATE TO " + alice + ", " + bob
                    + " USING (username = session_user) WITH CHECK (username = session_user)");
            statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + alice + ", " + bob);
            statement.execute("GRANT SELECT ON TABLE " + schema + ".schema_history, "
                    + schema + ".accounts TO " + alice + ", " + bob);
            statement.execute("GRANT UPDATE (encoded_credential) ON " + schema
                    + ".accounts TO " + alice + ", " + bob);
        }

        JdbcDatabase aliceDb = new JdbcDatabase(url, alice, aliceSqlSecret);
        JdbcDatabase bobDb = new JdbcDatabase(url, bob, bobSqlSecret);
        JdbcUserAccountRepository aliceRepository = new JdbcUserAccountRepository(aliceDb);
        JdbcUserAccountRepository bobRepository = new JdbcUserAccountRepository(bobDb);
        UserLoginService aliceLogin = new UserLoginService(
                aliceRepository, (UserAccountRepository) null);
        UserLoginService bobLogin = new UserLoginService(
                bobRepository, (UserAccountRepository) null);

        assertEquals(alice, sessionUser(aliceDb));
        assertEquals(bob, sessionUser(bobDb));
        assertTrue(aliceRepository.findByUsername(alice).isPresent());
        assertFalse(aliceRepository.findByUsername(bob).isPresent());
        assertTrue(bobRepository.findByUsername(bob).isPresent());
        assertFalse(bobRepository.findByUsername(alice).isPresent());
        assertEquals(1, aliceRepository.findAll().size());
        assertEquals(1, bobRepository.findAll().size());

        // The account's own login can still perform authenticated password rotation.
        assertTrue(aliceLogin.authenticate(credentials(alice, "alice-password")).isPresent());
        assertTrue(aliceLogin.changePassword(credentials(alice, "alice-password"), "alice-new-password"));
        assertFalse(aliceLogin.authenticate(credentials(alice, "alice-password")).isPresent());
        assertTrue(aliceLogin.authenticate(credentials(alice, "alice-new-password")).isPresent());

        // Application-level credential forgery cannot access the other user's row.
        assertFalse(aliceLogin.authenticate(credentials(bob, "bob-password")).isPresent());
        assertThrows(SecurityException.class,
                () -> aliceLogin.changePassword(credentials(bob, "bob-password"), "forged-credential"));
        assertFalse(aliceRepository.updateCredentialIfCurrent(
                bob, originalBobHash, "forged-hash"));

        // Direct SQL is subject to the same database identity / row policy.
        try (Connection connection = aliceDb.openConnection();
                Statement statement = connection.createStatement()) {
            assertEquals(0, statement.executeUpdate(
                    "UPDATE accounts SET encoded_credential = 'forged-hash' WHERE username = '" + bob + "'"));
            try (ResultSet rows = statement.executeQuery(
                    "SELECT username FROM accounts WHERE username = '" + bob + "'")) {
                assertFalse(rows.next());
            }
        }

        // No role membership is granted: the caller cannot assume the other's identity.
        assertSqlDenied(() -> {
            try (Connection connection = aliceDb.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("SET ROLE " + bob);
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = aliceDb.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = '"
                        + alice + "'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = aliceDb.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM accounts WHERE username = '" + bob + "'");
            }
        });

        assertEquals(originalBobHash,
                fixtureWriter.findByUsername(bob).orElseThrow(AssertionError::new).getPassword());
        assertTrue(bobLogin.authenticate(credentials(bob, "bob-password")).isPresent());
    }

    private static String sessionUser(JdbcDatabase database) throws SQLException {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT session_user")) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static void assertSqlDenied(Executable action) {
        SQLException failure = assertThrows(SQLException.class, action);
        assertEquals("42501", failure.getSQLState());
    }

    private static User credentials(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        return value;
    }
}
