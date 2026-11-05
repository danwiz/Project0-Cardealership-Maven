package com.revature.service;

import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.User;

/**
 * Credential-only application service for a separately trusted process.
 *
 * Keep the writer repository and its SQL credentials out of the ordinary
 * dealership runtime. Merely constructing this inside the same process as
 * untrusted request handling would NOT create a security boundary.
 *
 * This class does not open a network endpoint or manage password recovery.
 */
public final class TrustedCredentialRotationService {
    private final UserAccountRepository identityReader;
    private final UserAccountRepository credentialWriter;
    private final PasswordHasher passwordHasher;

    public TrustedCredentialRotationService(UserAccountRepository identityReader,
            UserAccountRepository credentialWriter) {
        this(identityReader, credentialWriter, new Pbkdf2PasswordHasher());
    }

    public TrustedCredentialRotationService(UserAccountRepository identityReader,
            UserAccountRepository credentialWriter, PasswordHasher passwordHasher) {
        this.identityReader = Objects.requireNonNull(identityReader, "identityReader");
        this.credentialWriter = Objects.requireNonNull(credentialWriter, "credentialWriter");
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        if (identityReader == credentialWriter) {
            throw new IllegalArgumentException(
                    "a separately held credential writer is required");
        }
    }

    /**
     * Reauthenticate the claimant against persistent identity data, then issue
     * one compare-and-swap credential update through the restricted writer.
     * Both missing accounts and invalid passwords receive the same error.
     */
    public boolean rotate(User currentCredentials, String replacementPassword) {
        if (currentCredentials == null || isBlank(currentCredentials.getUsername())
                || currentCredentials.getPassword() == null) {
            throw new SecurityException("current account authentication required");
        }
        if (isBlank(replacementPassword)) {
            throw new IllegalArgumentException("replacement password must not be blank");
        }
        Optional<User> existing = identityReader.findByUsername(currentCredentials.getUsername());
        if (!existing.isPresent() || !passwordHasher.matches(
                currentCredentials.getPassword(), existing.get().getPassword())) {
            throw new SecurityException("current account authentication required");
        }
        return credentialWriter.updateCredentialIfCurrent(existing.get().getUsername(),
                existing.get().getPassword(), passwordHasher.hash(replacementPassword));
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
