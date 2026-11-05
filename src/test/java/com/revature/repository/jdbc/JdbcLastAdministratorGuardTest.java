package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Regression coverage for the JDBC account repository's transactional
 * final-administrator guard. Direct DBA SQL remains outside this boundary.
 */
@Tag("SECURITY")
class JdbcLastAdministratorGuardTest {

    @Test
    void cannotDeleteOrDemoteTheLastAdministrator() {
        JdbcUserAccountRepository repository = accounts("last_admin");
        repository.save(account("sole-admin", AccountRole.ADMINISTRATOR));
        repository.save(account("customer", AccountRole.CUSTOMER));

        assertThrows(SecurityException.class, () ->
                repository.removeByUsername("sole-admin"));
        assertThrows(SecurityException.class, () ->
                repository.updateRoleIfCurrent("sole-admin",
                        AccountRole.ADMINISTRATOR, AccountRole.CUSTOMER));
        assertEquals(AccountRole.ADMINISTRATOR,
                repository.findByUsername("sole-admin").orElseThrow(AssertionError::new).getRole());

        assertTrue(repository.removeByUsername("customer"));
        assertFalse(repository.removeByUsername("customer"));
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void canRemoveOneAdministratorButNotTheFinalRemainingAccount() {
        JdbcUserAccountRepository repository = accounts("multi_admin");
        repository.save(account("alpha", AccountRole.ADMINISTRATOR));
        repository.save(account("beta", AccountRole.ADMINISTRATOR));

        assertTrue(repository.updateRoleIfCurrent("alpha",
                AccountRole.ADMINISTRATOR, AccountRole.EMPLOYEE));
        assertThrows(SecurityException.class, () ->
                repository.removeByUsername("beta"));
        assertTrue(repository.removeByUsername("alpha"));
        assertEquals(AccountRole.ADMINISTRATOR,
                repository.findByUsername("beta").orElseThrow(AssertionError::new).getRole());
    }

    @Test
    void concurrentDeletionsCannotRemoveBothAdministrators() throws Exception {
        JdbcUserAccountRepository repository = accounts("parallel_admin");
        repository.save(account("alpha", AccountRole.ADMINISTRATOR));
        repository.save(account("beta", AccountRole.ADMINISTRATOR));

        ExecutorService workers = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> outcomes = new ArrayList<>();
        try {
            for (String username : new String[] {"alpha", "beta"}) {
                Callable<Boolean> delete = () -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("concurrency test did not start");
                    }
                    try {
                        return repository.removeByUsername(username);
                    } catch (SecurityException | IllegalStateException deniedOrSerializationConflict) {
                        return false;
                    }
                };
                outcomes.add(workers.submit(delete));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();

            int completedDeletes = 0;
            for (Future<Boolean> result : outcomes) {
                if (result.get(30, TimeUnit.SECONDS)) completedDeletes++;
            }
            assertEquals(1, completedDeletes, "only one administrator may be deleted");
            assertEquals(1L, repository.findAll().stream()
                    .filter(user -> user.getRole() == AccountRole.ADMINISTRATOR).count());
        } finally {
            workers.shutdownNow();
        }
    }

    private static JdbcUserAccountRepository accounts(String prefix) {
        return new JdbcUserAccountRepository(
                JdbcDatabase.inMemory(prefix + "_" + System.nanoTime()));
    }

    private static User account(String username, AccountRole role) {
        User account = new User();
        account.setUsername(username);
        account.setPassword("fixture-encoded-credential");
        account.setRole(role);
        return account;
    }
}
