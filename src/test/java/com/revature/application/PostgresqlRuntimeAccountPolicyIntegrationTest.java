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
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * CI-only PostgreSQL account-policy proof. Column-level grants and row-level
 * security protect registration roles even when callers bypass Java services.
 * This does not grant least privilege for the entire dealership runtime.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlRuntimeAccountPolicyIntegrationTest {

    @Test
    void customerOnlyInsertsAndCredentialOnlyUpdatesAreEnforcedByDatabase() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Account policy integration only runs against the exact disposable CI fixture");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "rtp_" + suffix;
        String runtimeRole = "rt_" + suffix;
        String runtimeSecret = "isolated-runtime-fixture-secret";
        String url = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase ownerServer = new JdbcDatabase(baseUrl, ownerName, ownerPassword);

        // The fixture owner, not the application identity, owns migration/DDL.
        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
            statement.execute("CREATE ROLE " + runtimeRole
                    + " LOGIN PASSWORD '" + runtimeSecret + "'");
        }

        JdbcDatabase migrator = new JdbcDatabase(url, ownerName, ownerPassword);
        migrator.migrate();
        TestAccounts.seed(new JdbcUserAccountRepository(migrator),
                AccountRole.ADMINISTRATOR, "root_" + suffix, "root-password");

        try (Connection connection = ownerServer.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("ALTER TABLE " + schema + ".accounts ENABLE ROW LEVEL SECURITY");
            statement.execute("CREATE POLICY runtime_account_read ON " + schema
                    + ".accounts FOR SELECT TO " + runtimeRole + " USING (true)");
            statement.execute("CREATE POLICY runtime_customer_insert ON " + schema
                    + ".accounts FOR INSERT TO " + runtimeRole
                    + " WITH CHECK (role = 'CUSTOMER')");
            statement.execute("CREATE POLICY runtime_credential_change ON " + schema
                    + ".accounts FOR UPDATE TO " + runtimeRole
                    + " USING (true) WITH CHECK (true)");
            statement.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + runtimeRole);
            statement.execute("GRANT SELECT ON TABLE " + schema + ".schema_history, "
                    + schema + ".accounts TO " + runtimeRole);
            statement.execute("GRANT INSERT (username, encoded_credential, role) ON "
                    + schema + ".accounts TO " + runtimeRole);
            statement.execute("GRANT UPDATE (encoded_credential) ON "
                    + schema + ".accounts TO " + runtimeRole);
        }

        JdbcDatabase runtimeDatabase = new JdbcDatabase(url, runtimeRole, runtimeSecret);
        JdbcUserAccountRepository runtimeRepository = new JdbcUserAccountRepository(runtimeDatabase);
        UserLoginService runtimeLogin = new UserLoginService(
                runtimeRepository, (UserAccountRepository) null);
        String customerName = "customer_" + suffix;
        assertEquals(AccountRole.CUSTOMER,
                runtimeLogin.registerUser(customerName, "initial-password").getRole());

        User customerCredentials = credentials(customerName, "initial-password");
        assertTrue(runtimeLogin.authenticate(customerCredentials).isPresent());
        assertTrue(runtimeLogin.changePassword(customerCredentials, "replacement-password"));
        assertFalse(runtimeLogin.authenticate(customerCredentials).isPresent());
        assertTrue(runtimeLogin.authenticate(credentials(customerName, "replacement-password")).isPresent());

        // A forged repository object does not bypass PostgreSQL's WITH CHECK.
        IllegalStateException forbiddenRegistration = assertThrows(IllegalStateException.class,
                () -> runtimeRepository.save(account("forged_" + suffix, AccountRole.ADMINISTRATOR)));
        assertJdbcState(forbiddenRegistration, "42501");

        // Nor can the shared runtime SQL principal reassign roles or delete accounts.
        assertSqlDenied(() -> {
            try (Connection c = runtimeDatabase.openConnection();
                    Statement statement = c.createStatement()) {
                statement.executeUpdate("UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = '"
                        + customerName + "'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection c = runtimeDatabase.openConnection();
                    Statement statement = c.createStatement()) {
                statement.executeUpdate("DELETE FROM accounts WHERE username = '" + customerName + "'");
            }
        });
        assertSqlDenied(() -> {
            try (Connection c = runtimeDatabase.openConnection();
                    Statement statement = c.createStatement()) {
                statement.execute("CREATE TABLE forbidden_ddl (id INTEGER)");
            }
        });
        assertSqlDenied(() -> {
            try (Connection c = runtimeDatabase.openConnection();
                    Statement statement = c.createStatement()) {
                statement.executeUpdate("UPDATE administrator_bootstrap_state "
                        + "SET initial_admin_username = 'forged' WHERE bootstrap_id = 1");
            }
        });
        assertEquals(AccountRole.CUSTOMER,
                runtimeRepository.findByUsername(customerName).orElseThrow(AssertionError::new).getRole());
    }

    private static void assertSqlDenied(Executable attempt) {
        SQLException error = assertThrows(SQLException.class, attempt);
        assertEquals("42501", error.getSQLState());
    }

    private static void assertJdbcState(Throwable failure, String expected) {
        Throwable current = failure;
        while (current != null && !(current instanceof SQLException)) {
            current = current.getCause();
        }
        assertTrue(current instanceof SQLException, "expected a JDBC SQLSTATE");
        assertEquals(expected, ((SQLException) current).getSQLState());
    }

    private static User account(String username, AccountRole role) {
        User user = credentials(username, "encoded-test-credential");
        user.setRole(role);
        return user;
    }

    private static User credentials(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}
