package com.revature.service;

import java.util.List;
import java.util.Optional;

import com.revature.cardealer.User;

/**
 * Persistence boundary for user accounts used by authentication services.
 */
public interface UserAccountRepository {

    /** Inserts a new account; never overwrites a pre-existing username. */
    User save(User user);

    /** Atomically rotates an encoded credential only if the stored value is unchanged. */
    boolean updateCredentialIfCurrent(String username, String expectedEncoded, String replacementEncoded);

    /** Atomically changes a role only if the stored role still matches the expected value. */
    boolean updateRoleIfCurrent(String username, com.revature.cardealer.AccountRole expectedRole,
            com.revature.cardealer.AccountRole replacementRole);

    Optional<User> findByUsername(String username);

    boolean removeByUsername(String username);

    List<User> findAll();
}
