package com.revature.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Unprivileged application-facing projection of the account repository.
 * The internal authentication/provisioning services retain the trusted writer.
 *
 * This boundary prevents caller-controlled account records from being saved or
 * deleted through public composition/context getters, and avoids disclosing
 * encoded credentials through those getters. It is not a remote security boundary.
 */
public final class ReadOnlyUserAccountRepository implements UserAccountRepository {
    private final UserAccountRepository source;

    public ReadOnlyUserAccountRepository(UserAccountRepository source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    @Override
    public User save(User user) {
        throw new SecurityException("account mutation requires an authenticated application service");
    }

    @Override
    public boolean updateCredentialIfCurrent(String username, String expectedEncoded,
            String replacementEncoded) {
        throw new SecurityException("credential rotation requires an authenticated application service");
    }

    @Override
    public boolean updateRoleIfCurrent(String username, AccountRole expectedRole,
            AccountRole replacementRole) {
        throw new SecurityException("role changes require an authenticated administrator");
    }

    @Override
    public boolean removeByUsername(String username) {
        throw new SecurityException("account deletion requires an authenticated application service");
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return source.findByUsername(username).map(ReadOnlyUserAccountRepository::publicAccount);
    }

    @Override
    public List<User> findAll() {
        List<User> projected = new ArrayList<>();
        for (User sourceAccount : source.findAll()) {
            projected.add(publicAccount(sourceAccount));
        }
        return Collections.unmodifiableList(projected);
    }

    private static User publicAccount(User sourceAccount) {
        User result = new User();
        result.setUsername(sourceAccount.getUsername());
        result.setRole(sourceAccount.getRole());
        // Encoded credentials belong only to the internal identity services.
        return result;
    }
}
