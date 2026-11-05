package com.revature.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Bounded, in-process fixed-window throttle for a trusted credential service.
 *
 * This is a defense-in-depth prototype: counters are lost on restart and are
 * not shared between instances. Production must use an atomic shared limiter
 * and independent gateway limits for invalid/unauthenticated tokens.
 */
public final class CredentialRequestRateLimiter implements CredentialRequestQuota {
    private final int maxAttempts;
    private final long windowMillis;
    private final int maxSubjects;
    private final LongSupplier timeMillis;
    private final Map<String, Window> windows = new HashMap<>();

    public CredentialRequestRateLimiter(int maxAttempts, long windowMillis,
            int maxSubjects, LongSupplier timeMillis) {
        if (maxAttempts <= 0 || windowMillis <= 0 || maxSubjects <= 0) {
            throw new IllegalArgumentException("rate-limit thresholds must be positive");
        }
        this.maxAttempts = maxAttempts;
        this.windowMillis = windowMillis;
        this.maxSubjects = maxSubjects;
        this.timeMillis = Objects.requireNonNull(timeMillis, "timeMillis");
    }

    /**
     * Charges every admitted attempt, including wrong-password requests. The
     * caller must supply a stable subject obtained from a trusted verifier.
     */
    @Override
    public synchronized void acquire(String verifiedSubject) {
        if (verifiedSubject == null || verifiedSubject.trim().isEmpty()) {
            throw new SecurityException("verified subject required");
        }
        long now = timeMillis.getAsLong();
        Window current = windows.get(verifiedSubject);
        if (current != null && expired(current, now)) {
            windows.remove(verifiedSubject);
            current = null;
        }
        if (current == null) {
            if (windows.size() >= maxSubjects) {
                windows.entrySet().removeIf(entry -> expired(entry.getValue(), now));
            }
            if (windows.size() >= maxSubjects) {
                // Fail closed if cardinality would exceed bounded memory.
                throw new SecurityException("credential request temporarily limited");
            }
            current = new Window(now);
            windows.put(verifiedSubject, current);
        }
        if (current.attempts >= maxAttempts) {
            throw new SecurityException("credential request temporarily limited");
        }
        current.attempts++;
    }

    private boolean expired(Window window, long now) {
        // A backward wall-clock jump cannot reset a throttled subject.
        return now >= window.startedAt && now - window.startedAt >= windowMillis;
    }

    private static final class Window {
        private final long startedAt;
        private int attempts;
        private Window(long startedAt) {
            this.startedAt = startedAt;
        }
    }
}
