package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.TrustedCustomerCredentialWriter;

/**
 * Opt-in trusted credential-only service implementation.
 *
 * IMPORTANT: the database identity backing this object can update
 * credentials; it must NEVER be handed to the ordinary registration runtime
 * or exposed as public SQL credentials. This adapter is a DRAFT reference
 * boundary and is NOT a production-ready database grant design.
 */
public final class JdbcTrustedCustomerCredentialWriter implements TrustedCustomerCredentialWriter {
    private final JdbcDatabase trustedDatabase;
    private final Pbkdf2PasswordHasher passwordHasher;

    public JdbcTrustedCustomerCredentialWriter(JdbcDatabase trustedDatabase) {
        this.trustedDatabase = Objects.requireNonNull(trustedDatabase, "trustedDatabase");
        this.trustedDatabase.requireCurrentSchema();
        this.passwordHasher = new Pbkdf2PasswordHasher();
    }

    @Override
    public boolean rotateCustomerPassword(User snapshot, String plaintext, String replacementEncoded) {
        if (snapshot == null || snapshot.getRole() != AccountRole.CUSTOMER
                || snapshot.getUsername() == null || snapshot.getUsername().trim().isEmpty()
                || snapshot.getSessionRevision() == null
                || snapshot.getPassword() == null
                || plaintext == null || plaintext.isEmpty()
                || replacementEncoded == null || !replacementEncoded.startsWith("pbkdf2$")
                || replacementEncoded.length() > 512) {
            throw new SecurityException("current CUSTOMER identity and PBKDF2 credential required");
        }
        try (Connection connection = trustedDatabase.openConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                String priorHash;
                String priorRevision;
                String storedRole;
                try (PreparedStatement lock = connection.prepareStatement(
                        "SELECT encoded_credential, session_revision, role "
                        + "FROM accounts WHERE username = ? FOR UPDATE")) {
                    lock.setString(1, snapshot.getUsername());
                    try (ResultSet row = lock.executeQuery()) {
                        if (!row.next()) throw new SecurityException("customer account no longer exists");
                        priorHash = row.getString("encoded_credential");
                        priorRevision = row.getString("session_revision");
                        storedRole = row.getString("role");
                    }
                }

                if (!AccountRole.CUSTOMER.name().equals(storedRole)
                        || !Objects.equals(snapshot.getPassword(), priorHash)
                        || !Objects.equals(snapshot.getSessionRevision(), priorRevision)
                        || !passwordHasher.matches(plaintext, priorHash)) {
                    throw new SecurityException("stale or invalid customer credential");
                }
                // Never allow same hash or a duplicate password rotation to revalidate stale sessions.
                if (Objects.equals(priorHash, replacementEncoded)
                        || passwordHasher.matches(plaintext, replacementEncoded)) {
                    throw new SecurityException("replacement must be a different password");
                }

                String sql = trustedDatabase.isH2()
                        ? "UPDATE accounts SET encoded_credential = ?, session_revision = ? "
                            + "WHERE username = ? AND encoded_credential = ? AND session_revision = ? "
                            + "AND role = 'CUSTOMER'"
                        : "UPDATE accounts SET encoded_credential = ? "
                            + "WHERE username = ? AND encoded_credential = ? AND session_revision = ? "
                            + "AND role = 'CUSTOMER'";
                try (PreparedStatement update = connection.prepareStatement(sql)) {
                    update.setString(1, replacementEncoded);
                    int i = 2;
                    if (trustedDatabase.isH2()) update.setObject(i++, UUID.randomUUID());
                    update.setString(i++, snapshot.getUsername());
                    update.setString(i++, priorHash);
                    update.setObject(i, UUID.fromString(priorRevision));
                    if (update.executeUpdate() != 1) {
                        throw new SecurityException("credential rotation conflict");
                    }
                }

                connection.commit();
                return true;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("trusted customer password transaction failed", failure);
        }
    }
}
