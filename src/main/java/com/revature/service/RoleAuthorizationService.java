package com.revature.service;

import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Central role policy and (when wired to an application context) verified session gate.
 * A caller-controlled User is NOT a proof of authentication.
 */
public class RoleAuthorizationService {

    private static final Map<AccountRole, EnumSet<Permission>> PERMISSIONS_BY_ROLE = Map.of(
            AccountRole.CUSTOMER, EnumSet.of(
                    Permission.VIEW_INVENTORY,
                    Permission.REQUEST_PURCHASE,
                    Permission.VIEW_OWNED_VEHICLES,
                    Permission.VIEW_OWN_PAYMENTS,
                    Permission.RECORD_PAYMENTS),
            AccountRole.EMPLOYEE, EnumSet.of(
                    Permission.VIEW_INVENTORY,
                    Permission.MANAGE_INVENTORY,
                    Permission.REVIEW_PURCHASE_REQUESTS,
                    Permission.VIEW_CUSTOMER_PAYMENTS,
                    Permission.RECORD_PAYMENTS),
            AccountRole.ADMINISTRATOR, EnumSet.allOf(Permission.class));

    private final Map<User, VerifiedSession> sessions =
            Collections.synchronizedMap(new IdentityHashMap<User, VerifiedSession>());
    private volatile Supplier<UserAccountRepository> repositorySupplier;

    /**
     * Enables fail-closed verified sessions once per application context.
     * The supplier follows the repository when a validated snapshot is rehydrated.
     */
    public synchronized void requireVerifiedSessions(Supplier<UserAccountRepository> source) {
        Objects.requireNonNull(source, "source");
        if (repositorySupplier != null) {
            throw new IllegalStateException("verified session mode is already configured");
        }
        repositorySupplier = source;
        clearVerifiedSessions();
    }

    /**
     * A login adapter may bind a session only after checking plaintext credentials
     * independently against the current persisted PBKDF2 credential hash. No
     * externally supplied role or username is accepted as authority.
     */
    public void establishVerifiedSession(User account, User credentials) {
        Supplier<UserAccountRepository> source = repositorySupplier;
        if (source == null) {
            // Standalone instances remain policy-only for legacy isolated callers.
            return;
        }
        if (account == null || credentials == null
                || account.getUsername() == null || credentials.getPassword() == null
                || !Objects.equals(account.getUsername(), credentials.getUsername())) {
            throw new SecurityException("verified credentials are required");
        }
        User persisted = source.get().findByUsername(account.getUsername())
                .orElseThrow(() -> new SecurityException("authenticated account no longer exists"));
        if (persisted.getSessionRevision() == null
                || !Objects.equals(persisted.getSessionRevision(), account.getSessionRevision())
                || persisted.getRole() == null || persisted.getRole() != account.getRole()
                || !Objects.equals(persisted.getPassword(), account.getPassword())
                || !new Pbkdf2PasswordHasher().matches(credentials.getPassword(), persisted.getPassword())) {
            throw new SecurityException("authenticated identity does not match persisted credentials");
        }
        sessions.put(account, new VerifiedSession(
                persisted.getUsername(), persisted.getRole(), persisted.getPassword(),
                persisted.getSessionRevision()));
    }

    /** True only when this policy checks an authenticated session against persisted accounts. */
    public boolean enforcesVerifiedSessions() {
        return repositorySupplier != null;
    }

    public void clearVerifiedSessions() {
        sessions.clear();
    }

    public boolean isAuthorized(User account, Permission permission) {
        if (account == null || account.getRole() == null || permission == null) return false;
        Supplier<UserAccountRepository> source = repositorySupplier;
        if (source != null) {
            VerifiedSession session = sessions.get(account);
            if (session == null) return false;
            if (!session.matches(account)) {
                sessions.remove(account);
                return false;
            }
            Optional<User> current = source.get().findByUsername(session.username);
            if (!current.isPresent() || !session.matches(current.get())) {
                // A revoked actor must not reawaken if the same role/hash returns.
                sessions.remove(account);
                return false;
            }
        }
        return PERMISSIONS_BY_ROLE.getOrDefault(account.getRole(), EnumSet.noneOf(Permission.class))
                .contains(permission);
    }

    public void requireAuthorized(User account, Permission permission) {
        if (!isAuthorized(account, permission)) {
            throw new SecurityException("account is not authorized for " + permission);
        }
    }

    private static final class VerifiedSession {
        private final String username;
        private final AccountRole role;
        private final String passwordHash;
        private final String sessionRevision;

        private VerifiedSession(String username, AccountRole role, String passwordHash,
                String sessionRevision) {
            this.username = username;
            this.role = role;
            this.passwordHash = passwordHash;
            this.sessionRevision = sessionRevision;
        }

        private boolean matches(User account) {
            return account != null
                    && Objects.equals(username, account.getUsername())
                    && role == account.getRole()
                    && Objects.equals(passwordHash, account.getPassword())
                    && sessionRevision != null
                    && Objects.equals(sessionRevision, account.getSessionRevision());
        }
    }
}
