package com.revature.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Deterministic in-memory repository that preserves registration order.
 */
public class InMemoryUserAccountRepository implements UserAccountRepository {

    private final Map<String, User> usersByUsername = new LinkedHashMap<>();

    @Override
    public synchronized User save(User user) {
        User snapshot = copy(Objects.requireNonNull(user, "user"));
        requireText(snapshot.getUsername(), "username");
        if (usersByUsername.containsKey(snapshot.getUsername())) {
            throw new IllegalArgumentException("username is already registered");
        }
        // Never retain a caller-provided generation, including when restoring snapshots.
        snapshot.setSessionRevision(UUID.randomUUID().toString());
        usersByUsername.put(snapshot.getUsername(), snapshot);
        return copy(snapshot);
    }

    @Override
    public synchronized boolean updateCredentialIfCurrent(String username,
            String expectedEncoded, String replacementEncoded) {
        requireText(replacementEncoded, "replacement encoded credential");
        User current = usersByUsername.get(username);
        if (current == null || !Objects.equals(current.getPassword(), expectedEncoded)) return false;
        User updated = copy(current);
        updated.setPassword(replacementEncoded);
        updated.setSessionRevision(UUID.randomUUID().toString());
        usersByUsername.put(username, updated);
        return true;
    }

    @Override
    public synchronized boolean updateRoleIfCurrent(String username,
            AccountRole expectedRole, AccountRole replacementRole) {
        Objects.requireNonNull(expectedRole, "expectedRole");
        Objects.requireNonNull(replacementRole, "replacementRole");
        User current = usersByUsername.get(username);
        if (current == null || current.getRole() != expectedRole) return false;
        User updated = copy(current);
        updated.setRole(replacementRole);
        updated.setSessionRevision(UUID.randomUUID().toString());
        usersByUsername.put(username, updated);
        return true;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    @Override
    public synchronized Optional<User> findByUsername(String username) {
        return Optional.ofNullable(usersByUsername.get(username)).map(InMemoryUserAccountRepository::copy);
    }

    @Override
    public synchronized boolean removeByUsername(String username) {
        return usersByUsername.remove(username) != null;
    }

    @Override
    public synchronized List<User> findAll() {
        List<User> copies = new ArrayList<>();
        for (User account : usersByUsername.values()) copies.add(copy(account));
        return Collections.unmodifiableList(copies);
    }
    private static User copy(User source) {
        User result = new User();
        result.setUsername(source.getUsername());
        result.setPassword(source.getPassword());
        result.setRole(source.getRole());
        result.setSessionRevision(source.getSessionRevision());
        return result;
    }
}
