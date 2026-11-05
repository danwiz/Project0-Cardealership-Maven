package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
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
import com.revature.service.CredentialRequestRateLimiter;
import com.revature.service.TrustedCredentialRequestHandler;
import com.revature.service.TrustedCredentialRequestHandler.VerifiedPrincipal;
import com.revature.service.TrustedCredentialRotationService;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/** Java seam tests: fixture verifier is NOT a production token validator. */
class TrustedCredentialRequestHandlerTest {

    @Test
    void verifiedIdentitySelectsTheOnlyCredentialTarget() {
        Fixture fixture = fixture(5, new AtomicLong(1000));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("forged-token", "first-alice", "fraudulent"));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate(null, "first-alice", "fraudulent"));
        assertTrue(fixture.handler.rotate("valid-alice", "first-alice", "second-alice"));
        UserLoginService login = new UserLoginService(fixture.reader);
        assertTrue(login.authenticate(credentials("alice", "second-alice")).isPresent());
        assertTrue(login.authenticate(credentials("bob", "first-bob")).isPresent());
        assertFalse(login.authenticate(credentials("bob", "second-alice")).isPresent());
    }

    @Test
    void unsuccessfulAttemptsConsumeQuotaAndOnlyWindowExpiryReleasesIt() {
        AtomicLong now = new AtomicLong(2000);
        Fixture fixture = fixture(2, now);
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("valid-alice", "wrong", "ignored"));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("valid-alice", "wrong", "ignored"));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("valid-alice", "first-alice", "second-alice"));
        now.addAndGet(15 * 60 * 1000L);
        assertTrue(fixture.handler.rotate("valid-alice", "first-alice", "second-alice"));
    }

    @Test
    void missingAndInvalidBearersConsumeAnIndependentIngressBudget() {
        AtomicLong now = new AtomicLong(3000);
        Fixture fixture = fixture(5, 2, now);
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate(null, "first-alice", "second-alice"));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("forged-token", "first-alice", "second-alice"));
        assertThrows(SecurityException.class,
                () -> fixture.handler.rotate("valid-alice", "first-alice", "second-alice"));
        assertTrue(new UserLoginService(fixture.reader)
                .authenticate(credentials("alice", "first-alice")).isPresent());
        now.addAndGet(15 * 60 * 1000L);
        assertTrue(fixture.handler.rotate("valid-alice", "first-alice", "second-alice"));
    }

    @Test
    void absentTrustedIngressIdentityFailsClosedBeforeTokenVerification() {
        AtomicLong now = new AtomicLong(1000);
        Fixture fixture = fixture(3, now);
        TrustedCredentialRequestHandler unsafeIngress = new TrustedCredentialRequestHandler(
                () -> null,
                new CredentialRequestRateLimiter(3, 60_000, 4, now::get),
                bearer -> { throw new AssertionError("verifier must not be reached"); },
                new CredentialRequestRateLimiter(3, 60_000, 4, now::get),
                new TrustedCredentialRotationService(fixture.reader,
                        new JdbcUserAccountRepository(JdbcDatabase.inMemory("unused_writer"))));
        assertThrows(SecurityException.class,
                () -> unsafeIngress.rotate("valid-alice", "first-alice", "second-alice"));
    }

    @Test
    void boundOnTrackedSubjectsFailsClosedAndExpiresStaleWindows() {
        AtomicLong now = new AtomicLong(5000);
        CredentialRequestRateLimiter limiter = new CredentialRequestRateLimiter(
                2, 1000, 2, now::get);
        limiter.acquire("sub-a");
        limiter.acquire("sub-b");
        assertThrows(SecurityException.class, () -> limiter.acquire("sub-c"));
        now.addAndGet(1000);
        limiter.acquire("sub-c");
        now.set(100); // Backward clock movement must not reset the bucket.
        limiter.acquire("sub-c");
        assertThrows(SecurityException.class, () -> limiter.acquire("sub-c"));
    }

    @Test
    void concurrentAttemptsCannotExceedOneSubjectsQuota() throws Exception {
        CredentialRequestRateLimiter limiter = new CredentialRequestRateLimiter(
                3, 60_000, 8, () -> 1000L);
        ExecutorService workers = Executors.newFixedThreadPool(8);
        CountDownLatch release = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                results.add(workers.submit((Callable<Boolean>) () -> {
                    release.await();
                    try {
                        limiter.acquire("same-verified-subject");
                        return true;
                    } catch (SecurityException denied) {
                        return false;
                    }
                }));
            }
            release.countDown();
            int accepted = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) accepted++;
            }
            assertEquals(3, accepted);
        } finally {
            workers.shutdownNow();
        }
    }

    private static Fixture fixture(int maxAttempts, AtomicLong now) {
        return fixture(maxAttempts, 100, now);
    }

    private static Fixture fixture(int maxAttempts, int ingressAttempts, AtomicLong now) {
        JdbcDatabase db = JdbcDatabase.inMemory("gate_" +
                UUID.randomUUID().toString().replace("-", ""));
        JdbcUserAccountRepository reader = new JdbcUserAccountRepository(db);
        JdbcUserAccountRepository writer = new JdbcUserAccountRepository(db);
        TestAccounts.seed(writer, AccountRole.CUSTOMER, "alice", "first-alice");
        TestAccounts.seed(writer, AccountRole.CUSTOMER, "bob", "first-bob");
        TrustedCredentialRotationService rotation = new TrustedCredentialRotationService(
                reader, writer);
        TrustedCredentialRequestHandler.PrincipalVerifier verifier = bearer -> {
            if ("valid-alice".equals(bearer)) {
                return new VerifiedPrincipal("stable-subject-alice", "alice");
            }
            if ("valid-bob".equals(bearer)) {
                return new VerifiedPrincipal("stable-subject-bob", "bob");
            }
            throw new SecurityException("invalid token fixture");
        };
        CredentialRequestRateLimiter limiter = new CredentialRequestRateLimiter(
                maxAttempts, 15 * 60 * 1000L, 20, now::get);
        CredentialRequestRateLimiter ingressLimiter = new CredentialRequestRateLimiter(
                ingressAttempts, 15 * 60 * 1000L, 20, now::get);
        return new Fixture(reader,
                new TrustedCredentialRequestHandler(
                        () -> "trusted-ingress-client-A", ingressLimiter, verifier,
                        limiter, rotation));
    }

    private static com.revature.cardealer.User credentials(String name, String password) {
        com.revature.cardealer.User user = new com.revature.cardealer.User();
        user.setUsername(name);
        user.setPassword(password);
        return user;
    }

    private static final class Fixture {
        private final JdbcUserAccountRepository reader;
        private final TrustedCredentialRequestHandler handler;
        private Fixture(JdbcUserAccountRepository reader, TrustedCredentialRequestHandler handler) {
            this.reader = reader;
            this.handler = handler;
        }
    }
}
