package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.AtomicCredentialRequestQuota;
import com.revature.service.CredentialRequestQuota;
import com.revature.service.TrustedCredentialRequestHandler;
import com.revature.service.TrustedCredentialRequestHandler.VerifiedPrincipal;
import com.revature.service.TrustedCredentialRotationService;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/** Exercises a simulated shared atomic store; no real provider is configured. */
class AtomicCredentialRequestQuotaTest {

    @Test
    void sharedBackendPersistsAdmittedBudgetAcrossReplicasAndRecreation() {
        AtomicLong now = new AtomicLong(1000);
        SharedFakeStore store = new SharedFakeStore(now);
        CredentialRequestQuota replicaA = quota(store, "ingress", 2);
        CredentialRequestQuota replicaB = quota(store, "ingress", 2);
        replicaA.acquire("trusted-client");
        replicaB.acquire("trusted-client");
        assertThrows(SecurityException.class,
                () -> quota(store, "ingress", 2).acquire("trusted-client"));
        now.addAndGet(60_000);
        quota(store, "ingress", 2).acquire("trusted-client");
    }

    @Test
    void independentNamespacesDoNotAllowOneBudgetToResetAnother() {
        SharedFakeStore store = new SharedFakeStore(new AtomicLong(1000));
        CredentialRequestQuota preVerification = quota(store, "ingress", 1);
        CredentialRequestQuota postVerification = quota(store, "principal", 1);
        preVerification.acquire("same-key");
        postVerification.acquire("same-key");
        assertThrows(SecurityException.class, () -> preVerification.acquire("same-key"));
        assertThrows(SecurityException.class, () -> postVerification.acquire("same-key"));
    }

