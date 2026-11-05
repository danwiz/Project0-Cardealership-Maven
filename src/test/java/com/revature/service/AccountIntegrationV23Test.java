package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/** Tests the short-chain account-race and session-generation invariants on the long model. */
@Tag("INTEGRATION-CANDIDATE-V23")
class AccountIntegrationV23Test {
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void concurrentSameNameCreationHasExactlyOneWinnerWithoutReplacingAccount() throws Exception {
        InMemoryUserAccountRepository repo = new InMemoryUserAccountRepository();
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = pool.submit(() -> register(repo, "first-hash", go));
            Future<Boolean> second = pool.submit(() -> register(repo, "second-hash", go));
            go.countDown();
            assertEquals(1, (first.get(6, TimeUnit.SECONDS) ? 1 : 0)
                    + (second.get(6, TimeUnit.SECONDS) ? 1 : 0));
            assertEquals(1, repo.findAll().size());
            User stored = repo.findByUsername("single-user").orElseThrow();
            assertTrue("first-hash".equals(stored.getPassword())
                    || "second-hash".equals(stored.getPassword()));
            assertTrue(stored.getSessionRevision() != null);
        } finally { pool.shutdownNow(); }
    }

    @Test
    void roleDemotionAndRestorationNeverReusesOriginalSessionRevision() {
        InMemoryUserAccountRepository repo = new InMemoryUserAccountRepository();
        User original = new User();
        original.setUsername("operator");
        original.setPassword("encoded-credential");
        original.setRole(AccountRole.ADMINISTRATOR);
        User first = repo.save(original);
        String firstRevision = first.getSessionRevision();
        assertTrue(repo.updateRoleIfCurrent("operator",
                AccountRole.ADMINISTRATOR, AccountRole.CUSTOMER));
        User demoted = repo.findByUsername("operator").orElseThrow();
        assertNotEquals(firstRevision, demoted.getSessionRevision());
        assertEquals(AccountRole.CUSTOMER, demoted.getRole());
        assertTrue(repo.updateRoleIfCurrent("operator",
                AccountRole.CUSTOMER, AccountRole.ADMINISTRATOR));
        User restored = repo.findByUsername("operator").orElseThrow();
        assertEquals(AccountRole.ADMINISTRATOR, restored.getRole());
        assertNotEquals(firstRevision, restored.getSessionRevision());
        assertNotEquals(demoted.getSessionRevision(), restored.getSessionRevision());
        assertFalse(repo.updateRoleIfCurrent("operator",
                AccountRole.CUSTOMER, AccountRole.ADMINISTRATOR));
    }

    private static boolean register(InMemoryUserAccountRepository repo, String credential,
            CountDownLatch go) throws InterruptedException {
        go.await();
        User user = new User();
        user.setUsername("single-user");
        user.setPassword(credential);
        user.setRole(AccountRole.CUSTOMER);
        try {
            repo.save(user);
            return true;
        } catch (IllegalArgumentException expectedDuplicate) {
            return false;
        }
    }
}
