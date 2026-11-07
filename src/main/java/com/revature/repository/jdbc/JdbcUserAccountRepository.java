package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.UserAccountRepository;
import com.revature.service.AuthorizedAdminAccountWriter;
import com.revature.service.Pbkdf2PasswordHasher;

/** JDBC-backed account repository using the migrated accounts table. */
public final class JdbcUserAccountRepository implements UserAccountRepository, AuthorizedAdminAccountWriter {
    private final JdbcDatabase database;

    public JdbcUserAccountRepository(JdbcDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.ensureSchemaReady();
    }

    @Override
    public User save(User user) {
        validate(user);
        // DB owner supplies a fresh revision DEFAULT; restricted INSERT roles must not
        // gain permission to specify or reuse the session revision themselves.
        String sql = "INSERT INTO accounts (username, encoded_credential, role) VALUES (?, ?, ?)";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, user.getUsername());
            statement.setString(2, user.getPassword());
            statement.setString(3, user.getRole().name());
            statement.executeUpdate();
            // An INSERT-only SQL identity cannot SELECT the owner-issued revision.
            // Never echo a caller-supplied stale incarnation token as if it were authoritative.
            User inserted = copy(user);
            inserted.setSessionRevision(null);
            return inserted;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("username is already registered", exception);
            }
            throw new IllegalStateException("could not save account", exception);
        }
    }

    @Override
    public boolean updateCredentialIfCurrent(String username, String expectedEncoded,
            String replacementEncoded) {
        requireText(username, "username");
        requireText(expectedEncoded, "expected encoded credential");
        requireText(replacementEncoded, "replacement encoded credential");
        String sql = database.isH2()
                ? "UPDATE accounts SET encoded_credential = ?, session_revision = ? "
                    + "WHERE username = ? AND encoded_credential = ?"
                : "UPDATE accounts SET encoded_credential = ? "
                    + "WHERE username = ? AND encoded_credential = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, replacementEncoded);
            int keyIndex = 2;
            if (database.isH2()) statement.setObject(keyIndex++, UUID.randomUUID());
            statement.setString(keyIndex++, username);
            statement.setString(keyIndex, expectedEncoded);
            return statement.executeUpdate() == 1;
        } catch (SQLException exception) {
            throw new IllegalStateException("could not rotate account credential", exception);
        }
    }

    @Override
    public boolean updateRoleIfCurrent(String username, AccountRole expectedRole,
            AccountRole replacementRole) {
        requireText(username, "username");
        Objects.requireNonNull(expectedRole, "expectedRole");
        Objects.requireNonNull(replacementRole, "replacementRole");
        return inGuardedAccountTransaction(connection -> {
            if (expectedRole == AccountRole.ADMINISTRATOR
                    && replacementRole != AccountRole.ADMINISTRATOR) {
                List<String> administrators = lockAdministratorAccounts(connection);
                if (administrators.size() == 1 && administrators.contains(username)) {
                    throw new SecurityException("cannot demote the last administrator");
                }
            }
            String sql = database.isH2()
                    ? "UPDATE accounts SET role = ?, session_revision = ? WHERE username = ? AND role = ?"
                    : "UPDATE accounts SET role = ? WHERE username = ? AND role = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, replacementRole.name());
                int keyIndex = 2;
                if (database.isH2()) statement.setObject(keyIndex++, UUID.randomUUID());
                statement.setString(keyIndex++, username);
                statement.setString(keyIndex, expectedRole.name());
                return statement.executeUpdate() == 1;
            }
        });
    }

    private static void requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    @Override
    public Optional<User> findByUsername(String username) {
        if (username == null) return Optional.empty();
        String sql = "SELECT username, encoded_credential, role, session_revision FROM accounts WHERE username = ?";
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, username);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Optional.of(map(result)) : Optional.empty();
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("could not find account", exception);
        }
    }

    @Override
    public boolean removeByUsername(String username) {
        if (username == null) return false;
        return inGuardedAccountTransaction(connection -> {
            List<String> administrators = lockAdministratorAccounts(connection);
            if (administrators.size() == 1 && administrators.contains(username)) {
                throw new SecurityException("cannot delete the last administrator");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM accounts WHERE username = ?")) {
                statement.setString(1, username);
                return statement.executeUpdate() > 0;
            }
        });
    }

    @Override
    public List<User> findAll() {
        List<User> accounts = new ArrayList<>();
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT username, encoded_credential, role, session_revision FROM accounts ORDER BY username");
                ResultSet result = statement.executeQuery()) {
            while (result.next()) accounts.add(map(result));
            return Collections.unmodifiableList(accounts);
        } catch (SQLException exception) {
            throw new IllegalStateException("could not list accounts", exception);
        }
    }

    /**
     * Serializes supported JDBC administrator reductions by locking administrator
     * rows before checking the final-account invariant. SERIALIZABLE transactions
     * also reject conflicting changes rather than permitting write skew.
     * Raw DBA SQL and other repository implementations remain external boundaries.
     */
    private static List<String> lockAdministratorAccounts(Connection connection) throws SQLException {
        List<String> administrators = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT username FROM accounts WHERE role = ? ORDER BY username FOR UPDATE")) {
            statement.setString(1, AccountRole.ADMINISTRATOR.name());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) administrators.add(result.getString(1));
            }
        }
        return administrators;
    }


    /**
     * Lock and revalidate the authenticated actor INSIDE the same transaction
     * as the privileged account mutation. Generic repository methods cannot
     * serve as a substitute: they do not carry verified actor authority.
     */
    private void assertCurrentAdministrator(Connection connection, User actor, String plaintext)
            throws SQLException {
        if (actor == null || plaintext == null
                || actor.getRole() != AccountRole.ADMINISTRATOR
                || actor.getSessionRevision() == null) {
            throw new SecurityException("current administrator credentials required");
        }
        String sql = "SELECT username, encoded_credential, role, session_revision "
                + "FROM accounts WHERE username = ? FOR UPDATE";
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, actor.getUsername());
            try (ResultSet rows = query.executeQuery()) {
                if (!rows.next()) throw new SecurityException("administrator account missing");
                User current = map(rows);
                if (current.getRole() != AccountRole.ADMINISTRATOR
                        || !Objects.equals(current.getSessionRevision(), actor.getSessionRevision())
                        || !Objects.equals(current.getPassword(), actor.getPassword())
                        || !new Pbkdf2PasswordHasher().matches(plaintext, current.getPassword())) {
                    throw new SecurityException("administrator was revoked or credentials changed");
                }
            }
        }
    }

    @Override
    public User provisionAuthorized(User actor, String plaintext, User newUser) {
        validate(newUser);
        if (!newUser.getPassword().startsWith("pbkdf2$")) {
            throw new SecurityException("encoded credential required for provisioning");
        }
        return inGuardedAccountTransaction(connection -> {
            assertCurrentAdministrator(connection, actor, plaintext);
            String sql = "INSERT INTO accounts (username, encoded_credential, role) VALUES (?, ?, ?)";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, newUser.getUsername());
                statement.setString(2, newUser.getPassword());
                statement.setString(3, newUser.getRole().name());
                statement.executeUpdate();
                User inserted = copy(newUser);
                inserted.setSessionRevision(null);
                return inserted;
            } catch (SQLException failure) {
                if ("23505".equals(failure.getSQLState())) {
                    throw new IllegalArgumentException("username is already registered", failure);
                }
                throw failure;
            }
        });
    }

    @Override
    public boolean changeRoleAuthorized(User actor, String plaintext, String username,
            AccountRole expectedRole, AccountRole replacementRole) {
        requireText(username, "target username");
        Objects.requireNonNull(expectedRole, "expectedRole");
        Objects.requireNonNull(replacementRole, "replacementRole");
        return inGuardedAccountTransaction(connection -> {
            assertCurrentAdministrator(connection, actor, plaintext);
            if (Objects.equals(actor.getUsername(), username)
                    && replacementRole != AccountRole.ADMINISTRATOR) {
                throw new SecurityException("cannot demote current administrator");
            }
            if (expectedRole == AccountRole.ADMINISTRATOR
                    && replacementRole != AccountRole.ADMINISTRATOR) {
                List<String> administrators = lockAdministratorAccounts(connection);
                if (administrators.size() == 1 && administrators.contains(username)) {
                    throw new SecurityException("cannot demote last administrator");
                }
            }
            String sql = database.isH2()
                    ? "UPDATE accounts SET role = ?, session_revision = ? WHERE username = ? AND role = ?"
                    : "UPDATE accounts SET role = ? WHERE username = ? AND role = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, replacementRole.name());
                int index = 2;
                if (database.isH2()) statement.setObject(index++, UUID.randomUUID());
                statement.setString(index++, username);
                statement.setString(index, expectedRole.name());
                return statement.executeUpdate() == 1;
            }
        });
    }

    @Override
    public boolean removeAuthorized(User actor, String plaintext, String username) {
        requireText(username, "target username");
        return inGuardedAccountTransaction(connection -> {
            assertCurrentAdministrator(connection, actor, plaintext);
            if (Objects.equals(actor.getUsername(), username)) {
                throw new SecurityException("cannot delete current administrator");
            }
            List<String> administrators = lockAdministratorAccounts(connection);
            if (administrators.size() == 1 && administrators.contains(username)) {
                throw new SecurityException("cannot delete last administrator");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM accounts WHERE username = ?")) {
                statement.setString(1, username);
                return statement.executeUpdate() > 0;
            }
        });
    }

    private <T> T inGuardedAccountTransaction(SqlWork<T> operation) {
        try (Connection connection = database.openConnection()) {
            connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            connection.setAutoCommit(false);
            try {
                T result = operation.execute(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException failure) {
                connection.rollback();
                throw failure;
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("guarded account mutation failed", failure);
        }
    }

    private interface SqlWork<T> {
        T execute(Connection connection) throws SQLException;
    }

    private static User map(ResultSet result) throws SQLException {
        User user = new User();
        user.setUsername(result.getString("username"));
        user.setPassword(result.getString("encoded_credential"));
        user.setRole(AccountRole.valueOf(result.getString("role")));
        user.setSessionRevision(result.getString("session_revision"));
        return user;
    }

    private static User copy(User source) {
        User copy = new User();
        copy.setUsername(source.getUsername());
        copy.setPassword(source.getPassword());
        copy.setRole(source.getRole());
        copy.setSessionRevision(source.getSessionRevision());
        return copy;
    }

    private static void validate(User user) {
        Objects.requireNonNull(user, "user");
        if (user.getUsername() == null || user.getUsername().trim().isEmpty()) {
            throw new IllegalArgumentException("username must not be blank");
        }
        if (user.getPassword() == null || user.getPassword().trim().isEmpty()) {
            throw new IllegalArgumentException("encoded credential must not be blank");
        }
        Objects.requireNonNull(user.getRole(), "role");
    }
}
