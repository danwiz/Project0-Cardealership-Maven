package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;

/** Application use case for registering a role-specific account. */
public final class RegisterAccountCommand {
    private final ApplicationPorts.Identity identity;

    public RegisterAccountCommand(DealershipApplicationContext context) {
        this(new ContextIdentityAdapter(context));
    }

    public RegisterAccountCommand(ApplicationPorts.Identity identity) {
        this.identity = Objects.requireNonNull(identity, "identity");
    }

    public User execute(AccountRole role, String username, String password) {
        if (role != AccountRole.CUSTOMER) {
            throw new SecurityException("public registration permits customer accounts only");
        }
        User account = identity.register(role, username, password);
        identity.rememberRegisteredAccount(account);
        return account;
    }
}