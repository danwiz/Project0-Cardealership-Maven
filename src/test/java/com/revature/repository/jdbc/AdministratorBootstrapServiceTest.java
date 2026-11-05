package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.revature.application.AdministratorBootstrapCli;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.AdminLoginService;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

@Tag("SECURITY")
class AdministratorBootstrapServiceTest {

    @Test
    void commandLineRequiresExplicitOperatorFlag() {
        assertThrows(SecurityException.class, () -> AdministratorBootstrapCli.main(new String[0]));
        assertThrows(SecurityException.class, () -> AdministratorBootstrapCli.main(new String[] {"customer"}));
    }

    @Test
    void fileBackedDatabaseRemembersSingleUseAfterRestart(@TempDir Path directory) throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("dealership").toAbsolutePath()
                + ";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_ON_EXIT=FALSE";
        JdbcDatabase first = new JdbcDatabase(url);
        new AdministratorBootstrapService(first).bootstrap("owner", "private-password-123".toCharArray());

        JdbcUserAccountRepository original = new JdbcUserAccountRepository(first);
        User saved = original.findByUsername("owner").orElseThrow(AssertionError::new);
        assertEquals(AccountRole.ADMINISTRATOR, saved.getRole());
        assertNotEquals("private-password-123", saved.getPassword());
        assertTrue(new AdminLoginService(original)
                .authenticate(credentials("owner", "private-password-123")).isPresent());

        JdbcDatabase restarted = new JdbcDatabase(url);
        restarted.migrate();
        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, restarted.schemaVersion());
        assertEquals(1, count(restarted, "administrator_bootstrap_state"));
        assertThrows(SecurityException.class,
                () -> new AdministratorBootstrapService(restarted)
                        .bootstrap("second", "different-password-456".toCharArray()));
        assertEquals(1, count(restarted, "accounts"));
    }

    @Test
    void missingAdministratorDoesNotResetCompletedBootstrap() throws Exception {
        JdbcDatabase database = freshDatabase();
        AdministratorBootstrapService service = new AdministratorBootstrapService(database);
        service.bootstrap("first", "very-strong-password".toCharArray());
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
        // Supported repository APIs now preserve the final administrator.
        assertThrows(SecurityException.class, () -> accounts.removeByUsername("first"));

        // Model an out-of-band privileged DBA action or damaged recovery state.
        // The single-use bootstrap marker must still block reinitialization.
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement()) {
            assertEquals(1, statement.executeUpdate(
                    "DELETE FROM accounts WHERE username = 'first'"));
        }

        assertThrows(SecurityException.class,
                () -> service.bootstrap("replacement", "another-strong-password".toCharArray()));
        assertEquals(1, count(database, "administrator_bootstrap_state"));
        assertEquals(0, count(database, "accounts"));
    }

    @Test
    void existingAdministratorPreventsBootstrapAndLeavesNoMarker() throws Exception {
        JdbcDatabase database = freshDatabase();
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
        TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "existing", "preexisting-password");

        assertThrows(SecurityException.class,
                () -> new AdministratorBootstrapService(database)
                        .bootstrap("new-admin", "another-strong-password".toCharArray()));
        assertEquals(0, count(database, "administrator_bootstrap_state"));
        assertEquals(1, count(database, "accounts"));
    }

    @Test
    void duplicateUsernameRollsBackAndAllowsSafeRetry() throws Exception {
        JdbcDatabase database = freshDatabase();
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
        new UserLoginService(accounts).registerUser("taken", "customer-password");

        assertThrows(SecurityException.class,
                () -> new AdministratorBootstrapService(database)
                        .bootstrap("taken", "a-strong-password".toCharArray()));
        assertEquals(0, count(database, "administrator_bootstrap_state"));
        assertEquals(AccountRole.CUSTOMER, accounts.findByUsername("taken").orElseThrow(AssertionError::new).getRole());

        new AdministratorBootstrapService(database).bootstrap("first-admin", "even-stronger-password".toCharArray());
        assertEquals(1, count(database, "administrator_bootstrap_state"));
        assertEquals(2, count(database, "accounts"));
    }

    @Test
    void validatesBeforeCreatingAnyAdministratorOrMarker() throws Exception {
        JdbcDatabase database = freshDatabase();
        AdministratorBootstrapService service = new AdministratorBootstrapService(database);
        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap("admin", "short".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap(" admin ", "valid-password-length".toCharArray()));
        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap("admin", null));
        assertThrows(IllegalArgumentException.class,
                () -> service.bootstrap("admin", "              ".toCharArray()));
        assertEquals(0, count(database, "accounts"));
        assertEquals(0, count(database, "administrator_bootstrap_state"));
    }

    @Test
    void concurrentInitializationsCannotCreateTwoAdministrators() throws Exception {
        JdbcDatabase database = freshDatabase();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (int i = 0; i < 2; i++) {
                final String account = "administrator-" + i;
                Callable<Boolean> attempt = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("bootstrap test did not start");
                    try {
                        new AdministratorBootstrapService(database)
                                .bootstrap(account, "secure-long-password".toCharArray());
                        return true;
                    } catch (SecurityException | IllegalStateException expectedRaceLoss) {
                        return false;
                    }
                };
                outcomes.add(pool.submit(attempt));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int succeeded = 0;
            for (Future<Boolean> result : outcomes) if (result.get(30, TimeUnit.SECONDS)) succeeded++;
            assertEquals(1, succeeded);
            assertEquals(1, count(database, "accounts"));
            assertEquals(1, count(database, "administrator_bootstrap_state"));
        } finally {
            pool.shutdownNow();
        }
    }

    private static JdbcDatabase freshDatabase() {
        JdbcDatabase database = JdbcDatabase.inMemory("bootstrap_" + System.nanoTime());
        database.migrate();
        return database;
    }

    private static int count(JdbcDatabase database, String table) throws Exception {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static User credentials(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }
}
