package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.CredentialRequestRateLimiter;
import com.revature.service.TrustedCredentialRequestHandler;
import com.revature.service.TrustedCredentialRequestHandler.VerifiedPrincipal;
import com.revature.service.TrustedCredentialRotationService;
import com.revature.service.TrustedSessionPrincipalVerifier;
import com.revature.service.TrustedSessionPrincipalVerifier.SessionEvidence;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/** All identity sources below are fakes, not a cryptographic token verifier. */
class TrustedSessionPrincipalVerifierTest {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "credential-writer";
    private static final long NOW = 10_000;

    @Test
    void validRecentSessionResolvesCurrentAccountWithoutTokenUsername() {
        AtomicReference<SessionEvidence> evidence = new AtomicReference<>(valid());
        AtomicInteger lookups = new AtomicInteger();
        TrustedSessionPrincipalVerifier verifier = verifier(evidence::get, (issuer, subject) -> {
            lookups.incrementAndGet();
            assertEquals(ISSUER, issuer);
            assertEquals("subject-alice", subject);
            return "alice";
        }, () -> NOW);
        VerifiedPrincipal principal = verifier.verify("test-session");
        assertEquals("subject-alice", principal.subject());
        assertEquals("alice", principal.username());
        assertEquals(1, lookups.get());
    }

    @Test
    void incorrectIssuerAudienceAssuranceOrInactiveEvidenceNeverReachesBinding() {
        AtomicReference<SessionEvidence> evidence = new AtomicReference<>(valid());
        AtomicInteger lookups = new AtomicInteger();
        TrustedSessionPrincipalVerifier verifier = verifier(evidence::get, (issuer, subject) -> {
            lookups.incrementAndGet();
            return "alice";
        }, () -> NOW);
        SessionEvidence[] invalid = {
                session(false, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, 11000, 9900),
                session(true, "https://other.example.test", AUDIENCE, "subject-alice", "step-up", 9000, 11000, 9900),
                session(true, ISSUER, "public-client", "subject-alice", "step-up", 9000, 11000, 9900),
                session(true, ISSUER, AUDIENCE, "subject-alice", "weak", 9000, 11000, 9900),
                session(true, ISSUER, AUDIENCE, null, "step-up", 9000, 11000, 9900),
                session(true, ISSUER, AUDIENCE, " subject-alice", "step-up", 9000, 11000, 9900),
                session(true, ISSUER, AUDIENCE, "subject\nalice", "step-up", 9000, 11000, 9900)
        };
        for (SessionEvidence e : invalid) {
            evidence.set(e);
            assertThrows(SecurityException.class, () -> verifier.verify("test-session"));
        }
        assertEquals(0, lookups.get());
    }

