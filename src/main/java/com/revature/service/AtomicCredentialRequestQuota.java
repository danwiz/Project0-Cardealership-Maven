package com.revature.service;

import java.util.Objects;

/**
 * Provider-neutral adapter to a remotely shared, atomic quota decision.
 *
 * This class makes no network calls and supplies no production store. The
 * injected store must authenticate callers, enforce one atomic charge using
 * its own trusted clock, bound key cardinality, and persist quota state across
 * process restarts. Failure to obtain a decision always denies the request.
 */
public final class AtomicCredentialRequestQuota implements CredentialRequestQuota {
    private static final String LIMITED = "credential request temporarily limited";
    private static final String UNAVAILABLE = "credential request temporarily unavailable";

    private final AtomicQuotaStore store;
    private final String namespace;
    private final int maxAttempts;
    private final long windowMillis;

    public AtomicCredentialRequestQuota(AtomicQuotaStore store, String namespace,
            int maxAttempts, long windowMillis) {
        this.store = Objects.requireNonNull(store, "store");
        if (namespace == null || !namespace.matches("[a-z][a-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException("invalid quota namespace");
        }
        if (maxAttempts <= 0 || windowMillis <= 0) {
            throw new IllegalArgumentException("quota thresholds must be positive");
        }
        this.namespace = namespace;
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowMillis;
    }

    @Override
    public void acquire(String trustedKey) {
        if (trustedKey == null || trustedKey.trim().isEmpty()
                || !trustedKey.equals(trustedKey) || hasControlCharacter(trustedKey)) {
            throw new SecurityException("trusted quota key required");
        }
        final boolean accepted;
        try {
            accepted = store.tryAcquire(namespace, trustedKey, maxAttempts, windowMillis);
        } catch (RuntimeException unavailable) {
            // Never leak backend configuration, identifiers or error messages.
            throw new SecurityException(UNAVAILABLE);
        }
        if (!accepted) {
            throw new SecurityException(LIMITED);
        }
    }

    private static boolean hasControlCharacter(String key) {
        for (int i = 0; i < key.length(); i++) {
            if (Character.isISOControl(key.charAt(i))) return true;
        }
        return false;
    }

    /**
     * One indivisible admission decision and charged increment on the shared
     * backend for every admitted request, including attempts that will later
     * fail authentication or password checks. false means denied: never retry
     * locally as an allow. The store controls clock and window rollover.
     */
    public interface AtomicQuotaStore {
        boolean tryAcquire(String namespace, String trustedKey,
                int maxAttempts, long windowMillis);
    }
}
