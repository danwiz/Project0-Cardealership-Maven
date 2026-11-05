package com.revature.service;

import java.util.Collections;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;

import com.revature.service.TrustedCredentialRequestHandler.PrincipalVerifier;
import com.revature.service.TrustedCredentialRequestHandler.VerifiedPrincipal;

/**
 * Provider-neutral policy gate for an already authenticated bearer session.
 *
 * The injected source MUST first verify the token signature or authenticate
 * an authoritative introspection response, check revocation and enforce
 * token binding/replay policy. This class does none of that cryptographic or
 * network work and is NOT a deployable identity provider.
 */
public final class TrustedSessionPrincipalVerifier implements PrincipalVerifier {
    private static final String DENIED = "verified session required";

    private final ValidatedSessionSource sessions;
    private final SubjectAccountBinding accounts;
    private final String expectedIssuer;
    private final String expectedAudience;
    private final Set<String> allowedAssuranceContexts;
    private final long maxAuthenticationAgeSeconds;
    private final LongSupplier clockEpochSeconds;

    public TrustedSessionPrincipalVerifier(ValidatedSessionSource sessions,
            SubjectAccountBinding accounts, String expectedIssuer,
            String expectedAudience, Set<String> allowedAssuranceContexts,
            long maxAuthenticationAgeSeconds, LongSupplier clockEpochSeconds) {
        this.sessions = Objects.requireNonNull(sessions, "sessions");
        this.accounts = Objects.requireNonNull(accounts, "accounts");
        this.expectedIssuer = requiredConfiguration(expectedIssuer, "issuer");
        this.expectedAudience = requiredConfiguration(expectedAudience, "audience");
        if (allowedAssuranceContexts == null || allowedAssuranceContexts.isEmpty()) {
            throw new IllegalArgumentException("assurance contexts required");
        }
        Set<String> accepted = new HashSet<>();
        for (String context : allowedAssuranceContexts) {
            accepted.add(requiredConfiguration(context, "assurance context"));
        }
        this.allowedAssuranceContexts = Collections.unmodifiableSet(accepted);
        if (maxAuthenticationAgeSeconds <= 0) {
            throw new IllegalArgumentException("authentication freshness must be positive");
        }
        this.maxAuthenticationAgeSeconds = maxAuthenticationAgeSeconds;
        this.clockEpochSeconds = Objects.requireNonNull(clockEpochSeconds, "clockEpochSeconds");
    }

    @Override
    public VerifiedPrincipal verify(String bearerCredential) {
        if (bearerCredential == null || bearerCredential.isEmpty()) throw denied();
        final SessionEvidence evidence;
        try {
            evidence = sessions.validateBearer(bearerCredential);
        } catch (RuntimeException authenticationFailure) {
            throw denied();
        }
        if (evidence == null || !evidence.active
                || !expectedIssuer.equals(evidence.issuer)
                || !expectedAudience.equals(evidence.audience)
                || !allowedAssuranceContexts.contains(evidence.assuranceContext)
                || !validIdentity(evidence.subject)) {
            throw denied();
        }

        final long now;
        try {
            now = clockEpochSeconds.getAsLong();
        } catch (RuntimeException clockFailure) {
            throw denied();
        }
        if (now <= 0 || evidence.notBeforeEpochSeconds > now
                || evidence.expiresAtEpochSeconds <= now
                || evidence.authenticationEpochSeconds <= 0
                || evidence.authenticationEpochSeconds > now
                || now - evidence.authenticationEpochSeconds > maxAuthenticationAgeSeconds) {
            throw denied();
        }

        // A username from the bearer/session is never used as a write target.
        // Account binding MUST come from a separately trusted current lookup.
        final String boundUsername;
        try {
            boundUsername = accounts.resolveCurrentUsername(evidence.issuer, evidence.subject);
        } catch (RuntimeException bindingFailure) {
            throw denied();
        }
        if (!validIdentity(boundUsername)) throw denied();
        return new VerifiedPrincipal(evidence.subject, boundUsername);
    }

    private static String requiredConfiguration(String value, String name) {
        if (!validIdentity(value)) throw new IllegalArgumentException("invalid " + name);
        return value;
    }

    private static boolean validIdentity(String value) {
        return value != null && !value.trim().isEmpty() && value.equals(value.trim())
                && !hasControlCharacter(value);
    }

    private static boolean hasControlCharacter(String value) {
        for (int i = 0; i < value.length(); i++) {
            if (Character.isISOControl(value.charAt(i))) return true;
        }
        return false;
    }

    private static SecurityException denied() { return new SecurityException(DENIED); }

    /**
     * An independently trusted adapter, not a JWT claims parser. It must
     * authenticate tokens before returning evidence and fail closed on
     * unavailable keys, unknown issuers, inactive/revoked sessions and errors.
     */
    public interface ValidatedSessionSource {
        SessionEvidence validateBearer(String bearerCredential);
    }

    /**
     * Authoritative current subject/account lookup. Reject disabled or
     * ambiguous accounts; never accept a username from a request or token.
     */
    public interface SubjectAccountBinding {
        String resolveCurrentUsername(String validatedIssuer, String validatedSubject);
    }

    /**
     * Metadata returned only AFTER the source authenticates the bearer and
     * checks revocation. Never construct this directly from decoded claims.
     */
    public static final class SessionEvidence {
        private final boolean active;
        private final String issuer;
        private final String audience;
        private final String subject;
        private final String assuranceContext;
        private final long notBeforeEpochSeconds;
        private final long expiresAtEpochSeconds;
        private final long authenticationEpochSeconds;

        public SessionEvidence(boolean active, String issuer, String audience,
                String subject, String assuranceContext, long notBeforeEpochSeconds,
                long expiresAtEpochSeconds, long authenticationEpochSeconds) {
            this.active = active;
            this.issuer = issuer;
            this.audience = audience;
            this.subject = subject;
            this.assuranceContext = assuranceContext;
            this.notBeforeEpochSeconds = notBeforeEpochSeconds;
            this.expiresAtEpochSeconds = expiresAtEpochSeconds;
            this.authenticationEpochSeconds = authenticationEpochSeconds;
        }
    }
}
