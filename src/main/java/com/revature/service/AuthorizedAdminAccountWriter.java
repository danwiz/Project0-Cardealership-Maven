package com.revature.service;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Dedicated account-administration capability. Each operation MUST prove that
 * the acting administrator has a current persisted generation and valid
 * credential inside the same atomic write boundary as the target mutation.
 *
 * Generic UserAccountRepository writes are not a substitute for this port.
 */
public interface AuthorizedAdminAccountWriter {
    User provisionAuthorized(User authenticatedActor, String actorPlaintext, User newUser);

    boolean changeRoleAuthorized(User authenticatedActor, String actorPlaintext,
            String targetUsername, AccountRole expectedRole, AccountRole replacementRole);

    boolean removeAuthorized(User authenticatedActor, String actorPlaintext,
            String targetUsername);
}
