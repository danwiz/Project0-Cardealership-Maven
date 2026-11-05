package com.revature.application;

import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.service.Permission;

public final class AuthenticateAccountCommand {
    public enum Status { AUTHENTICATED, INVALID_CREDENTIALS, ACCESS_DENIED }

    public static final class Result {
        private final Status status;
        private final User account;
        private Result(Status status, User account) { this.status = status; this.account = account; }
        public static Result authenticated(User account) {
            return new Result(Status.AUTHENTICATED, Objects.requireNonNull(account, "account"));
        }
        public static Result failure(Status status) {
            if (status == Status.AUTHENTICATED) throw new IllegalArgumentException("failure status must not be AUTHENTICATED");
            return new Result(status, null);
        }
        public Status getStatus() { return status; }
        public Optional<User> getAccount() { return Optional.ofNullable(account); }
        public boolean isAuthenticated() { return status == Status.AUTHENTICATED; }
    }

    private final ApplicationPorts.Identity identity;
    private final ApplicationPorts.Authorization authorization;

    public AuthenticateAccountCommand(DealershipApplicationContext context) {
        this(new ContextIdentityAdapter(context), new ContextDomainAdapter(context));
    }

    public AuthenticateAccountCommand(ApplicationPorts.Identity identity,
            ApplicationPorts.Authorization authorization) {
        this.identity = Objects.requireNonNull(identity, "identity");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
    }

    public Result execute(AccountRole role, User credentials, Permission permission) {
        Objects.requireNonNull(permission, "permission");
        Optional<User> authenticated = identity.authenticate(Objects.requireNonNull(role, "role"), credentials);
        if (!authenticated.isPresent()) {
            identity.setCurrentAccount(null);
            return Result.failure(Status.INVALID_CREDENTIALS);
        }
        User account = authenticated.get();
        if (!authorization.isAuthorized(account, permission)) {
            identity.setCurrentAccount(null);
            return Result.failure(Status.ACCESS_DENIED);
        }
        identity.setCurrentAccount(account);
        return Result.authenticated(account);
    }
}