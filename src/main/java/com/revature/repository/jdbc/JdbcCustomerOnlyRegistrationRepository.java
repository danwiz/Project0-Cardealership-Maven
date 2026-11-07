package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.UserAccountRepository;

/**
 * A narrowly scoped customer-registration and account-read adapter.
 *
 * For a separately provisioned PostgreSQL SQL principal that has SELECT and
 * EXECUTE on an owner-controlled, CUSTOMER-only registration function, but
 * NO INSERT, UPDATE, DELETE, or CREATE permissions on the accounts table.
 *
 * This adapter is NOT the generic application account writer; password
 * rotation and administrator mutations intentionally fail closed. Existing
 * production JDBC composition must not silently swap to this adapter until
 * credential-rotation and other application write paths have been reviewed.
 * Schema migrations and function/grant provisioning belong to an independent
 * privileged operator, not this runtime.
 */
public final class JdbcCustomerOnlyRegistrationRepository implements UserAccountRepository {
    private final JdbcDatabase database;
    private final JdbcUserAccountRepository reader;

    public JdbcCustomerOnlyRegistrationRepository(JdbcDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.requireCurrentSchema();
        this.reader = new JdbcUserAccountRepository(database);
    }

    @Override
    public User save(User account) {
        Objects.requireNonNull(account, "account");
        if (account.getRole() != AccountRole.CUSTOMER) {
            throw new SecurityException("public registration may only create CUSTOMER accounts");
        }
        if (account.getUsername() == null || account.getUsername().trim().isEmpty()
                || account.getPassword() == null || !account.getPassword().startsWith("pbkdf2$")) {
            throw new SecurityException("registration requires a username and encoded PBKDF2 credential");
        }

        // This function is owned by a separately authorized schema operator.
        // Its SECURITY DEFINER body forces the CUSTOMER role and generates
        // the session revision itself. No arbitrary role parameter is exposed.
        try (Connection connection = database.openConnection();
                PreparedStatement statement = connection.prepareStatement(
                        "SELECT register_customer_trusted(?, ?)")) {
            statement.setString(1, account.getUsername());
            statement.setString(2, account.getPassword());
            try (ResultSet response = statement.executeQuery()) {
                if (!response.next() || !Objects.equals(account.getUsername(), response.getString(1))
                        || response.next()) {
                    throw new IllegalStateException("unexpected customer registration result");
                }
            }
            User created = copy(account);
            created.setSessionRevision(null);
            return created;
        } catch (SQLException exception) {
            if ("23505".equals(exception.getSQLState())) {
                throw new IllegalArgumentException("username is already registered", exception);
            }
            throw new IllegalStateException("customer-only SQL registration unavailable", exception);
        }
    }

    @Override
    public boolean updateCredentialIfCurrent(String username, String expected, String replacement) {
        throw new SecurityException("credential changes require separately authorized writer");
    }

    @Override
    public boolean updateRoleIfCurrent(String username, AccountRole expected, AccountRole replacement) {
        throw new SecurityException("administrator account mutation unavailable to registration runtime");
    }

    @Override
    public boolean removeByUsername(String username) {
        throw new SecurityException("account deletion unavailable to registration runtime");
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return reader.findByUsername(username);
    }

    @Override
    public List<User> findAll() {
        return reader.findAll();
    }

    private static User copy(User original) {
        User created = new User();
        created.setUsername(original.getUsername());
        created.setPassword(original.getPassword());
        created.setRole(original.getRole());
        created.setSessionRevision(original.getSessionRevision());
        return created;
    }
}
