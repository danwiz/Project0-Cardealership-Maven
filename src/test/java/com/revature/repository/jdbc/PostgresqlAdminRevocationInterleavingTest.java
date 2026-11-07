package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * Wave18: actual PostgreSQL16 two-connection row-lock interleavings.
 *
 * A separate connection holds and revokes the acting administrator's row
 * while a privileged operation waits at the same row lock. After the
 * revocation commits, the privileged operation must not change its target.
 * This verifies denial or SERIALIZABLE abort (SQLSTATE 40001), not production
 * networking, high availability, or complete administrator privilege grants.
 *
 * Runs ONLY in the disposable, known-identity GitHub PostgreSQL fixture.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlAdminRevocationInterleavingTest {
    private enum Operation { PROVISION, PROMOTE, DELETE }

    @Test
    @Timeout(value = 50, unit = TimeUnit.SECONDS)
    void revokedActorCannotProvisionAfterBlockedActorRowLock() throws Exception {
        provePostgresRevocationWins(Operation.PROVISION);
    }

    @Test
    @Timeout(value = 50, unit = TimeUnit.SECONDS)
    void revokedActorCannotPromoteAfterBlockedActorRowLock() throws Exception {
        provePostgresRevocationWins(Operation.PROMOTE);
    }

    @Test
    @Timeout(value = 50, unit = TimeUnit.SECONDS)
    void revokedActorCannotDeleteAfterBlockedActorRowLock() throws Exception {
        provePostgresRevocationWins(Operation.DELETE);
    }

    private static void provePostgresRevocationWins(Operation operation) throws Exception {
        String base = System.getenv("DEALERSHIP_PG_TEST_URL");
        String user = System.getenv("DEALERSHIP_PG_TEST_USER");
        String pass = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(base)
                        && "dealership_ci_owner".equals(user)
                        && "ci-only-postgres-fixture".equals(pass),
                "only disposable PostgreSQL16 CI fixture is authorized");

        String token = UUID.randomUUID().toString().replace("-", "");
        String schema = "w18_" + token;
        String applicationName = "w18actor_" + token.substring(0, 16);
        JdbcDatabase owner = new JdbcDatabase(base, user, pass);
        try (Connection connection = owner.openConnection();
                Statement statement = connection.createStatement()) {
            try (ResultSet result = statement.executeQuery(
                    "SELECT current_setting('server_version_num')::integer")) {
                assertTrue(result.next());
                int version = result.getInt(1);
                assertTrue(version >= 160000 && version < 170000,
                        "this test is explicitly scoped to PostgreSQL16");
            }
            statement.execute("CREATE SCHEMA " + schema);
        }

        ExecutorService workers = Executors.newSingleThreadExecutor();
        try {
            String scopedUrl = base + "?currentSchema=" + schema
                    + "&ApplicationName=" + applicationName;
            JdbcDatabase database = new JdbcDatabase(scopedUrl, user, pass);
            database.migrate();
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "root", "root-password");
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "backup", "backup-password");
            TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "buyer-password");

            String oldRevision = accounts.findByUsername("root")
                    .orElseThrow(AssertionError::new).getSessionRevision();
            UserLoginService service = new UserLoginService(accounts, accounts);
            User credentials = new User();
            credentials.setUsername("root");
            credentials.setPassword("root-password");
            credentials.setRole(AccountRole.ADMINISTRATOR);

            try (Connection revoker = database.openConnection()) {
                revoker.setAutoCommit(false);
                try {
                    // Hold the actor row lock before the high-level operation begins.
                    try (PreparedStatement lock = revoker.prepareStatement(
                            "SELECT username FROM accounts WHERE username = ? FOR UPDATE")) {
                        lock.setString(1, "root");
                        try (ResultSet result = lock.executeQuery()) {
                            assertTrue(result.next(), "fixture administrator must exist");
                        }
                    }

                    Future<?> attempt = workers.submit(() -> {
                        switch (operation) {
                            case PROVISION:
                                service.provisionUser(credentials, "employee", "employee-password",
                                        AccountRole.EMPLOYEE);
                                break;
                            case PROMOTE:
                                service.changeRole(credentials, "buyer",
                                        AccountRole.CUSTOMER, AccountRole.EMPLOYEE);
                                break;
                            case DELETE:
                                service.removeUser(credentials, "buyer");
                                break;
                            default:
                                throw new AssertionError("unsupported operation");
                        }
                    });

                    assertTrue(awaitPostgresLockWait(owner, applicationName, 20),
                            "the authenticated writer must reach a PostgreSQL lock wait");
                    try (PreparedStatement revoke = revoker.prepareStatement(
                            "UPDATE accounts SET role = ? WHERE username = ? AND role = ?")) {
                        revoke.setString(1, AccountRole.CUSTOMER.name());
                        revoke.setString(2, "root");
                        revoke.setString(3, AccountRole.ADMINISTRATOR.name());
                        assertEquals(1, revoke.executeUpdate(), "fixture revocation must succeed");
                    }
                    revoker.commit();
                    try {
                        attempt.get(20, TimeUnit.SECONDS);
                        fail("privileged operation must be denied after revocation committed");
                    } catch (ExecutionException expected) {
                        Throwable problem = expected.getCause();
                        assertTrue(problem instanceof SecurityException || isSerializationAbort(problem),
                                "expected current-role denial or SQLSTATE 40001, got " + problem);
                    }

                    User currentAdmin = accounts.findByUsername("root")
                            .orElseThrow(AssertionError::new);
                    assertEquals(AccountRole.CUSTOMER, currentAdmin.getRole());
                    assertNotEquals(oldRevision, currentAdmin.getSessionRevision(),
                            "server-side trigger must advance revocation generation");

                    switch (operation) {
                        case PROVISION:
                            assertFalse(accounts.findByUsername("employee").isPresent(),
                                    "revoked operator must not provision privileged account");
                            break;
                        case PROMOTE:
                            assertEquals(AccountRole.CUSTOMER, accounts.findByUsername("buyer")
                                    .orElseThrow(AssertionError::new).getRole());
                            break;
                        case DELETE:
                            assertTrue(accounts.findByUsername("buyer").isPresent(),
                                    "revoked operator must not delete customer");
                            break;
                        default:
                            throw new AssertionError("unsupported operation");
                    }
                } finally {
                    revoker.rollback();
                }
            }
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            // Only the unique synthetic schema is removed, never any user schema.
            try (Connection cleanup = owner.openConnection();
                    Statement statement = cleanup.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static boolean isSerializationAbort(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof SQLException
                    && "40001".equals(((SQLException) current).getSQLState())) {
                return true;
            }
        }
        return false;
    }

    private static boolean awaitPostgresLockWait(
            JdbcDatabase owner, String applicationName, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) {
            try (Connection observer = owner.openConnection();
                    PreparedStatement statement = observer.prepareStatement(
                            "SELECT COUNT(*) FROM pg_stat_activity "
                            + "WHERE application_name = ? AND wait_event_type = 'Lock'")) {
                statement.setString(1, applicationName);
                try (ResultSet result = statement.executeQuery()) {
                    assertTrue(result.next());
                    if (result.getInt(1) >= 1) return true;
                }
            }
            Thread.sleep(75);
        }
        return false;
    }
}
