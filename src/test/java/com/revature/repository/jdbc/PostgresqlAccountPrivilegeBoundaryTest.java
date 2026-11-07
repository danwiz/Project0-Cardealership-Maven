package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * Disposable PostgreSQL16 database-grant regression.
 * Tests a limited ordinary runtime identity, NOT production role wiring.
 * The owner account remains privileged: a separate operator-only trusted
 * database writer boundary must still be independently designed and reviewed.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlAccountPrivilegeBoundaryTest {
    @Test
    @Timeout(60)
    void ordinaryReadOnlyRuntimeCannotPromoteDeleteOrCreateAccounts() throws Exception {
        String base = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "only the isolated CI PostgreSQL16 fixture is authorized");

        String token = UUID.randomUUID().toString().replace("-", "");
        String schema = "w20_" + token;
        String readonlyRole = "w20reader_" + token;
        String readonlyPassword = "synthetic-readonly-fixture-only";
        JdbcDatabase ownerServer = new JdbcDatabase(base, ownerName, ownerPassword);
        boolean roleCreated = false;
        try {
            try (Connection connection = ownerServer.openConnection();
                    Statement sql = connection.createStatement()) {
                try (ResultSet rs = sql.executeQuery("SHOW server_version_num")) {
                    assertTrue(rs.next());
                    int version = Integer.parseInt(rs.getString(1));
                    assertTrue(version >= 160000 && version < 170000,
                            "actual PostgreSQL16 required");
                }
                sql.execute("CREATE SCHEMA " + schema);
                sql.execute("CREATE ROLE " + readonlyRole
                        + " LOGIN PASSWORD '" + readonlyPassword + "'");
                roleCreated = true;
            }

            String scoped = base + "?currentSchema=" + schema;
            JdbcDatabase owner = new JdbcDatabase(scoped, ownerName, ownerPassword);
            owner.migrate();
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(owner);
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "root", "root-password");
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "backup", "backup-password");
            TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "buyer-password");

            try (Connection connection = ownerServer.openConnection();
                    Statement sql = connection.createStatement()) {
                connection.setAutoCommit(false);
                try {
                    sql.execute("REVOKE ALL ON SCHEMA " + schema + " FROM PUBLIC");
                    sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + readonlyRole);
                    sql.execute("GRANT SELECT ON " + schema + ".schema_history, "
                            + schema + ".accounts TO " + readonlyRole);
                    connection.commit();
                } catch (SQLException error) {
                    connection.rollback();
                    throw error;
                }
            }
            JdbcDatabase reader = new JdbcDatabase(scoped, readonlyRole, readonlyPassword);
            assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, reader.schemaVersion());
            JdbcUserAccountRepository readerAccounts = new JdbcUserAccountRepository(reader);

            User actor = new User();
            actor.setUsername("root");
            actor.setPassword("root-password");
            actor.setRole(AccountRole.ADMINISTRATOR);

            UserLoginService normalRuntime = new UserLoginService(readerAccounts, null);
            assertTrue(normalRuntime.authenticate(actor).isPresent(),
                    "limited SELECT access must allow credential verification");
            assertThrows(SecurityException.class,
                    () -> normalRuntime.changeRole(
                            actor, "buyer", AccountRole.CUSTOMER, AccountRole.EMPLOYEE),
                    "ordinary runtime has no trusted admin mutation capability");
            assertThrows(SecurityException.class,
                    () -> normalRuntime.removeUser(actor, "buyer"));
            assertThrows(SecurityException.class,
                    () -> normalRuntime.provisionUser(actor, "forged", "forged-password",
                            AccountRole.ADMINISTRATOR));

            assertDenied42501(() -> execute(reader,
                    "UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = 'buyer'"));
            assertDenied42501(() -> execute(reader,
                    "UPDATE accounts SET encoded_credential = 'forged' WHERE username = 'buyer'"));
            assertDenied42501(() -> execute(reader,
                    "DELETE FROM accounts WHERE username = 'buyer'"));
            assertDenied42501(() -> execute(reader,
                    "INSERT INTO accounts(username, encoded_credential, role) "
                    + "VALUES('forged', 'hash', 'ADMINISTRATOR')"));
            assertDenied42501(() -> execute(reader,
                    "CREATE TABLE forged_account_table (id INTEGER)"));

            assertEquals(AccountRole.CUSTOMER, accounts.findByUsername("buyer")
                    .orElseThrow(AssertionError::new).getRole());
            assertEquals(3, accounts.findAll().size());
        } finally {
            try (Connection connection = ownerServer.openConnection();
                    Statement sql = connection.createStatement()) {
                sql.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
                if (roleCreated) sql.execute("DROP ROLE IF EXISTS " + readonlyRole);
            }
        }
    }

    private static void execute(JdbcDatabase db, String query) throws SQLException {
        try (Connection connection = db.openConnection();
                PreparedStatement statement = connection.prepareStatement(query)) {
            statement.execute();
        }
    }

    private static void assertDenied42501(SqlAttempt operation) {
        SQLException exception = assertThrows(SQLException.class, operation::perform);
        assertEquals("42501", exception.getSQLState(),
                "lack of privilege must be enforced by PostgreSQL, not merely Java");
    }

    private interface SqlAttempt {
        void perform() throws SQLException;
    }
}
