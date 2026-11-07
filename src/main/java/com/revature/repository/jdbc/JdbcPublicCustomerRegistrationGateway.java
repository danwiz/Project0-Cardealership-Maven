package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;

import com.revature.service.Pbkdf2PasswordHasher;

/**
 * Public customer-signup gateway, intentionally NOT an account repository.
 *
 * The production registration principal proposed for this port needs
 * schema_history SELECT, schema USAGE, and EXECUTE on two fixed,
 * owner-controlled functions. It does NOT need accounts SELECT or any
 * accounts INSERT/UPDATE/DELETE grant. No credential hashes, session
 * revisions, administrator operations, or DB writer capability are exposed
 * through this API.
 *
 * DRAFT only: a separate authenticated service must handle login,
 * password rotation, rate-limiting, and credential recovery.
 */
public final class JdbcPublicCustomerRegistrationGateway {
    private final JdbcDatabase database;
    private final Pbkdf2PasswordHasher hasher;

    public JdbcPublicCustomerRegistrationGateway(JdbcDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.requireCurrentSchema();
        this.hasher = new Pbkdf2PasswordHasher();
    }

    /**
     * Limited existence check, not an account lookup. Responses may be used
     * for registration UX only with abuse throttling at a separate API edge.
     */
    public boolean usernameExists(String username) {
        requireText("username", username);
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT customer_username_exists_trusted(?)")) {
            statement.setString(1, username);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) throw new IllegalStateException("username existence result missing");
                boolean exists = rows.getBoolean(1);
                if (rows.next()) throw new IllegalStateException("unexpected existence result count");
                return exists;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("customer name check unavailable", exception);
        }
    }

    /** Creates CUSTOMER only; the SQL owner assigns role and session revision. */
    public String registerCustomer(String username, String plaintextPassword) {
        requireText("username", username);
        requireText("password", plaintextPassword);
        String encoded = hasher.hash(plaintextPassword);
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT register_customer_trusted(?, ?)")) {
            statement.setString(1, username);
            statement.setString(2, encoded);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !username.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("unexpected registration response");
                }
            }
            return username;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("username is already registered", exception);
            }
            throw new IllegalStateException("customer registration unavailable", exception);
        }
    }

    private static void requireText(String label, String value) {
        if (value == null || value.trim().isEmpty() || value.length() > 500) {
            throw new IllegalArgumentException(label + " must be nonempty and within limits");
        }
    }
}
