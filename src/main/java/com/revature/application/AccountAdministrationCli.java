package com.revature.application;

import java.io.Console;
import java.util.Arrays;
import java.util.Locale;
import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.UserLoginService;

/**
 * Offline, interactive operator-only account administration.
 *
 * This command is not wired into the application menu or exposed as an HTTP/API
 * route. Database identities, operator approval and terminal custody are
 * deployment trust boundaries; console output is not a durable audit ledger.
 */
public final class AccountAdministrationCli {
    enum Action { PROVISION, CHANGE_ROLE, DELETE }

    private AccountAdministrationCli() { }

    public static void main(String[] args) {
        Action action = parseAction(args);
        Console console = System.console();
        if (console == null) {
            throw new SecurityException("account administration requires a local interactive terminal");
        }

        String url = requireText(console.readLine("Persistent JDBC URL: "), "JDBC URL");
        String identityPrincipal = requireText(
                console.readLine("Read-only identity database principal: "), "identity principal");
        String operatorPrincipal = requireText(
                console.readLine("Privileged account database principal: "), "operator principal");
        validateSeparatePrincipals(url, identityPrincipal, operatorPrincipal);

        char[] identitySecret = null;
        char[] operatorSecret = null;
        char[] administratorSecret = null;
        char[] newAccountSecret = null;
        char[] newAccountConfirmation = null;
        User administratorCredentials = null;
        try {
            identitySecret = requiredSecret(console.readPassword("Identity database password: "),
                    "identity database password");
            operatorSecret = requiredSecret(console.readPassword("Privileged database password: "),
                    "privileged database password");
            String adminUsername = requireText(console.readLine("Approving administrator username: "),
                    "administrator username");
            administratorSecret = requiredSecret(console.readPassword("Administrator login password: "),
                    "administrator login password");
            String changeReference = requireText(
                    console.readLine("Approved change-ticket reference: "), "change-ticket reference");
            String targetUsername = requireText(console.readLine("Target account username: "),
                    "target username");

            JdbcDatabase identityDatabase = new JdbcDatabase(url, identityPrincipal,
                    new String(identitySecret));
            JdbcDatabase privilegedDatabase = new JdbcDatabase(url, operatorPrincipal,
                    new String(operatorSecret));
            UserLoginService service = new UserLoginService(
                    new JdbcUserAccountRepository(identityDatabase),
                    new JdbcUserAccountRepository(privilegedDatabase));
            administratorCredentials = credentials(adminUsername, new String(administratorSecret));

            AccountRole expectedRole = null;
            AccountRole replacementRole = null;
            String newAccountPassword = null;
            if (action == Action.PROVISION) {
                replacementRole = readRole(console, "New staff/administrator role: ");
                newAccountSecret = requiredSecret(console.readPassword("New account password: "),
                        "new account password");
                newAccountConfirmation = requiredSecret(console.readPassword("Confirm new account password: "),
                        "new account confirmation");
                if (!Arrays.equals(newAccountSecret, newAccountConfirmation)) {
                    throw new SecurityException("new account password confirmation failed");
                }
                newAccountPassword = new String(newAccountSecret);
            } else if (action == Action.CHANGE_ROLE) {
                expectedRole = readRole(console, "Expected current account role: ");
                replacementRole = readRole(console, "Replacement account role: ");
            } else {
                String confirmation = console.readLine("Type DELETE " + targetUsername + " to confirm: ");
                if (!("DELETE " + targetUsername).equals(confirmation)) {
                    throw new SecurityException("account deletion confirmation failed");
                }
            }

            boolean changed = execute(service, administratorCredentials, action,
                    targetUsername, expectedRole, replacementRole, newAccountPassword);
            if (!changed) {
                throw new IllegalStateException("account was not changed; verify target and expected role");
            }
            // Operational receipt only. An immutable, external audit trail is still required.
            console.printf("Operation completed: %s; actor=%s; target=%s; ticket=%s%n",
                    action, adminUsername, targetUsername, changeReference);
        } finally {
            if (administratorCredentials != null) administratorCredentials.setPassword(null);
            clear(identitySecret);
            clear(operatorSecret);
            clear(administratorSecret);
            clear(newAccountSecret);
            clear(newAccountConfirmation);
        }
    }

    static Action parseAction(String[] args) {
        if (args == null || args.length != 1) {
            throw new SecurityException("exactly one explicit operator action flag is required");
        }
        if ("--provision-staff".equals(args[0])) return Action.PROVISION;
        if ("--change-role".equals(args[0])) return Action.CHANGE_ROLE;
        if ("--delete-account".equals(args[0])) return Action.DELETE;
        throw new SecurityException("unsupported account administration command");
    }

    /** Separate login principals are mandatory even when the JDBC URL is shared. */
    static void validateSeparatePrincipals(String url, String identityPrincipal,
            String operatorPrincipal) {
        String normalizedUrl = requireText(url, "JDBC URL").toLowerCase(Locale.ROOT);
        if (!normalizedUrl.startsWith("jdbc:postgresql://")
                && !normalizedUrl.startsWith("jdbc:h2:file:")) {
            throw new SecurityException("operator workflow requires a persistent PostgreSQL or H2-file URL");
        }
        if (normalizedUrl.contains("password=") || normalizedUrl.contains("user=")
                || normalizedUrl.contains("username=") || normalizedUrl.contains("@")) {
            throw new SecurityException("JDBC URL must not embed a username or password");
        }
        String identity = requireText(identityPrincipal, "identity principal");
        String operator = requireText(operatorPrincipal, "operator principal");
        if (identity.equalsIgnoreCase(operator)) {
            throw new SecurityException("operator and identity database principals must differ");
        }
    }

    static boolean execute(UserLoginService service, User administratorCredentials, Action action,
            String targetUsername, AccountRole expectedRole, AccountRole replacementRole,
            String newAccountPassword) {
        Objects.requireNonNull(service, "service");
        Objects.requireNonNull(action, "action");
        String target = requireText(targetUsername, "target username");
        switch (action) {
            case PROVISION:
                if (replacementRole != AccountRole.EMPLOYEE
                        && replacementRole != AccountRole.ADMINISTRATOR) {
                    throw new IllegalArgumentException("provisioning here is limited to staff and administrators");
                }
                service.provisionUser(administratorCredentials, target,
                        requireText(newAccountPassword, "new account password"), replacementRole);
                return true;
            case CHANGE_ROLE:
                return service.changeRole(administratorCredentials, target,
                        Objects.requireNonNull(expectedRole, "expectedRole"),
                        Objects.requireNonNull(replacementRole, "replacementRole"));
            case DELETE:
                return service.removeUser(administratorCredentials, target);
            default:
                throw new IllegalStateException("unknown account administration action");
        }
    }

    private static AccountRole readRole(Console console, String prompt) {
        String roleName = requireText(console.readLine(prompt), "role");
        return AccountRole.valueOf(roleName.toUpperCase(Locale.ROOT));
    }

    private static User credentials(String username, String password) {
        User value = new User();
        value.setUsername(username);
        value.setPassword(password);
        return value;
    }

    private static char[] requiredSecret(char[] value, String name) {
        if (value == null || value.length == 0) {
            clear(value);
            throw new SecurityException(name + " is required");
        }
        return value;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(field + " must not be blank or padded");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException(field + " must not contain control characters");
            }
        }
        return value;
    }

    private static void clear(char[] value) {
        if (value != null) Arrays.fill(value, '\0');
    }
}
