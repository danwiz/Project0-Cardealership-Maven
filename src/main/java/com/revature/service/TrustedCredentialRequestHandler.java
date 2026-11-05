package com.revature.service;

import java.util.Objects;

import com.revature.cardealer.User;

/**
 * Request-facing gate for an isolated credential process (NOT a network API).
 *
 * A separately trusted verifier must validate token authenticity, issuer,
 * audience, expiry, revocation and session assurance before returning identity.
 * Never derive the rotation target from an untrusted request field.
 */
public final class TrustedCredentialRequestHandler {
    private final TrustedIngressIdentityResolver ingressIdentity;
    private final CredentialRequestQuota ingressLimiter;
    private final PrincipalVerifier verifier;
    private final CredentialRequestQuota limiter;
    private final TrustedCredentialRotationService rotation;

    public TrustedCredentialRequestHandler(TrustedIngressIdentityResolver ingressIdentity,
            CredentialRequestQuota ingressLimiter, PrincipalVerifier verifier,
            CredentialRequestQuota limiter,
            TrustedCredentialRotationService rotation) {
        this.ingressIdentity = Objects.requireNonNull(ingressIdentity, "ingressIdentity");
        this.ingressLimiter = Objects.requireNonNull(ingressLimiter, "ingressLimiter");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.rotation = Objects.requireNonNull(rotation, "rotation");
    }

    /**
     * No username parameter: only the verified principal selects the target.
     * The supplied current password is rechecked against persisted credentials.
     */
    public boolean rotate(String bearerCredential, String currentPassword,
            String replacementPassword) {
        // Charge unauthenticated requests before inspecting their bearer token.
        // Identity MUST come from trusted transport metadata, never request input.
        ingressLimiter.acquire(ingressIdentity.resolveTrustedKey());
        if (bearerCredential == null || bearerCredential.isEmpty()) {
            throw new SecurityException("verified session required");
        }
        VerifiedPrincipal principal = verifier.verify(bearerCredential);
        if (principal == null) {
            throw new SecurityException("verified session required");
        }
        limiter.acquire(principal.subject());
        User credentials = new User();
        credentials.setUsername(principal.username());
        credentials.setPassword(currentPassword);
        try {
            return rotation.rotate(credentials, replacementPassword);
        } finally {
            credentials.setPassword(null);
        }
    }

    /** Implementations are part of the trusted service boundary, not clients. */
    public interface PrincipalVerifier {
        VerifiedPrincipal verify(String bearerCredential);
    }

    /**
     * Supplies a stable requester key from authenticated ingress/gateway context.
     * Never trust a client-supplied IP, forwarded header or request parameter.
     * The implementation must fail closed if its transport identity is absent.
     */
    public interface TrustedIngressIdentityResolver {
        String resolveTrustedKey();
    }

    /** Immutable identity assertion created only by a trusted verifier. */
    public static final class VerifiedPrincipal {
        private final String subject;
        private final String username;

        public VerifiedPrincipal(String subject, String username) {
            this.subject = required(subject, "subject");
            this.username = required(username, "username");
        }

        public String subject() { return subject; }
        public String username() { return username; }

        private static String required(String value, String name) {
            if (value == null || value.trim().isEmpty()
                    || !value.equals(value.trim())) {
                throw new SecurityException("invalid verified " + name);
            }
            return value;
        }
    }
}
