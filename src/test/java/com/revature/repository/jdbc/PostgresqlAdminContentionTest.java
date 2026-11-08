package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/**
 * Disposable real PostgreSQL 16 concurrency acceptance cases.
 *
 * Invariant A: a writer holding actor authority cannot be invalidated
 * retroactively; any later revocation must wait for the writer to commit.
 * Invariant B: competing administrator demotions never remove the last admin.
 * No user/production database or deployment is accessed.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlAdminContentionTest {

    @Test
    @Timeout(value = 80, unit = TimeUnit.SECONDS)
    void actorLockFirstOrdersAuthorizedWriteBeforeWaitingRevocation() throws Exception {
        withFixture("order", database -> {
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "root", "root-password");
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "backup", "backup-password");
            TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "buyer-password");

            String writerApp = "w19write_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            String revokeApp = "w19revoke_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            JdbcDatabase writerDb = taggedDatabase(database, writerApp);
            JdbcDatabase revokerDb = taggedDatabase(database, revokeApp);
            UserLoginService service = new UserLoginService(
                    new JdbcUserAccountRepository(writerDb),
                    new JdbcUserAccountRepository(writerDb));
            User credentials = credentials("root", "root-password");

            ExecutorService workers = Executors.newFixedThreadPool(2);
            try (Connection holdTarget = database.openConnection()) {
                holdTarget.setAutoCommit(false);
                try (PreparedStatement lock = holdTarget.prepareStatement(
                        "SELECT username FROM accounts WHERE username = ? FOR UPDATE")) {
                    lock.setString(1, "buyer");
                    try (ResultSet rs = lock.executeQuery()) {
                        assertTrue(rs.next());
                    }
                }

                Future<Boolean> write = workers.submit(() ->
                        service.changeRole(credentials, "buyer",
                                AccountRole.CUSTOMER, AccountRole.EMPLOYEE));

                try {
                    assertTrue(awaitLockWait(database, writerApp, 22),
                            "authorized writer must first acquire root and then block on buyer row");

                    Future<Integer> revoke = workers.submit(() -> {
                        try (Connection connection = revokerDb.openConnection();
                                PreparedStatement statement = connection.prepareStatement(
                                        "UPDATE accounts SET role = ? WHERE username = ? AND role = ?")) {
                            statement.setString(1, AccountRole.CUSTOMER.name());
                            statement.setString(2, "root");
                            statement.setString(3, AccountRole.ADMINISTRATOR.name());
                            return statement.executeUpdate();
                        }
                    });

                    assertTrue(awaitLockWait(database, revokeApp, 22),
                            "revoker must wait on administrator row held by writer");

                    holdTarget.commit();
                    assertTrue(write.get(24, TimeUnit.SECONDS),
                            "privileged mutation legitimately linearized before revocation");
                    assertEquals(1, revoke.get(24, TimeUnit.SECONDS).intValue(),
                            "later revocation must commit after the authorized write");
                } finally {
                    holdTarget.rollback();
                }
            } finally {
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20, TimeUnit.SECONDS));
            }
            assertEquals(AccountRole.EMPLOYEE,
                    accounts.findByUsername("buyer").orElseThrow(AssertionError::new).getRole());
            assertEquals(AccountRole.CUSTOMER,
                    accounts.findByUsername("root").orElseThrow(AssertionError::new).getRole());
            assertFalse(new UserLoginService(accounts, accounts)
                    .authenticate(credentials).filter(u -> u.getRole() == AccountRole.ADMINISTRATOR).isPresent(),
                    "revoked root cannot authenticate with current administrator role");
        });
    }

    @Test
    @Timeout(value = 80, unit = TimeUnit.SECONDS)
    void competingAdministratorsCannotBothDemoteTheFinalAdministrators() throws Exception {
        withFixture("last", database -> {
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "root", "root-password");
            TestAccounts.seed(accounts, AccountRole.ADMINISTRATOR, "backup", "backup-password");
            CountDownLatch start = new CountDownLatch(1);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                Future<String> root = workers.submit(() -> attemptDemotion(
                        new JdbcUserAccountRepository(database), credentials("root","root-password"),
                        "backup",start));
                Future<String> backup = workers.submit(() -> attemptDemotion(
                        new JdbcUserAccountRepository(database), credentials("backup","backup-password"),
                        "root",start));
                start.countDown();

                String rootResult = root.get(28,TimeUnit.SECONDS);
                String backupResult = backup.get(28,TimeUnit.SECONDS);
                assertTrue(isValidOutcome(rootResult), "Unexpected root outcome: " + rootResult);
                assertTrue(isValidOutcome(backupResult), "Unexpected backup outcome: " + backupResult);

                long remaining = accounts.findAll().stream()
                        .filter(x -> x.getRole() == AccountRole.ADMINISTRATOR).count();
                assertTrue(remaining >= 1,
                        "two competing administrative demotions must not remove the final administrator");
                assertTrue(remaining <= 2);
                assertEquals(2, accounts.findAll().size(), "no account may disappear");
            } finally {
                start.countDown();
                workers.shutdownNow();
                assertTrue(workers.awaitTermination(20,TimeUnit.SECONDS));
            }
        });
    }

    private static String attemptDemotion(JdbcUserAccountRepository repository,
            User actor, String target, CountDownLatch start) throws Exception {
        if (!start.await(15,TimeUnit.SECONDS)) return "INVALID: start barrier timeout";
        try {
            boolean changed = new UserLoginService(repository,repository).changeRole(actor,target,
                    AccountRole.ADMINISTRATOR,AccountRole.CUSTOMER);
            return changed ? "COMMITTED" : "CAS_REJECTED";
        } catch (SecurityException expected) {
            return "DENIED";
        } catch (IllegalStateException expected) {
            if (hasSqlState(expected,"40001") || hasSqlState(expected,"40P01")) {
                return "SQL_TRANSACTION_ABORT";
            }
            throw expected;
        }
    }

    private static boolean isValidOutcome(String outcome) {
        return "COMMITTED".equals(outcome) || "CAS_REJECTED".equals(outcome)
                || "DENIED".equals(outcome) || "SQL_TRANSACTION_ABORT".equals(outcome);
    }

    private static boolean hasSqlState(Throwable exception,String wanted) {
        for (Throwable current=exception; current != null; current=current.getCause()) {
            if (current instanceof SQLException
                    && wanted.equals(((SQLException)current).getSQLState())) return true;
        }
        return false;
    }

    private static User credentials(String name,String password) {
        User actor=new User();
        actor.setUsername(name);
        actor.setPassword(password);
        actor.setRole(AccountRole.ADMINISTRATOR);
        return actor;
    }

    private static JdbcDatabase taggedDatabase(JdbcDatabase original,String applicationName) {
        // Fixture scope is immutable and generated in this class; no production URL.
        return new JdbcDatabase(workingUrl + "&ApplicationName=" + applicationName,
                fixtureUser,fixturePass);
    }

    private static String workingUrl;
    private static String fixtureUser;
    private static String fixturePass;

    private interface FixtureWork { void execute(JdbcDatabase database) throws Exception; }

    private static synchronized void withFixture(String label, FixtureWork work) throws Exception {
        String url=System.getenv("DEALERSHIP_PG_TEST_URL");
        String name=System.getenv("DEALERSHIP_PG_TEST_USER");
        String password=System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                && "dealership_ci_owner".equals(name)
                && "ci-only-postgres-fixture".equals(password),
                "restricted to the disposable PostgreSQL16 GitHub fixture");
        String schema="w19_"+label+"_"+UUID.randomUUID().toString().replace("-","");
        JdbcDatabase owner=new JdbcDatabase(url,name,password);
        try (Connection connection=owner.openConnection();
                Statement stmt=connection.createStatement()) {
            try (ResultSet rs=stmt.executeQuery("SHOW server_version_num")) {
                assertTrue(rs.next());
                int version=Integer.parseInt(rs.getString(1));
                assertTrue(version>=160000 && version<170000,"PostgreSQL16 required");
            }
            stmt.execute("CREATE SCHEMA "+schema);
        }
        String schemaUrl=url+"?currentSchema="+schema;
        JdbcDatabase database=new JdbcDatabase(schemaUrl,name,password);
        workingUrl=schemaUrl;fixtureUser=name;fixturePass=password;
        try {
            database.migrate();
            work.execute(database);
        } finally {
            workingUrl=null;fixtureUser=null;fixturePass=null;
            try(Connection connection=owner.openConnection();
                    Statement stmt=connection.createStatement()) {
                stmt.execute("DROP SCHEMA IF EXISTS "+schema+" CASCADE");
            }
        }
    }

    private static boolean awaitLockWait(JdbcDatabase owner,String app,int seconds)
            throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(seconds);
        while(System.nanoTime()<deadline) {
            try(Connection c=owner.openConnection();
                    PreparedStatement stmt=c.prepareStatement(
                            "SELECT count(*) FROM pg_stat_activity WHERE application_name = ? "
                            +"AND wait_event_type = 'Lock'")) {
                stmt.setString(1,app);
                try(ResultSet rs=stmt.executeQuery()) {
                    assertTrue(rs.next());
                    if(rs.getInt(1)>0)return true;
                }
            }
            Thread.sleep(70);
        }
        return false;
    }
}
