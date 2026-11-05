package com.revature.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;
import java.util.Optional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import com.revature.cardealer.User;

/**
 * Explicitly provisioned, bounded migration of the project's historical
 * PBKDF2-HMAC-SHA256/120k credentials. THIS IS NOT A GENERAL PASSWORD VERIFIER.
 *
 * Never install this with an untrusted repository writer. The default login
 * service does not enable this capability; a trusted migration process must
 * supply both the reader and a writer with narrowly approved privileges.
 */
public final class LegacyPbkdf2CredentialUpgrade {
    private static final int LEGACY_ITERATIONS = 120_000;
    private static final String LEGACY_ITERATIONS_TEXT = "120000";
    private static final int SALT_BYTES = 16;
    private static final int KEY_BYTES = 32;
    private static final int MAX_ENCODED_LENGTH = 512;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";

    private final UserAccountRepository credentialWriter;

    public LegacyPbkdf2CredentialUpgrade(UserAccountRepository credentialWriter) {
        this.credentialWriter = Objects.requireNonNull(credentialWriter, "credentialWriter");
    }

    /**
     * Upgrade only the account read from authoritative storage, after verifying
     * exactly one known historical encoding shape with fixed, bounded effort.
     * CAS failure always denies, rather than authenticating a stale account.
     */
    public Optional<User> verifyAndUpgrade(User persisted, String plaintext,
            UserAccountRepository reader, PasswordHasher modernHasher) {
        Objects.requireNonNull(reader, "reader");
        Objects.requireNonNull(modernHasher, "modernHasher");
        if (persisted == null || persisted.getUsername() == null
                || persisted.getPassword() == null || plaintext == null
                || persisted.getSessionRevision() == null
                || !matchesHistoricalEncoding(plaintext, persisted.getPassword())) {
            return Optional.empty();
        }

        String next = modernHasher.hash(plaintext);
        // Fail closed if a custom hasher or configuration cannot produce a
        // current, verifiable hash. There is no fallback to weaker new hashes.
        if (!new Pbkdf2PasswordHasher().matches(plaintext, next)) {
            return Optional.empty();
        }
        if (!credentialWriter.updateCredentialIfCurrent(
                persisted.getUsername(), persisted.getPassword(), next)) {
            return Optional.empty();
        }

        Optional<User> current = reader.findByUsername(persisted.getUsername());
        if (!current.isPresent()
                || current.get().getRole() != persisted.getRole()
                || Objects.equals(current.get().getSessionRevision(), persisted.getSessionRevision())
                || !new Pbkdf2PasswordHasher().matches(plaintext, current.get().getPassword())) {
            return Optional.empty();
        }
        return current;
    }

    /** Strict parsing before KDF: no unbounded iteration counts or key lengths. */
    private static boolean matchesHistoricalEncoding(String plaintext, String encoded) {
        if (encoded.length() > MAX_ENCODED_LENGTH) return false;
        String[] parts = encoded.split("\\$", -1);
        if (parts.length != 4 || !"pbkdf2".equals(parts[0])
                || !LEGACY_ITERATIONS_TEXT.equals(parts[1])) {
            return false;
        }

        byte[] salt;
        byte[] expected;
        try {
            salt = Base64.getDecoder().decode(parts[2]);
            expected = Base64.getDecoder().decode(parts[3]);
            if (salt.length != SALT_BYTES || expected.length != KEY_BYTES
                    || !Base64.getEncoder().encodeToString(salt).equals(parts[2])
                    || !Base64.getEncoder().encodeToString(expected).equals(parts[3])) {
                return false;
            }
        } catch (IllegalArgumentException invalid) {
            return false;
        }

        PBEKeySpec specification = new PBEKeySpec(
                plaintext.toCharArray(), salt, LEGACY_ITERATIONS, KEY_BYTES * 8);
        try {
            byte[] actual = SecretKeyFactory.getInstance(ALGORITHM)
                    .generateSecret(specification).getEncoded();
            return MessageDigest.isEqual(expected, actual);
        } catch (NoSuchAlgorithmException | InvalidKeySpecException unavailable) {
            throw new IllegalStateException("legacy PBKDF2 verification unavailable", unavailable);
        } finally {
            specification.clearPassword();
        }
    }
}