    @Test
    void simultaneousWorkersAcrossAdaptersCannotExceedAtomicLimit() throws Exception {
        SharedFakeStore store = new SharedFakeStore(new AtomicLong(1000));
        CredentialRequestQuota a = quota(store, "principal", 4);
        CredentialRequestQuota b = quota(store, "principal", 4);
        ExecutorService workers = Executors.newFixedThreadPool(10);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                final CredentialRequestQuota quota = i % 2 == 0 ? a : b;
                results.add(workers.submit((Callable<Boolean>) () -> {
                    release.await();
                    try {
                        quota.acquire("subject-A");
                        return true;
                    } catch (SecurityException denied) {
                        return false;
                    }
                }));
            }
            release.countDown();
            int admitted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) admitted++;
            }
            assertEquals(4, admitted);
        } finally {
            workers.shutdownNow();
        }
    }

    @Test
    void denialBackendOutageAndInvalidInputAlwaysFailClosed() {
        AtomicCredentialRequestQuota deny = new AtomicCredentialRequestQuota(
                (namespace, key, limit, window) -> false, "ingress", 1, 1000);
        assertThrows(SecurityException.class, () -> deny.acquire("valid-key"));
        AtomicCredentialRequestQuota outage = new AtomicCredentialRequestQuota(
                (namespace, key, limit, window) -> {
                    throw new IllegalStateException("sensitive-provider-diagnostic");
                }, "principal", 1, 1000);
        SecurityException unavailable = assertThrows(SecurityException.class,
                () -> outage.acquire("valid-key"));
        assertEquals("credential request temporarily unavailable", unavailable.getMessage());
        assertFalse(unavailable.getMessage().contains("sensitive-provider-diagnostic"));
        assertThrows(SecurityException.class, () -> outage.acquire(null));
        assertThrows(SecurityException.class, () -> outage.acquire("   "));
        assertThrows(SecurityException.class, () -> outage.acquire(" forged-key"));
        assertThrows(SecurityException.class, () -> outage.acquire("forged\nkey"));
        assertThrows(IllegalArgumentException.class,
                () -> new AtomicCredentialRequestQuota((n, k, l, w) -> true,
                        "../unsafe", 1, 1000));
        assertThrows(IllegalArgumentException.class,
                () -> new AtomicCredentialRequestQuota((n, k, l, w) -> true,
                        "ingress", 0, 1000));
    }

    @Test
    void requestHandlerCanEnforceSharedIngressBudgetAcrossTwoInstances() {
        AtomicLong now = new AtomicLong(1000);
        SharedFakeStore store = new SharedFakeStore(now);
        Fixture f = fixture(store, 2, 5);
        assertThrows(SecurityException.class,
                () -> f.replicaA.rotate("bad", "first-alice", "wrong"));
        assertTrue(f.replicaB.rotate("valid", "first-alice", "second-alice"));
        assertThrows(SecurityException.class,
                () -> f.replicaA.rotate("valid", "second-alice", "third-alice"));
        assertTrue(new UserLoginService(f.reader)
                .authenticate(credentials("alice", "second-alice")).isPresent());
    }

    @Test
    void requestHandlerCanEnforceSharedPrincipalBudgetAcrossTwoInstances() {
        SharedFakeStore store = new SharedFakeStore(new AtomicLong(1000));
        Fixture f = fixture(store, 8, 1);
        assertThrows(SecurityException.class,
                () -> f.replicaA.rotate("valid", "wrong-password", "ignored"));
        assertThrows(SecurityException.class,
                () -> f.replicaB.rotate("valid", "first-alice", "second-alice"));
        assertTrue(new UserLoginService(f.reader)
                .authenticate(credentials("alice", "first-alice")).isPresent());
    }

    private static CredentialRequestQuota quota(SharedFakeStore store, String namespace,
            int attempts) {
        return new AtomicCredentialRequestQuota(store, namespace, attempts, 60_000);
    }

    private static Fixture fixture(SharedFakeStore store, int ingressAttempts,
            int principalAttempts) {
        JdbcDatabase db = JdbcDatabase.inMemory("atomic_quota_" +
                UUID.randomUUID().toString().replace("-", ""));
        JdbcUserAccountRepository reader = new JdbcUserAccountRepository(db);
        JdbcUserAccountRepository writer = new JdbcUserAccountRepository(db);
        TestAccounts.seed(writer, AccountRole.CUSTOMER, "alice", "first-alice");
        TrustedCredentialRotationService rotation = new TrustedCredentialRotationService(reader, writer);
        TrustedCredentialRequestHandler.PrincipalVerifier verifier = bearer -> {
            if ("valid".equals(bearer)) return new VerifiedPrincipal("subject-alice", "alice");
            throw new SecurityException("test token denied");
        };
        CredentialRequestQuota ingressA = quota(store, "ingress", ingressAttempts);
        CredentialRequestQuota ingressB = quota(store, "ingress", ingressAttempts);
        CredentialRequestQuota principalA = quota(store, "principal", principalAttempts);
        CredentialRequestQuota principalB = quota(store, "principal", principalAttempts);
        TrustedCredentialRequestHandler a = new TrustedCredentialRequestHandler(
                () -> "trusted-client-A", ingressA, verifier, principalA, rotation);
        TrustedCredentialRequestHandler b = new TrustedCredentialRequestHandler(
                () -> "trusted-client-A", ingressB, verifier, principalB, rotation);
        return new Fixture(reader, a, b);
    }

    private static com.revature.cardealer.User credentials(String name, String password) {
        com.revature.cardealer.User user = new com.revature.cardealer.User();
        user.setUsername(name);
        user.setPassword(password);
        return user;
    }

    private static final class Fixture {
        private final JdbcUserAccountRepository reader;
        private final TrustedCredentialRequestHandler replicaA;
        private final TrustedCredentialRequestHandler replicaB;
        private Fixture(JdbcUserAccountRepository reader,
                TrustedCredentialRequestHandler replicaA,
                TrustedCredentialRequestHandler replicaB) {
            this.reader = reader;
            this.replicaA = replicaA;
            this.replicaB = replicaB;
        }
    }

    /** Test-only atomics: not durable, independently operated, or deployable. */
    private static final class SharedFakeStore implements AtomicCredentialRequestQuota.AtomicQuotaStore {
        private final Map<String, Window> windows = new HashMap<>();
        private final AtomicLong now;
        private SharedFakeStore(AtomicLong now) { this.now = now; }

        @Override
        public synchronized boolean tryAcquire(String namespace, String key,
                int maxAttempts, long windowMillis) {
            String composite = namespace + ":" + key;
            long currentTime = now.get();
            Window w = windows.get(composite);
            if (w == null || (currentTime >= w.startedAt
                    && currentTime - w.startedAt >= windowMillis)) {
                w = new Window(currentTime);
                windows.put(composite, w);
            }
            if (w.charges >= maxAttempts) return false;
            w.charges++;
            return true;
        }

        private static final class Window {
            private final long startedAt;
            private int charges;
            private Window(long startedAt) { this.startedAt = startedAt; }
        }
    }
}
