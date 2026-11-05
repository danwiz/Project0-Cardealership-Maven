package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.User;

/** Protects the in-memory identity store's concurrent-read contract. */
@Tag("SECURITY")
class InMemoryUserAccountRepositoryConcurrencyTest {

    private static User user(String name) {
        User account = new User();
        account.setUsername(name);
        account.setPassword("encoded-fixture-only");
        return account;
    }

    @Test
    void allMapOperationsUseTheSameRepositoryMonitor() throws Exception {
        for (Method method : new Method[] {
                InMemoryUserAccountRepository.class.getMethod("save", User.class),
                InMemoryUserAccountRepository.class.getMethod("findByUsername", String.class),
                InMemoryUserAccountRepository.class.getMethod("removeByUsername", String.class),
                InMemoryUserAccountRepository.class.getMethod("findAll"),
                InMemoryUserAccountRepository.class.getMethod(
                        "updateCredentialIfCurrent", String.class, String.class, String.class),
                InMemoryUserAccountRepository.class.getMethod(
                        "updateRoleIfCurrent", String.class,
                        com.revature.cardealer.AccountRole.class,
                        com.revature.cardealer.AccountRole.class) }) {
            assertTrue(Modifier.isSynchronized(method.getModifiers()), method.getName());
        }
    }

    @Test
    void directWritesRejectBlankAndDuplicateUsernamesWithoutChangingExistingIdentity() {
        InMemoryUserAccountRepository repository = new InMemoryUserAccountRepository();
        repository.save(user("existing"));
        assertThrows(IllegalArgumentException.class, () -> repository.save(user("  ")));
        assertThrows(IllegalArgumentException.class, () -> repository.save(user(null)));
        assertThrows(IllegalArgumentException.class, () -> repository.save(user("existing")));
        assertEquals(1, repository.findAll().size());
        assertEquals("encoded-fixture-only", repository.findByUsername("existing")
                .orElseThrow().getPassword());
    }

    @Test
    void concurrentAccountInsertsAndReadersReturnValidSnapshots() throws Exception {
        InMemoryUserAccountRepository repository = new InMemoryUserAccountRepository();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            Future<?> writer = executor.submit(() -> {
                await(start);
                for (int i = 0; i < 250; i++) {
                    repository.save(user("customer-" + i));
                }
            });
            Future<?>[] readers = new Future<?>[3];
            for (int i = 0; i < readers.length; i++) {
                readers[i] = executor.submit(() -> {
                    await(start);
                    for (int attempt = 0; attempt < 450; attempt++) {
                        List<User> snapshot = repository.findAll();
                        Set<String> names = new HashSet<>();
                        for (User found : snapshot) {
                            assertTrue(found.getUsername().startsWith("customer-"));
                            assertTrue(names.add(found.getUsername()));
                        }
                        assertTrue(snapshot.size() <= 250);
                        repository.findByUsername("customer-0");
                    }
                });
            }
            start.countDown();
            writer.get(20, TimeUnit.SECONDS);
            for (Future<?> reader : readers) {
                reader.get(20, TimeUnit.SECONDS);
            }
            assertEquals(250, repository.findAll().size());
            assertTrue(repository.removeByUsername("customer-0"));
            assertFalse(repository.findByUsername("customer-0").isPresent());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("concurrent test did not start");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }
}
