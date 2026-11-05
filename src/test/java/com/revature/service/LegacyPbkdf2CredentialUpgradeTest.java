package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;

/**
 * Migration is explicitly opt-in; the strong public PBKDF2 verifier is never
 * downgraded to accept 120k historical credentials on its normal path.
 */
@Tag("SECURITY")
class LegacyPbkdf2CredentialUpgradeTest {

    @Test
    void defaultLoginRejectsHistoricalHashWithoutMigrationCapability() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        seedLegacy(repository, "old", "old-secret");
        assertFalse(new Pbkdf2PasswordHasher().matches(
                "old-secret", repository.findByUsername("old").orElseThrow().getPassword()));
        assertFalse(new UserLoginService(repository).authenticate(credentials("old", "old-secret")).isPresent());
        assertEquals("120000", repository.findByUsername("old")
                .orElseThrow().getPassword().split("\\$", -1)[1]);
    }

    @Test
    void authorizedMemoryMigrationRotatesPasswordHashAndAccountRevision() {
        assertMigrationWorks(new InMemoryUserAccountRepository());
    }

    @Test
    void authorizedJdbcMigrationRotatesPasswordHashAndAccountRevision() {
        assertMigrationWorks(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("legacy_kdf_" + System.nanoTime())));
    }

    @Test
    void incorrectPasswordDoesNotRotateOrAuthorize() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        seedLegacy(repository, "old", "correct");
        User before = repository.findByUsername("old").orElseThrow();
        assertFalse(migrationLogin(repository).authenticate(credentials("old", "wrong")).isPresent());
        User after = repository.findByUsername("old").orElseThrow();
        assertEquals(before.getPassword(), after.getPassword());
        assertEquals(before.getSessionRevision(), after.getSessionRevision());
    }

    @Test
    void oversizedAndForgedWorkFactorsFailBeforeAnyCredentialUpdate() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        String good = legacyEncoded("correct");
        for (String bad : new String[] {
                good.replace("$120000$", "$2147483647$"),
                good.replace("$120000$", "$60000$"),
                good.replace("$120000$", "$0$"),
                good.replace("$120000$", "$120001$"),
                good + "x".repeat(513),
                "pbkdf2$120000$abc$abc",
                "pbkdf2$120000$bad$" + good.split("\\$", -1)[3],
                "pbkdf2$120000$" + good.split("\\$", -1)[2] + "$bad"
        }) {
            User stored = legacyUser("old", bad);
            repository.save(stored);
            assertFalse(migrationLogin(repository)
                    .authenticate(credentials("old", "correct")).isPresent(), "must reject: " + bad.substring(0, 14));
            assertEquals(bad, repository.findByUsername("old").orElseThrow().getPassword());
            repository.removeByUsername("old");
        }
    }

    @Test
    void compareAndSwapConflictFailsClosedWithoutSessionIssue() {
        UserAccountRepository backing = new InMemoryUserAccountRepository();
        seedLegacy(backing, "old", "correct");
        UserAccountRepository rejectingWriter = new UserAccountRepository() {
            @Override public User save(User user) { return backing.save(user); }
            @Override public boolean updateCredentialIfCurrent(String u, String old, String replacement) {
                return false;
            }
            @Override public boolean updateRoleIfCurrent(String u, AccountRole old, AccountRole replacement) {
                return backing.updateRoleIfCurrent(u, old, replacement);
            }
            @Override public Optional<User> findByUsername(String username) {
                return backing.findByUsername(username);
            }
            @Override public List<User> findAll() { return backing.findAll(); }
            @Override public boolean removeByUsername(String username) { return backing.removeByUsername(username); }
        };
        UserLoginService login = new UserLoginService(backing, null,
                new Pbkdf2PasswordHasher(), new LegacyPbkdf2CredentialUpgrade(rejectingWriter));
        assertFalse(login.authenticate(credentials("old", "correct")).isPresent());
        assertEquals("120000", backing.findByUsername("old").orElseThrow()
                .getPassword().split("\\$", -1)[1]);
    }

    private static void assertMigrationWorks(UserAccountRepository repository) {
        seedLegacy(repository, "old", "correct");
        User before = repository.findByUsername("old").orElseThrow();
        UserLoginService login = migrationLogin(repository);

        User accepted = login.authenticate(credentials("old", "correct")).orElseThrow();
        User after = repository.findByUsername("old").orElseThrow();
        assertEquals("600000", after.getPassword().split("\\$", -1)[1]);
        assertTrue(new Pbkdf2PasswordHasher().matches("correct", after.getPassword()));
        assertNotEquals(before.getPassword(), after.getPassword());
        assertNotEquals(before.getSessionRevision(), after.getSessionRevision());
        assertEquals(after.getSessionRevision(), accepted.getSessionRevision());
        assertFalse(login.authenticate(credentials("old", "wrong")).isPresent());
        assertTrue(new UserLoginService(repository)
                .authenticate(credentials("old", "correct")).isPresent(),
                "after migration, normal strict authentication must succeed");
    }

    private static UserLoginService migrationLogin(UserAccountRepository repository) {
        return new UserLoginService(repository, null, new Pbkdf2PasswordHasher(),
                new LegacyPbkdf2CredentialUpgrade(repository));
    }

    private static void seedLegacy(UserAccountRepository repository, String username, String plain) {
        repository.save(legacyUser(username, legacyEncoded(plain)));
    }

    private static User legacyUser(String username, String encoded) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(encoded);
        u.setRole(AccountRole.CUSTOMER);
        return u;
    }

    private static User credentials(String username, String plaintext) {
        User u = new User();
        u.setUsername(username);
        u.setPassword(plaintext);
        return u;
    }

    private static String legacyEncoded(String plaintext) {
        try {
            byte[] salt = new byte[16];
            new SecureRandom().nextBytes(salt);
            PBEKeySpec input = new PBEKeySpec(plaintext.toCharArray(), salt, 120_000, 256);
            byte[] derived;
            try {
                derived = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                        .generateSecret(input).getEncoded();
            } finally {
                input.clearPassword();
            }
            return "pbkdf2$120000$" + Base64.getEncoder().encodeToString(salt)
                    + "$" + Base64.getEncoder().encodeToString(derived);
        } catch (Exception failure) {
            throw new IllegalStateException("could not construct isolated legacy test credential", failure);
        }
    }
}
