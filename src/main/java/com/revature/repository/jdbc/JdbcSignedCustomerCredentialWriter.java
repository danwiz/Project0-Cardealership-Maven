package com.revature.repository.jdbc;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.TrustedCustomerCredentialWriter;

/**
 * Opt-in, signed-intent credential mutation port for PostgreSQL.
 *
 * The JDBC principal is granted EXECUTE on ONE SECURITY DEFINER function and
 * no accounts SELECT or UPDATE. A stolen SQL login cannot mint an authorized
 * password change because the independent signing secret is not in its SQL
 * grants. The privileged database function independently checks the HMAC,
 * role, hash, session generation and one-time nonce before updating.
 *
 * The signing secret is injected to a separately protected credential service
 * and is deliberately NOT stored in the SQL login or a public web component.
 * Compromise of both the SQL login AND service signing secret is still a
 * critical threat. This is a review-only design, NOT production composition.
 */
public final class JdbcSignedCustomerCredentialWriter implements TrustedCustomerCredentialWriter {
    private static final int MAX_HASH_LENGTH = 512;
    private static final int MAX_USERNAME_LENGTH = 100;
    private final JdbcDatabase database;
    private final byte[] signingKey;
    private final Pbkdf2PasswordHasher hasher;

    public JdbcSignedCustomerCredentialWriter(JdbcDatabase database, byte[] signingKey) {
        this.database = Objects.requireNonNull(database, "database");
        if (this.database.isH2()) {
            throw new IllegalArgumentException("signed customer credential writer requires PostgreSQL");
        }
        this.database.requireCurrentSchema();
        if (signingKey == null || signingKey.length < 32 || signingKey.length > 128) {
            throw new IllegalArgumentException("a separate 256-bit or stronger service signing key is required");
        }
        this.signingKey = signingKey.clone();
        this.hasher = new Pbkdf2PasswordHasher();
    }

    @Override
    public boolean rotateCustomerPassword(User snapshot,
                                          String currentPlaintext,
                                          String replacementEncoded) {
        if (snapshot == null || snapshot.getRole() != AccountRole.CUSTOMER
                || snapshot.getUsername() == null
                || snapshot.getUsername().isEmpty()
                || snapshot.getUsername().length() > MAX_USERNAME_LENGTH
                || snapshot.getUsername().indexOf('\n') >= 0
                || snapshot.getUsername().indexOf('\r') >= 0
                || snapshot.getSessionRevision() == null
                || snapshot.getPassword() == null
                || currentPlaintext == null || currentPlaintext.isEmpty()
                || replacementEncoded == null || replacementEncoded.length() > MAX_HASH_LENGTH
                || !replacementEncoded.startsWith("pbkdf2$")
                || !hasher.matches(currentPlaintext, snapshot.getPassword())
                || hasher.matches(currentPlaintext, replacementEncoded)
                || Objects.equals(snapshot.getPassword(), replacementEncoded)) {
            throw new SecurityException("invalid or stale customer credential intent");
        }
        final UUID prior;
        try {
            prior = UUID.fromString(snapshot.getSessionRevision());
        } catch (IllegalArgumentException invalidRevision) {
            throw new SecurityException("invalid session revision", invalidRevision);
        }

        UUID nonce = UUID.randomUUID();
        String payload = canonical(snapshot.getUsername(), prior,
                snapshot.getPassword(), replacementEncoded, nonce);
        String authentication = hex(mac(payload));
        try (Connection connection = database.openConnection();
                PreparedStatement action = connection.prepareStatement(
                        "SELECT rotate_customer_credential_authorized(?, ?, ?, ?, ?, ?)")) {
            action.setString(1, snapshot.getUsername());
            action.setObject(2, prior);
            action.setString(3, snapshot.getPassword());
            action.setString(4, replacementEncoded);
            action.setObject(5, nonce);
            action.setString(6, authentication);
            try (ResultSet result = action.executeQuery()) {
                if (!result.next() || !result.getBoolean(1) || result.next()) {
                    throw new SecurityException("credential rotation was not authorized");
                }
                return true;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("signed credential update failed", failure);
        }
    }

    private byte[] mac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingKey.clone(), "HmacSHA256"));
            return mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HmacSHA256 signing is unavailable", failure);
        }
    }

    static String canonical(String username, UUID revision, String priorHash,
                            String replacementHash, UUID nonce) {
        return username + "\n" + revision + "\n" + priorHash
                + "\n" + replacementHash + "\n" + nonce;
    }

    private static String hex(byte[] digest) {
        char[] out = new char[digest.length * 2];
        final char[] alphabet = "0123456789abcdef".toCharArray();
        for (int i = 0; i < digest.length; i++) {
            out[2 * i] = alphabet[(digest[i] >>> 4) & 15];
            out[2 * i + 1] = alphabet[digest[i] & 15];
        }
        return new String(out);
    }
}
