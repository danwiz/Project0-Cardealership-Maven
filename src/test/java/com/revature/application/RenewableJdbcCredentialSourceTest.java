package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcCredentialSource;
import com.revature.repository.jdbc.RenewableJdbcCredentialSource;
import com.revature.repository.jdbc.RenewableJdbcCredentialSource.SecretMaterial;

/** Versioned in-memory fixtures only; no managed store or real credentials. */
class RenewableJdbcCredentialSourceTest {
    private static final String ROLE = "isolated_writer";
    private static final long NOW = 10_000;

    @Test
    void acceptsCurrentLeaseTransfersOwnedBufferAndClearsOnClose() {
        char[] first = "ci-first-secret".toCharArray();
        char[] second = "ci-second-secret".toCharArray();
        Queue<SecretMaterial> material = new ArrayDeque<>();
        material.add(lease(ROLE, first, 1, NOW - 1, NOW + 120, false));
        material.add(lease(ROLE, second, 2, NOW - 1, NOW + 120, false));
        RenewableJdbcCredentialSource source = source(material::remove, 0, () -> NOW);
        try (JdbcCredentialSource.CredentialLease accepted = source.acquire()) {
            assertEquals(ROLE, accepted.username());
        }
        assertCleared(first);
        try (JdbcCredentialSource.CredentialLease accepted = source.acquire()) {
            assertEquals(ROLE, accepted.username());
        }
        assertCleared(second);
    }

    @Test
    void generationRollbackIsDeniedAndRejectedSecretIsWiped() {
        char[] newest = "ci-newest-secret".toCharArray();
        char[] stale = "ci-stale-secret".toCharArray();
        Queue<SecretMaterial> material = new ArrayDeque<>();
        material.add(lease(ROLE, newest, 7, NOW - 1, NOW + 120, false));
        material.add(lease(ROLE, stale, 6, NOW - 1, NOW + 120, false));
        RenewableJdbcCredentialSource source = source(material::remove, 0, () -> NOW);
        try (JdbcCredentialSource.CredentialLease accepted = source.acquire()) {
            assertEquals(ROLE, accepted.username());
        }
        assertCleared(newest);
        assertDenied(() -> source.acquire());
        assertCleared(stale);

        char[] rollbackAfterRestart = "ci-rollback".toCharArray();
        RenewableJdbcCredentialSource restarted = source(
                () -> lease(ROLE, rollbackAfterRestart, 6, NOW - 1, NOW + 120, false),
                7, () -> NOW);
        assertDenied(restarted::acquire);
        assertCleared(rollbackAfterRestart);
    }

    @Test
    void rejectsRevokedExpiredFutureOverlongAndRoleSwappedLeases() {
        char[][] secrets = {
                "revoked".toCharArray(), "expired".toCharArray(),
                "future".toCharArray(), "overlong".toCharArray(),
                "wrong-role".toCharArray(), "bad-version".toCharArray(),
                "negative-version".toCharArray(), new char[0]
        };
        SecretMaterial[] invalid = {
                lease(ROLE, secrets[0], 1, NOW - 1, NOW + 120, true),
                lease(ROLE, secrets[1], 1, NOW - 1, NOW, false),
                lease(ROLE, secrets[2], 1, NOW + 1, NOW + 120, false),
                lease(ROLE, secrets[3], 1, NOW - 1, NOW + 301, false),
                lease("unexpected_superuser", secrets[4], 1,
                        NOW - 1, NOW + 120, false),
                lease(ROLE, secrets[5], 0, NOW - 1, NOW + 120, false),
                lease(ROLE, secrets[6], -1, NOW - 1, NOW + 120, false),
                lease(ROLE, secrets[7], 1, NOW - 1, NOW + 120, false)
        };
        for (int i = 0; i < invalid.length; i++) {
            SecretMaterial candidate = invalid[i];
            AtomicBoolean handedOut = new AtomicBoolean(false);
            RenewableJdbcCredentialSource source = source(() -> {
                handedOut.set(true);
                return candidate;
            }, 0, () -> NOW);
            assertDenied(source::acquire);
            assertEquals(true, handedOut.get());
            assertCleared(secrets[i]);
        }
    }

    @Test
    void backendOutageNeverReusesPreviouslyAcceptedSecret() {
        char[] first = "ci-first-secret".toCharArray();
        AtomicBoolean online = new AtomicBoolean(true);
        RenewableJdbcCredentialSource source = source(() ->
                online.get() ? lease(ROLE, first, 1, NOW - 1, NOW + 120, false) : null,
                0, () -> NOW);
        try (JdbcCredentialSource.CredentialLease accepted = source.acquire()) {
            assertEquals(ROLE, accepted.username());
        }
        assertCleared(first);
        online.set(false);
        assertDenied(source::acquire);
    }

    @Test
    void providerOrClockFailuresAreSanitizedAndFailClosed() {
        RenewableJdbcCredentialSource providerFailure = source(() -> {
            throw new IllegalStateException("sensitive-provider-error");
        }, 0, () -> NOW);
        assertDenied(providerFailure::acquire);
        char[] input = "ci-secret".toCharArray();
        RenewableJdbcCredentialSource clockFailure = source(
                () -> lease(ROLE, input, 1, NOW - 1, NOW + 120, false),
                0, () -> { throw new IllegalStateException("clock-details"); });
        assertDenied(clockFailure::acquire);
        assertCleared(input);
    }

    @Test
    void invalidPolicyConfigurationIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> source(() -> null, -1, () -> NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RenewableJdbcCredentialSource(() -> null, ROLE,
                        0, 0, () -> NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RenewableJdbcCredentialSource(() -> null, " ",
                        300, 0, () -> NOW));
        assertThrows(IllegalArgumentException.class,
                () -> new RenewableJdbcCredentialSource(() -> null, "writer\nadmin",
                        300, 0, () -> NOW));
        RenewableJdbcCredentialSource clockUnavailable = source(
                () -> lease(ROLE, "ci-only".toCharArray(), 1, NOW - 1, NOW + 1, false),
                0, () -> 0);
        assertDenied(clockUnavailable::acquire);
    }

    private static RenewableJdbcCredentialSource source(
            RenewableJdbcCredentialSource.SecretLeaseProvider provider,
            long floor, java.util.function.LongSupplier clock) {
        return new RenewableJdbcCredentialSource(provider, ROLE, 300, floor, clock);
    }

    private static SecretMaterial lease(String username, char[] password,
            long generation, long notBefore, long expiresAt, boolean revoked) {
        return new SecretMaterial(username, password, generation, notBefore, expiresAt, revoked);
    }

    private static void assertDenied(java.util.concurrent.Callable<?> attempt) {
        SecurityException denied = assertThrows(SecurityException.class, attempt::call);
        assertEquals("database credential lease unavailable", denied.getMessage());
    }

    private static void assertCleared(char[] chars) {
        for (char value : chars) assertEquals('\0', value);
    }
}
