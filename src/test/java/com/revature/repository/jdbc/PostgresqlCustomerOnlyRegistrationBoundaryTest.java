package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;

/**
 * Wave21: actual PostgreSQL 16 SQL principal/customer registration boundary.
 * Owner creates a role-scoped SECURITY DEFINER SQL function and grants EXECUTE
 * while retaining all account-table DML with the separate schema owner.
 * This is NOT an approved production migration or secrets/grants policy.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCustomerOnlyRegistrationBoundaryTest {
    @Test
    @Timeout(85)
    void limitedRegistrarCreatesOnlyCustomerAndCannotMutateAccountsDirectly() throws Exception {
        String base = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "only the disposable PostgreSQL16 CI fixture is authorized");

        String suffix = UUID.randomUUID().toString().replace("-", "");
        String schema = "w21_" + suffix;
        String registrarRole = "w21registrar_" + suffix;
        String registrarPassword = "ci-only-registrar-synthetic-password";
        JdbcDatabase ownerServer = new JdbcDatabase(base, ownerName, ownerPassword);
        boolean roleCreated = false;
        try {
            try (Connection connection = ownerServer.openConnection();
                    Statement statement = connection.createStatement()) {
                try (ResultSet version = statement.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int numeric = Integer.parseInt(version.getString(1));
                    assertTrue(numeric >= 160000 && numeric < 170000,
                            "must use actual disposable PostgreSQL16");
                }
                statement.execute("CREATE SCHEMA " + schema);
                statement.execute("CREATE ROLE " + registrarRole
                        + " LOGIN PASSWORD '" + registrarPassword + "'");
                roleCreated = true;
            }
            String scopedUrl = base + "?currentSchema=" + schema;
            JdbcDatabase owner = new JdbcDatabase(scopedUrl, ownerName, ownerPassword);
            owner.migrate();

            String signature = schema + ".register_customer_trusted(text,text)";
            try (Connection connection = ownerServer.openConnection();
                    Statement sql = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    sql.execute(loadSqlTemplate().replace("__SCHEMA__", schema));
                    // No committed public EXECUTE window.
                    sql.execute("REVOKE ALL ON FUNCTION " + signature + " FROM PUBLIC");
                    sql.execute("REVOKE ALL ON SCHEMA " + schema + " FROM PUBLIC");
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + registrarRole);
                    sql.execute("GRANT SELECT ON " + schema + ".schema_history, "
                            + schema + ".accounts TO " + registrarRole);
                    sql.execute("GRANT EXECUTE ON FUNCTION " + signature + " TO " + registrarRole);
                    connection.commit();
                } catch (SQLException | RuntimeException failure) {
                    connection.rollback();
                    throw failure;
                }
            }

            JdbcDatabase registrarDb = new JdbcDatabase(scopedUrl, registrarRole, registrarPassword);
            UserAccountRepository limited = new JdbcCustomerOnlyRegistrationRepository(registrarDb);
            UserLoginService registration = new UserLoginService(limited,
                    (UserAccountRepository) null);

            User created = registration.registerUser("newbuyer", "synthetic-buyer-password");
            assertEquals(AccountRole.CUSTOMER, created.getRole());
            assertNull(created.getSessionRevision(),
                    "caller cannot claim database-issued session generation");
            User persisted = new JdbcUserAccountRepository(owner).findByUsername("newbuyer")
                    .orElseThrow(AssertionError::new);
            assertEquals(AccountRole.CUSTOMER, persisted.getRole());
            assertNotNull(persisted.getSessionRevision());
            assertTrue(new Pbkdf2PasswordHasher().matches(
                    "synthetic-buyer-password", persisted.getPassword()));
            assertTrue(registration.authenticate(credentials("newbuyer",
                    "synthetic-buyer-password")).isPresent());

            // Even a direct caller of the SQL function has no role parameter.
            String directEncoded = new Pbkdf2PasswordHasher().hash("direct-password");
            try (Connection connection = registrarDb.openConnection();
                    PreparedStatement statement = connection.prepareStatement(
                            "SELECT register_customer_trusted(?, ?)")) {
                statement.setString(1, "direct-customer");
                statement.setString(2, directEncoded);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    assertEquals("direct-customer", result.getString(1));
                }
            }
            assertEquals(AccountRole.CUSTOMER,
                    new JdbcUserAccountRepository(owner).findByUsername("direct-customer")
                            .orElseThrow(AssertionError::new).getRole());

            assertThrows(IllegalArgumentException.class, () ->
                    registration.registerUser("newbuyer", "second-registration-password"));
            User forged = new User();
            forged.setUsername("forged-admin");
            forged.setRole(AccountRole.ADMINISTRATOR);
            forged.setPassword(new Pbkdf2PasswordHasher().hash("forged-password"));
            assertThrows(SecurityException.class, () -> limited.save(forged));
            assertThrows(SecurityException.class, () ->
                    limited.updateRoleIfCurrent("newbuyer", AccountRole.CUSTOMER,
                            AccountRole.ADMINISTRATOR));
            assertThrows(SecurityException.class, () ->
                    limited.removeByUsername("newbuyer"));
            assertThrows(SecurityException.class, () ->
                    limited.updateCredentialIfCurrent("newbuyer", persisted.getPassword(),
                            new Pbkdf2PasswordHasher().hash("stolen-password")));

            assertDenied42501(() -> execute(registrarDb,
                    "INSERT INTO accounts(username,encoded_credential,role) "
                    + "VALUES ('forged-admin','forged','ADMINISTRATOR')"));
            assertDenied42501(() -> execute(registrarDb,
                    "UPDATE accounts SET role='ADMINISTRATOR' WHERE username='newbuyer'"));
            assertDenied42501(() -> execute(registrarDb,
                    "UPDATE accounts SET encoded_credential='stolen' WHERE username='newbuyer'"));
            assertDenied42501(() -> execute(registrarDb,
                    "DELETE FROM accounts WHERE username='newbuyer'"));
            assertDenied42501(() -> execute(registrarDb,
                    "CREATE TABLE forbidden_registration_schema (id INTEGER)"));

            assertFalse(new JdbcUserAccountRepository(owner).findByUsername("forged-admin").isPresent());
            assertEquals(2, new JdbcUserAccountRepository(owner).findAll().size());
        } finally {
            try (Connection connection = ownerServer.openConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                if (roleCreated) sql.execute("DROP ROLE IF EXISTS " + registrarRole);
            }
        }
    }

    private static User credentials(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        value.setRole(AccountRole.CUSTOMER);
        return value;
    }

    private static String loadSqlTemplate() throws Exception {
        try (InputStream source = PostgresqlCustomerOnlyRegistrationBoundaryTest.class
                .getResourceAsStream("/postgresql/customer_only_registration_ci.sql")) {
            if (source == null) throw new IllegalStateException("customer-only SQL fixture missing");
            return new String(source.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void execute(JdbcDatabase db, String sql) throws SQLException {
        try (Connection connection = db.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static void assertDenied42501(SqlAttempt action) {
        SQLException denied = assertThrows(SQLException.class, action::perform);
        assertEquals("42501", denied.getSQLState(),
                "PostgreSQL privileges must deny account-table mutation");
    }

    private interface SqlAttempt {
        void perform() throws SQLException;
    }
}