    @Test
    void expiryNotBeforeAndAuthenticationFreshnessAreEnforcedAtBoundaries() {
        AtomicReference<SessionEvidence> evidence = new AtomicReference<>(valid());
        TrustedSessionPrincipalVerifier verifier = verifier(evidence::get,
                (issuer, subject) -> "alice", () -> NOW);
        SessionEvidence[] invalid = {
                session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, NOW, 9900),
                session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", NOW + 1, 11000, 9900),
                session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, 11000, 9699),
                session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, 11000, NOW + 1),
                session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, 11000, 0)
        };
        for (SessionEvidence e : invalid) {
            evidence.set(e);
            assertThrows(SecurityException.class, () -> verifier.verify("test-session"));
        }
        evidence.set(session(true, ISSUER, AUDIENCE, "subject-alice",
                "step-up", NOW, NOW + 1, NOW - 300));
        assertEquals("alice", verifier.verify("test-session").username());
    }

    @Test
    void missingDisabledOrAmbiguousAccountNeverBecomesCredentialTarget() {
        for (String account : new String[] {null, "", " ", " alice", "alice\nadmin"}) {
            TrustedSessionPrincipalVerifier verifier = verifier(
                    TrustedSessionPrincipalVerifierTest::valid,
                    (issuer, subject) -> account, () -> NOW);
            assertThrows(SecurityException.class, () -> verifier.verify("test-session"));
        }
    }

    @Test
    void unavailableIdentityBindingOrClockFailClosedWithoutLeakingDiagnostics() {
        TrustedSessionPrincipalVerifier identityFailure = verifier(() -> {
            throw new IllegalStateException("identity-backend-secret");
        }, (issuer, subject) -> "alice", () -> NOW);
        TrustedSessionPrincipalVerifier bindingFailure = verifier(
                TrustedSessionPrincipalVerifierTest::valid, (issuer, subject) -> {
                    throw new IllegalStateException("account-backend-secret");
                }, () -> NOW);
        TrustedSessionPrincipalVerifier clockFailure = verifier(
                TrustedSessionPrincipalVerifierTest::valid,
                (issuer, subject) -> "alice", () -> { throw new IllegalStateException("clock-secret"); });
        for (TrustedSessionPrincipalVerifier verifier : new TrustedSessionPrincipalVerifier[] {
                identityFailure, bindingFailure, clockFailure }) {
            SecurityException denied = assertThrows(SecurityException.class,
                    () -> verifier.verify("test-session"));
            assertEquals("verified session required", denied.getMessage());
        }
        assertThrows(SecurityException.class, () -> identityFailure.verify(null));
        assertThrows(SecurityException.class, () -> identityFailure.verify(""));
    }

    @Test
    void configurationRejectsEmptyAssuranceOrFreshnessPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new TrustedSessionPrincipalVerifier(
                bearer -> valid(), (issuer, subject) -> "alice", ISSUER,
                AUDIENCE, Set.of(), 300, () -> NOW));
        assertThrows(IllegalArgumentException.class, () -> new TrustedSessionPrincipalVerifier(
                bearer -> valid(), (issuer, subject) -> "alice", ISSUER,
                AUDIENCE, Set.of("step-up"), 0, () -> NOW));
        assertThrows(IllegalArgumentException.class, () -> new TrustedSessionPrincipalVerifier(
                bearer -> valid(), (issuer, subject) -> "alice", " ",
                AUDIENCE, Set.of("step-up"), 300, () -> NOW));
    }

    @Test
    void requestHandlerCanOnlyRotateIndependentlyBoundAccount() {
        JdbcDatabase db = JdbcDatabase.inMemory("verified_session_" +
                UUID.randomUUID().toString().replace("-", ""));
        JdbcUserAccountRepository reader = new JdbcUserAccountRepository(db);
        JdbcUserAccountRepository writer = new JdbcUserAccountRepository(db);
        TestAccounts.seed(writer, AccountRole.CUSTOMER, "alice", "first-alice");
        TestAccounts.seed(writer, AccountRole.CUSTOMER, "bob", "first-bob");
        AtomicLong now = new AtomicLong(NOW);
        AtomicInteger sourceCalls = new AtomicInteger();
        TrustedSessionPrincipalVerifier verifier = new TrustedSessionPrincipalVerifier(
                bearer -> {
                    sourceCalls.incrementAndGet();
                    return "valid-alice".equals(bearer) ? valid() : null;
                }, (issuer, subject) -> "subject-alice".equals(subject) ? "alice" : null,
                ISSUER, AUDIENCE, Set.of("step-up"), 300, now::get);
        TrustedCredentialRequestHandler handler = new TrustedCredentialRequestHandler(
                () -> "authenticated-gateway-client",
                new CredentialRequestRateLimiter(5, 60_000, 10, now::get),
                verifier,
                new CredentialRequestRateLimiter(5, 60_000, 10, now::get),
                new TrustedCredentialRotationService(reader, writer));
        assertThrows(SecurityException.class,
                () -> handler.rotate(null, "first-alice", "wrong"));
        assertEquals(0, sourceCalls.get());
        assertThrows(SecurityException.class,
                () -> handler.rotate("forged", "first-alice", "wrong"));
        assertTrue(handler.rotate("valid-alice", "first-alice", "second-alice"));
        UserLoginService login = new UserLoginService(reader);
        assertTrue(login.authenticate(credentials("alice", "second-alice")).isPresent());
        assertTrue(login.authenticate(credentials("bob", "first-bob")).isPresent());
        assertFalse(login.authenticate(credentials("bob", "second-alice")).isPresent());
    }

    private static TrustedSessionPrincipalVerifier verifier(
            java.util.function.Supplier<SessionEvidence> source,
            TrustedSessionPrincipalVerifier.SubjectAccountBinding binding,
            java.util.function.LongSupplier clock) {
        return new TrustedSessionPrincipalVerifier(
                bearer -> source.get(), binding, ISSUER, AUDIENCE,
                Set.of("step-up"), 300, clock);
    }

    private static SessionEvidence valid() {
        return session(true, ISSUER, AUDIENCE, "subject-alice", "step-up", 9000, 11000, 9900);
    }

    private static SessionEvidence session(boolean active, String issuer, String audience,
            String subject, String assurance, long notBefore, long expires, long authenticated) {
        return new SessionEvidence(active, issuer, audience, subject,
                assurance, notBefore, expires, authenticated);
    }

    private static com.revature.cardealer.User credentials(String name, String password) {
        com.revature.cardealer.User user = new com.revature.cardealer.User();
        user.setUsername(name);
        user.setPassword(password);
        return user;
    }
}
