package com.revature.service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Objects;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * PBKDF2 password encoder using a unique random salt for every credential.
 */
public class Pbkdf2PasswordHasher implements PasswordHasher {

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "pbkdf2";
    private static final int DEFAULT_ITERATIONS = 600_000;
    private static final int MAX_VERIFY_ITERATIONS = 2_000_000;
    private static final int MAX_ENCODED_LENGTH = 512;
    private static final int DEFAULT_KEY_LENGTH_BITS = 256;
    private static final int SALT_LENGTH_BYTES = 16;

    private final SecureRandom secureRandom;
    private final int iterations;
    private final int keyLengthBits;

    public Pbkdf2PasswordHasher() {
        this(new SecureRandom(), DEFAULT_ITERATIONS, DEFAULT_KEY_LENGTH_BITS);
    }

    Pbkdf2PasswordHasher(SecureRandom secureRandom, int iterations, int keyLengthBits) {
        this.secureRandom = Objects.requireNonNull(secureRandom, "secureRandom");
        if (iterations < DEFAULT_ITERATIONS || iterations > MAX_VERIFY_ITERATIONS) {
            throw new IllegalArgumentException("iterations outside supported secure bounds");
        }
        if (keyLengthBits != DEFAULT_KEY_LENGTH_BITS) {
            throw new IllegalArgumentException("keyLengthBits must be 256");
        }
        this.iterations = iterations;
        this.keyLengthBits = keyLengthBits;
    }

    @Override
    public String hash(String plaintextPassword) {
        Objects.requireNonNull(plaintextPassword, "plaintextPassword");
        byte[] salt = new byte[SALT_LENGTH_BYTES];
        secureRandom.nextBytes(salt);
        byte[] derivedKey = derive(plaintextPassword, salt, iterations, keyLengthBits);
        return String.join("$",
                PREFIX,
                Integer.toString(iterations),
                Base64.getEncoder().encodeToString(salt),
                Base64.getEncoder().encodeToString(derivedKey));
    }

    @Override
    public boolean matches(String plaintextPassword, String encodedPassword) {
        if (plaintextPassword == null || encodedPassword == null
                || encodedPassword.length() > MAX_ENCODED_LENGTH) {
            return false;
        }

        String[] parts = encodedPassword.split("\\$", -1);
        if (parts.length != 4 || !PREFIX.equals(parts[0])) {
            return false;
        }

        try {
            int encodedIterations = Integer.parseInt(parts[1]);
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            // The stored work factor and encoded sizes are untrusted input until validated.
            // Reject implausible costs before attempting an expensive PBKDF2 operation.
            if (encodedIterations < DEFAULT_ITERATIONS || encodedIterations > MAX_VERIFY_ITERATIONS
                    || salt.length != SALT_LENGTH_BYTES
                    || expected.length != DEFAULT_KEY_LENGTH_BITS / 8) {
                return false;
            }
            byte[] actual = derive(plaintextPassword, salt, encodedIterations, DEFAULT_KEY_LENGTH_BITS);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static byte[] derive(String plaintextPassword, byte[] salt, int iterations, int keyLengthBits) {
        PBEKeySpec specification = new PBEKeySpec(
                plaintextPassword.toCharArray(), salt, iterations, keyLengthBits);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(specification).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException exception) {
            throw new IllegalStateException("PBKDF2 password hashing is unavailable", exception);
        } finally {
            specification.clearPassword();
        }
    }
}
