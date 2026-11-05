package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
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
import com.revature.service.TrustedCredentialRotationService;
import com.revature.support.TestAccounts;

/**
 * Proves distinct SQL reader and credential-writer privileges in disposable
 * PostgreSQL, NOT an isolated deployed credential service or customer identity
 * token verification at an HTTP/network boundary.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlTrustedCredentialServiceIntegrationTest {

    @Test
    void aSeparateCredentialWriterMayRotateButTheRuntimeReaderCannot() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Credential service integration only runs against exact disposable CI fixture");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "svc_" + suffix;
        String readLogin = "readsvc_" + suffix;
        String writerLogin = "credential_" + suffix;
        String readSecret = "ci-only-reader-secret";
        String writeSecret = "ci-only-credential-writer-secret";
        String url = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase ownerServer = new JdbcDatabase(baseUrl, ownerName, ownerPassword);

        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE ROLE " + readLogin
                    + " LOGIN PASSWORD '" + readSecret + "'");
            statement.execute("CREATE ROLE " + writerLogin
                    + " LOGIN PASSWORD '" + writeSecret + "'");
        }

        JdbcDatabase migrator = new JdbcDatabase(url, ownerName, ownerPassword);
        migrator.migrate();
        JdbcUserAccountRepository fixtureWriter = new JdbcUserAccountRepository(migrator);
        String alice = "alice_" + suffix;
        String bob = "bob_" + suffix;
        TestAccounts.seed(fixtureWriter, AccountRole.CUSTOMER, alice, "initial-alice");
        TestAccounts.seed(fixtureWriter, AccountRole.CUSTOMER, bob, "initial-bob");

        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("GRANT USAGE ON SCHEMA " + schema
                    + " TO " + readLogin + ", " + writerLogin);
            statement.execute("GRANT SELECT ON TABLE " + schema + ".schema_history, "
                    + schema + ".accounts TO " + readLogin + ", " + writerLogin);
            statement.execute("GRANT UPDATE (encoded_credential) ON TABLE " + schema
                    + ".accounts TO " + writerLogin);
        }

        JdbcDatabase runtimeDatabase = new JdbcDatabase(url, readLogin, readSecret);
        JdbcDatabase trustedWriterDatabase = new JdbcDatabase(url, writerLogin, writeSecret);
        JdbcUserAccountRepository runtimeAccounts = new JdbcUserAccountRepository(runtimeDatabase);
        JdbcUserAccountRepository secretWriter = new JdbcUserAccountRepository(trustedWriterDatabase);
        assertThrows(IllegalArgumentException.class,
                () -> new TrustedCredentialRotationService(runtimeAccounts, runtimeAccounts));
        TrustedCredentialRotationService trusted =
                new TrustedCredentialRotationService(runtimeAccounts, secretWriter);

        // Only the separately held credential-writer login has the UPDATE grant.
        IllegalStateException denied = assertThrows(IllegalStateException.class,
                () -> runtimeAccounts.updateCredentialIfCurrent(alice,
                        runtimeAccounts.findByUsername(alice).orElseThrow(AssertionError::new)
                                .getPassword(), "forged-hash"));
        assertJdbcDenied(denied);
        assertThrows(SecurityException.class,
                () -> trusted.rotate(credentials(alice, "wrong-password"), "forged-password"));
        assertThrows(SecurityException.class,
                () -> trusted.rotate(credentials("missing_" + suffix, "anything"), "forged-password"));
        assertThrows(IllegalArgumentException.class,
                () -> trusted.rotate(credentials(alice, "initial-alice"), " "));

        assertTrue(trusted.rotate(credentials(alice, "initial-alice"), "rotated-alice"));
        assertFalse(new com.revature.service.UserLoginService(runtimeAccounts)
                .authenticate(credentials(alice, "initial-alice")).isPresent());
        assertTrue(new com.revature.service.UserLoginService(runtimeAccounts)
                .authenticate(credentials(alice, "rotated-alice")).isPresent());
        assertThrows(SecurityException.class,
                () -> trusted.rotate(credentials(alice, "initial-alice"), "stale-request"));
        assertTrue(trusted.rotate(credentials(bob, "initial-bob"), "rotated-bob"));

        // The credential writer itself must not be allowed to elevate roles,
        // create staff accounts, delete accounts, change schema or bootstrap state.
        assertSqlDenied(() -> {
            try (Connection connection = trustedWriterDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = '"
                        + alice + "'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = trustedWriterDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM accounts WHERE username = '" + bob + "'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = trustedWriterDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO accounts(username, encoded_credential, role) "
                        + "VALUES ('forged', 'forged', 'ADMINISTRATOR')");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = trustedWriterDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE forbidden_ddl (id INTEGER)");
            }
        });
        assertSqlDenied(() -> {
            try (Connection connection = trustedWriterDatabase.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.executeUpdate("UPDATE administrator_bootstrap_state "
                        + "SET initial_admin_username = 'forged' WHERE bootstrap_id = 1");
            }
        });

        assertEquals(AccountRole.CUSTOMER,
                runtimeAccounts.findByUsername(alice).orElseThrow(AssertionError::new).getRole());
        assertTrue(new com.revature.service.UserLoginService(runtimeAccounts)
                .authenticate(credentials(bob, "rotated-bob")).isPresent());

        // Known limitation: raw SQL with the credential writer login still has
        // UPDATE permission for other users' hashes. Protect that login through
        // an isolated trusted process, access review, audit and incident controls.
    }

    private static void assertJdbcDenied(Throwable failure) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) current = current.getCause();
        assertTrue(current instanceof SQLException, "expected JDBC SQLSTATE");
        assertEquals("42501", ((SQLException) current).getSQLState());
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
