package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.DAOService.DAOService;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.SnapshotRehydrator;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.LegacyPbkdf2CredentialUpgrade;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;

/**
 * Integrates a separately authorized, opt-in one-time credential rotation with
 * the unchanged application login command and its verified session gate.
 * No production runtime or public self-service migration is enabled.
 */
@Tag("SECURITY")
class LegacyCredentialApplicationSessionIntegrationTest {

    @Test
    void authorizedMemoryUpgradeEnablesFreshVerifiedAppLoginWithoutPrivilegeEscalation()
            throws Exception {
        verifyAppSessionAfterUpgrade(new InMemoryUserAccountRepository());
    }

    @Test
    void authorizedJdbcUpgradeEnablesFreshVerifiedAppLoginWithoutPrivilegeEscalation()
            throws Exception {
        verifyAppSessionAfterUpgrade(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("legacy_app_session_" + System.nanoTime())));
    }

    private static void verifyAppSessionAfterUpgrade(UserAccountRepository accounts)
            throws Exception {
        User source = new User();
        source.setUsername("legacy-buyer");
        source.setRole(AccountRole.CUSTOMER);
        source.setPassword(legacyEncoded("LegacyPass123!"));
        accounts.save(source);

        DealershipApplicationContext context = new DealershipApplicationContext(
                accounts, new RoleAuthorizationService(), new SnapshotRehydrator(),
                new DAOService(), null);
        AuthenticateAccountCommand command = new AuthenticateAccountCommand(context);
        User credentials = credentials("legacy-buyer", "LegacyPass123!");
        User before = accounts.findByUsername("legacy-buyer").orElseThrow();

        // The ordinary application has no migration writer and rejects weak hashes.
        assertEquals(AuthenticateAccountCommand.Status.INVALID_CREDENTIALS,
                command.execute(AccountRole.CUSTOMER, credentials,
                        Permission.VIEW_INVENTORY).getStatus());
        assertFalse(context.getAuthorizationService().isAuthorized(
                before, Permission.VIEW_INVENTORY));
        assertEquals(before.getPassword(),
                accounts.findByUsername("legacy-buyer").orElseThrow().getPassword());

        // Separate operator-controlled migration: synthetic fixture with explicitly
        // supplied writer, not an application/composition-root default.
        UserLoginService controlledMigration = new UserLoginService(accounts, null,
                new Pbkdf2PasswordHasher(),
                new LegacyPbkdf2CredentialUpgrade(accounts));
        User upgraded = controlledMigration.authenticate(credentials).orElseThrow();
        assertEquals("600000", upgraded.getPassword().split("\\$", -1)[1]);
        assertNotEquals(before.getSessionRevision(), upgraded.getSessionRevision());

        // The actual unchanged application Identity + Authorization adapters
        // must establish a verified session using the newly persisted account.
        AuthenticateAccountCommand.Result result = command.execute(
                AccountRole.CUSTOMER, credentials, Permission.VIEW_INVENTORY);
        assertEquals(AuthenticateAccountCommand.Status.AUTHENTICATED, result.getStatus());
        User active = result.getAccount().orElseThrow();
        assertEquals(active, context.getCurrentAccount());
        assertTrue(context.getAuthorizationService().isAuthorized(
                active, Permission.VIEW_INVENTORY));
        assertFalse(context.getAuthorizationService().isAuthorized(
                active, Permission.MANAGE_INVENTORY));
        assertFalse(context.getAuthorizationService().isAuthorized(
                active, Permission.MANAGE_USERS));

        // Copying stored identity attributes never creates a session.
        User impersonator = new User();
        impersonator.setUsername(active.getUsername());
        impersonator.setRole(AccountRole.ADMINISTRATOR);
        impersonator.setPassword(active.getPassword());
        impersonator.setSessionRevision(active.getSessionRevision());
        assertFalse(context.getAuthorizationService().isAuthorized(
                impersonator, Permission.MANAGE_USERS));

        // A new credential change revokes even the newly verified session.
        assertTrue(accounts.updateCredentialIfCurrent(
                "legacy-buyer", active.getPassword(),
                new Pbkdf2PasswordHasher().hash("RotatedPass123!")));
        assertFalse(context.getAuthorizationService().isAuthorized(
                active, Permission.VIEW_INVENTORY));
        assertEquals(AuthenticateAccountCommand.Status.INVALID_CREDENTIALS,
                command.execute(AccountRole.CUSTOMER, credentials,
                        Permission.VIEW_INVENTORY).getStatus());
        assertEquals(AuthenticateAccountCommand.Status.AUTHENTICATED,
                command.execute(AccountRole.CUSTOMER,
                        credentials("legacy-buyer", "RotatedPass123!"),
                        Permission.VIEW_INVENTORY).getStatus());
    }

    private static User credentials(String username, String password) {
        User result = new User();
        result.setUsername(username);
        result.setPassword(password);
        return result;
    }

    private static String legacyEncoded(String plaintext) throws Exception {
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        PBEKeySpec input = new PBEKeySpec(plaintext.toCharArray(), salt, 120_000, 256);
        try {
            byte[] key = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                    .generateSecret(input).getEncoded();
            return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(key);
        } finally {
            input.clearPassword();
        }
    }
}
