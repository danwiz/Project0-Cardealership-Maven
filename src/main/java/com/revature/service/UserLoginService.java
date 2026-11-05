package com.revature.service;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

public class UserLoginService {

    private final UserAccountRepository userRepository;
    private final UserAccountRepository administrativeRepository;
    private final PasswordHasher passwordHasher;
    // Default null: legacy credentials are denied until an authorized migration
    // explicitly provisions a trusted, scoped credential writer.
    private final LegacyPbkdf2CredentialUpgrade legacyCredentialUpgrade;

    public UserLoginService() {
        this(new InMemoryUserAccountRepository(), new Pbkdf2PasswordHasher());
    }

    public UserLoginService(UserAccountRepository userRepository) {
        this(userRepository, userRepository, new Pbkdf2PasswordHasher());
    }

    public UserLoginService(UserAccountRepository userRepository, PasswordHasher passwordHasher) {
        this(userRepository, userRepository, passwordHasher);
    }

    /**
     * Explicitly separate identity storage from privileged account administration.
     * Null administrativeRepository disables provisioning, role changes and deletion.
     * Ordinary JDBC runtime composition intentionally passes null.
     */
    public UserLoginService(UserAccountRepository userRepository,
            UserAccountRepository administrativeRepository) {
        this(userRepository, administrativeRepository, new Pbkdf2PasswordHasher());
    }

    public UserLoginService(UserAccountRepository userRepository,
            UserAccountRepository administrativeRepository, PasswordHasher passwordHasher) {
        this(userRepository, administrativeRepository, passwordHasher, null);
    }

    /**
     * The migration capability is opt-in and must be supplied by a trusted
     * operator with an approved credential-only writer. Ordinary runtime
     * composition does not grant it or weaken modern hash verification.
     */
    public UserLoginService(UserAccountRepository userRepository,
            UserAccountRepository administrativeRepository, PasswordHasher passwordHasher,
            LegacyPbkdf2CredentialUpgrade legacyCredentialUpgrade) {
        this.userRepository = Objects.requireNonNull(userRepository, "userRepository");
        this.administrativeRepository = administrativeRepository;
        this.passwordHasher = Objects.requireNonNull(passwordHasher, "passwordHasher");
        this.legacyCredentialUpgrade = legacyCredentialUpgrade;
    }

    /** Public self-registration always creates a CUSTOMER. */
    public User registerUser(String username, String password) {
        return registerWithRole(username, password, AccountRole.CUSTOMER);
    }

    /**
     * Privileged role assignment is available only to a previously authenticated
     * administrator. First-admin bootstrap requires a separate trusted process.
     */
    public User provisionUser(User operatorCredentials, String username,
            String password, AccountRole role) {
        UserAccountRepository administrator = requireAdministrativeRepository();
        User operator = authenticate(operatorCredentials)
                .orElseThrow(() -> new SecurityException("administrator authentication required"));
        new RoleAuthorizationService().requireAuthorized(operator, Permission.MANAGE_USERS);
        return registerWithRole(username, password, role, administrator);
    }

    /**
     * A user may change their own password only by supplying their current
     * plaintext credentials. Compare-and-swap prevents stale updates.
     */
    public boolean changePassword(User currentCredentials, String replacementPassword) {
        validateCredential("replacementPassword", replacementPassword);
        User existing = authenticate(currentCredentials)
                .orElseThrow(() -> new SecurityException("current account authentication required"));
        return userRepository.updateCredentialIfCurrent(existing.getUsername(),
                existing.getPassword(), passwordHasher.hash(replacementPassword));
    }

    /**
     * Only a currently authenticated persisted administrator may reassign roles.
     * The expected role is checked atomically against the repository.
     */
    public boolean changeRole(User administratorCredentials, String targetUsername,
            AccountRole expectedRole, AccountRole replacementRole) {
        validateCredential("targetUsername", targetUsername);
        Objects.requireNonNull(expectedRole, "expectedRole");
        Objects.requireNonNull(replacementRole, "replacementRole");
        UserAccountRepository administratorWriter = requireAdministrativeRepository();
        User administrator = authenticate(administratorCredentials)
                .orElseThrow(() -> new SecurityException("administrator authentication required"));
        new RoleAuthorizationService().requireAuthorized(administrator, Permission.MANAGE_USERS);
        if (Objects.equals(administrator.getUsername(), targetUsername)
                && replacementRole != AccountRole.ADMINISTRATOR) {
            throw new SecurityException("an administrator cannot demote their own account");
        }
        return administratorWriter.updateRoleIfCurrent(targetUsername, expectedRole, replacementRole);
    }

    private User registerWithRole(String username, String password, AccountRole role) {
        return registerWithRole(username, password, role, userRepository);
    }

    private User registerWithRole(String username, String password, AccountRole role,
            UserAccountRepository writer) {
        validateCredential("username", username);
        validateCredential("password", password);
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(writer, "writer");
        if (userRepository.findByUsername(username).isPresent()) {
            throw new IllegalArgumentException("username is already registered");
        }
        User account = new User();
        account.setUsername(username);
        account.setPassword(passwordHasher.hash(password));
        account.setRole(role);
        return writer.save(account);
    }

    private UserAccountRepository requireAdministrativeRepository() {
        if (administrativeRepository == null) {
            throw new SecurityException("privileged account administration is not configured for this runtime");
        }
        return administrativeRepository;
    }

    public boolean removeUser(User operatorCredentials, String targetUsername) {
        validateCredential("targetUsername", targetUsername);
        UserAccountRepository administratorWriter = requireAdministrativeRepository();
        User operator = authenticate(operatorCredentials)
                .orElseThrow(() -> new SecurityException("administrator authentication required"));
        new RoleAuthorizationService().requireAuthorized(operator, Permission.MANAGE_USERS);
        if (Objects.equals(operator.getUsername(), targetUsername)) {
            throw new SecurityException("an administrator cannot delete their own account");
        }
        return administratorWriter.removeByUsername(targetUsername);
    }

    /** The legacy unauthenticated deletion API fails closed. */
    @Deprecated
    public void removeUser(User user) {
        throw new SecurityException("administrator authentication required for account deletion");
    }

    public Optional<User> authenticate(User credentials) {
        if (credentials == null || isBlank(credentials.getUsername()) || credentials.getPassword() == null) {
            return Optional.empty();
        }
        Optional<User> persisted = userRepository.findByUsername(credentials.getUsername());
        if (!persisted.isPresent()) return Optional.empty();
        if (passwordHasher.matches(credentials.getPassword(), persisted.get().getPassword())) {
            return persisted;
        }
        if (legacyCredentialUpgrade == null) return Optional.empty();
        // Verification of a historical 120k hash, its CAS rotation and a fresh
        // persisted 600k account read are required before authentication succeeds.
        return legacyCredentialUpgrade.verifyAndUpgrade(
                persisted.get(), credentials.getPassword(), userRepository, passwordHasher);
    }

    public boolean authenticateUser(User credentials) {
        return authenticate(credentials).isPresent();
    }

    public List<User> getUsers() {
        return userRepository.findAll();
    }

    public String[] getUserNames() {
        List<User> users = getUsers();
        String[] usernames = new String[users.size()];
        for (int i = 0; i < users.size(); i++) usernames[i] = users.get(i).getUsername();
        return usernames;
    }

    private static void validateCredential(String fieldName, String value) {
        if (value == null) throw new IllegalArgumentException(fieldName + " must not be null");
        if (isBlank(value)) throw new IllegalArgumentException(fieldName + " must not be blank");
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
