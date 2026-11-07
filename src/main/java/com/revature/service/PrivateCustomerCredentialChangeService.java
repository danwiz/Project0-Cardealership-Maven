package com.revature.service;

import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Internal-only customer password change facade.
 *
 * A public signup gateway must never receive the JDBC credential writer,
 * encoded account hash, or session generation. An independently protected
 * private service authenticates the presented CURRENT plaintext password and
 * passes its persisted account snapshot to a separately provisioned trusted
 * writer, which MUST independently revalidate under the database lock.
 *
 * This object is an application service boundary, NOT in itself an RPC,
 * process isolation, mTLS, or secret protection mechanism. It must not be
 * bound into anonymous/public dependency injection or exposed through
 * serialization, logs, or unrestricted internal calls.
 */
public final class PrivateCustomerCredentialChangeService {
    private static final int MAX_USERNAME_LENGTH = 100;
    private static final int MAX_PASSWORD_LENGTH = 256;

    private final UserAccountRepository privateAccountReader;
    private final TrustedCustomerCredentialWriter trustedWriter;
    private final Pbkdf2PasswordHasher hasher;

    public PrivateCustomerCredentialChangeService(
            UserAccountRepository privateAccountReader,
            TrustedCustomerCredentialWriter trustedWriter) {
        this.privateAccountReader = Objects.requireNonNull(
                privateAccountReader, "privateAccountReader");
        this.trustedWriter = Objects.requireNonNull(
                trustedWriter, "trustedWriter");
        this.hasher = new Pbkdf2PasswordHasher();
    }

    /**
     * Returns only success/failure; no encoded hash or account snapshot leaves
     * this private service. The caller must authenticate to the service itself
     * through a separately reviewed channel before calling this method.
     */
    public boolean changePassword(
            String username, String currentPlaintext, String replacementPlaintext) {
        requireUsername(username);
        requirePassword(currentPlaintext);
        requirePassword(replacementPlaintext);
        if (currentPlaintext.equals(replacementPlaintext)) {
            throw new SecurityException("replacement credential must differ");
        }

        User stored = privateAccountReader.findByUsername(username)
                .orElseThrow(() -> new SecurityException("customer credentials invalid"));
        if (stored.getRole() != AccountRole.CUSTOMER
                || stored.getSessionRevision() == null
                || stored.getPassword() == null
                || !hasher.matches(currentPlaintext, stored.getPassword())) {
            throw new SecurityException("customer credentials invalid");
        }
        String encodedReplacement = hasher.hash(replacementPlaintext);
        // The writer MUST independently validate the CURRENT plaintext,
        // role, hash and session generation while holding the database row.
        return trustedWriter.rotateCustomerPassword(
                stored, currentPlaintext, encodedReplacement);
    }

    private static void requireUsername(String value) {
        if (value == null || value.trim().isEmpty()
                || value.length() > MAX_USERNAME_LENGTH) {
            throw new SecurityException("customer credentials invalid");
        }
    }

    private static void requirePassword(String value) {
        if (value == null || value.isEmpty()
                || value.length() > MAX_PASSWORD_LENGTH) {
            throw new SecurityException("customer credentials invalid");
        }
    }
}
