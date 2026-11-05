package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.service.PasswordHasher;
import com.revature.service.Pbkdf2PasswordHasher;

/**
 * Local-operator-only, single-use administrator bootstrap for JDBC deployments.
 *
 * This service must never be exposed through public registration or network APIs.
 * Database credentials, operating-system access and the dedicated CLI are the trust
 * boundary. The one-time marker and administrator insert commit atomically.
 */
public final class AdministratorBootstrapService {
    private final JdbcDatabase database;
    private final PasswordHasher hasher;

    public AdministratorBootstrapService(JdbcDatabase database) {
        this(database, new Pbkdf2PasswordHasher());
    }

    AdministratorBootstrapService(JdbcDatabase database, PasswordHasher hasher) {
        this.database = Objects.requireNonNull(database, "database");
        this.hasher = Objects.requireNonNull(hasher, "hasher");
    }

    /**
     * Seeds exactly one initial administrator into a versioned database.
     * The caller retains responsibility for clearing the supplied character array.
     */
    public void bootstrap(String username, char[] password) {
        validate(username, password);
        char[] secretCopy = Arrays.copyOf(password, password.length);
        final String encodedCredential;
        try {
            encodedCredential = hasher.hash(new String(secretCopy));
        } finally {
            Arrays.fill(secretCopy, '\0');
        }

        database.migrate();
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false);
            try {
                if (exists(connection, "SELECT 1 FROM administrator_bootstrap_state")
                        || exists(connection, "SELECT 1 FROM accounts WHERE role = 'ADMINISTRATOR'")) {
                    throw new SecurityException("administrator bootstrap has already been completed or an administrator exists");
                }

                try (PreparedStatement addAdmin = connection.prepareStatement(
                        "INSERT INTO accounts (username, encoded_credential, role) VALUES (?, ?, ?)")) {
                    addAdmin.setString(1, username);
                    addAdmin.setString(2, encodedCredential);
                    addAdmin.setString(3, AccountRole.ADMINISTRATOR.name());
                    addAdmin.executeUpdate();
                }

                try (PreparedStatement mark = connection.prepareStatement(
                        "INSERT INTO administrator_bootstrap_state (bootstrap_id, initial_admin_username) VALUES (1, ?)")) {
                    mark.setString(1, username);
                    mark.executeUpdate();
                }

                connection.commit();
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            if ("23505".equals(failure.getSQLState())) {
                throw new SecurityException("administrator bootstrap was already claimed or username exists", failure);
            }
            throw new IllegalStateException("administrator bootstrap transaction failed", failure);
        }
    }

    private static boolean exists(Connection connection, String query) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(query);
                ResultSet result = statement.executeQuery()) {
            return result.next();
        }
    }

    private static void validate(String username, char[] password) {
        if (username == null || username.isEmpty() || !username.equals(username.trim())
                || username.length() > 100) {
            throw new IllegalArgumentException("administrator username must be 1-100 characters with no surrounding whitespace");
        }
        if (password == null || password.length < 12 || password.length > 1024) {
            throw new IllegalArgumentException("administrator password must be 12-1024 characters");
        }
        boolean nonWhitespace = false;
        for (char value : password) if (!Character.isWhitespace(value)) nonWhitespace = true;
        if (!nonWhitespace) throw new IllegalArgumentException("administrator password cannot be all whitespace");
    }
}
