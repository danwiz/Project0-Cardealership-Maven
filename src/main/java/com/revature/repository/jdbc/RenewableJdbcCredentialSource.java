package com.revature.repository.jdbc;

import java.util.Arrays;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Provider-neutral lease-policy adapter for an isolated JDBC credential writer.
 *
 * This is NOT a secret store implementation. A separately trusted provider
 * must authenticate the workload, validate its own lease/revocation state,
 * deliver freshly owned char[] buffers and prevent generation rollback after
 * process restarts. This adapter only checks metadata at connection time.
 */
public final class RenewableJdbcCredentialSource implements JdbcCredentialSource {
    private static final String UNAVAILABLE = "database credential lease unavailable";

    private final SecretLeaseProvider provider;
    private final String expectedUsername;
    private final long maxRemainingSeconds;
    private final LongSupplier clockEpochSeconds;
    private long highestObservedGeneration;

    public RenewableJdbcCredentialSource(SecretLeaseProvider provider,
            String expectedUsername, long maxRemainingSeconds,
            long initialGenerationFloor, LongSupplier clockEpochSeconds) {
        this.provider = Objects.requireNonNull(provider, "provider");
        if (expectedUsername == null || expectedUsername.trim().isEmpty()
                || !expectedUsername.equals(expectedUsername.trim())
                || containsControlCharacter(expectedUsername)) {
            throw new IllegalArgumentException("expected database role required");
        }
        if (maxRemainingSeconds <= 0 || initialGenerationFloor < 0) {
            throw new IllegalArgumentException("invalid lease policy");
        }
        this.expectedUsername = expectedUsername;
        this.maxRemainingSeconds = maxRemainingSeconds;
        this.highestObservedGeneration = initialGenerationFloor;
        this.clockEpochSeconds = Objects.requireNonNull(clockEpochSeconds, "clockEpochSeconds");
    }

    /**
     * Serialized to fence concurrent generation rollback within this process.
     * A production provider still needs its own durable, cross-replica floor.
     */
    @Override
    public synchronized CredentialLease acquire() {
        SecretMaterial material = null;
        try {
            material = provider.acquireCurrent();
            final long now = clockEpochSeconds.getAsLong();
            if (material == null || now <= 0 || material.revoked
                    || material.generation <= 0
                    || material.generation < highestObservedGeneration
                    || !expectedUsername.equals(material.username)
                    || material.password == null || material.password.length == 0
                    || material.notBeforeEpochSeconds > now
                    || material.expiresAtEpochSeconds <= now
                    || material.expiresAtEpochSeconds - now > maxRemainingSeconds) {
                throw unavailable();
            }
            CredentialLease result = material.transferToJdbc();
            highestObservedGeneration = material.generation;
            return result;
        } catch (RuntimeException providerOrPolicyFailure) {
            // Never expose provider URI, role secrets, generation or diagnostics.
            throw unavailable();
        } finally {
            if (material != null) material.close();
        }
    }

    private static SecurityException unavailable() {
        return new SecurityException(UNAVAILABLE);
    }

    private static boolean containsControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    /**
     * Must return the CURRENT, independently attested and non-revoked secret
     * on every call, or fail closed. No cached fallback or stale response.
     */
    public interface SecretLeaseProvider {
        SecretMaterial acquireCurrent();
    }

    /**
     * Owns a fresh password buffer; it is either transferred to the JDBC
     * connection lease or zeroed on rejection. Never share/reuse the array.
     */
    public static final class SecretMaterial implements AutoCloseable {
        private final String username;
        private char[] password;
        private final long generation;
        private final long notBeforeEpochSeconds;
        private final long expiresAtEpochSeconds;
        private final boolean revoked;

        public SecretMaterial(String username, char[] password, long generation,
                long notBeforeEpochSeconds, long expiresAtEpochSeconds,
                boolean revoked) {
            this.username = username;
            this.password = password;
            this.generation = generation;
            this.notBeforeEpochSeconds = notBeforeEpochSeconds;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.revoked = revoked;
        }

        private CredentialLease transferToJdbc() {
            char[] owned = password;
            try {
                return new CredentialLease(username, owned);
            } catch (RuntimeException rejected) {
                if (owned != null) Arrays.fill(owned, '\0');
                throw rejected;
            } finally {
                password = null;
            }
        }

        @Override
        public void close() {
            if (password != null) {
                Arrays.fill(password, '\0');
                password = null;
            }
        }
    }
}
