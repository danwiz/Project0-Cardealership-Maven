package com.revature.repository.jdbc;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.UserAccountRepository;

/**
 * Dedicated authentication-read adapter for a private SQL principal.
 *
 * This facade MUST NOT be bound to anonymous signup callers. It can return
 * encoded credentials to the trusted verifier service, but exposes no account
 * writes, role changes, listing API or administrator capability.
 *
 * The PostgreSQL principal behind this adapter must have narrowly scoped
 * SELECT permissions and no direct account INSERT/UPDATE/DELETE/DDL grants.
 * Application process/service isolation and actual secret handling remain
 * separately reviewed deployment concerns.
 */
public final class JdbcPrivateAuthenticationReader implements UserAccountRepository {
    private final JdbcUserAccountRepository accountReader;

    public JdbcPrivateAuthenticationReader(JdbcDatabase privateDatabase) {
        Objects.requireNonNull(privateDatabase, "privateDatabase").requireCurrentSchema();
        this.accountReader = new JdbcUserAccountRepository(privateDatabase);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return accountReader.findByUsername(username);
    }

    @Override
    public User save(User account) {
        throw new SecurityException("private authentication reader cannot create accounts");
    }

    @Override
    public boolean updateCredentialIfCurrent(String username, String expected, String replacement) {
        throw new SecurityException("private authentication reader cannot rotate passwords");
    }

    @Override
    public boolean updateRoleIfCurrent(String username, AccountRole expected, AccountRole replacement) {
        throw new SecurityException("private authentication reader cannot change account roles");
    }

    @Override
    public boolean removeByUsername(String username) {
        throw new SecurityException("private authentication reader cannot delete accounts");
    }

    @Override
    public List<User> findAll() {
        throw new SecurityException("bulk identity listing unavailable to authentication reader");
    }
}
