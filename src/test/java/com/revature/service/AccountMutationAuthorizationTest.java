package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.support.TestAccounts;

/** Security regression checks for creation, rotation and role reassignment. */
@Tag("SECURITY")
class AccountMutationAuthorizationTest {

    @Test
    void memoryRepositoryUsesInsertOnlyAndAuthenticatedChanges() {
        exerciseMutations(new InMemoryUserAccountRepository());
    }

    @Test
    void jdbcRepositoryUsesInsertOnlyAndAuthenticatedChanges() {
        exerciseMutations(new JdbcUserAccountRepository(
                JdbcDatabase.inMemory("account_mutation_" + System.nanoTime())));
    }

    private static void exerciseMutations(UserAccountRepository repository) {
        TestAccounts.seed(repository, AccountRole.ADMINISTRATOR, "root", "root-password");
        UserLoginService service = new UserLoginService(repository);
        service.registerUser("buyer", "buyer-password");

        User persisted = repository.findByUsername("buyer").orElseThrow(AssertionError::new);
        User forged = user("buyer", "forged-hash", AccountRole.ADMINISTRATOR);
        assertThrows(IllegalArgumentException.class, () -> repository.save(forged));
        assertEquals(AccountRole.CUSTOMER, repository.findByUsername("buyer")
                .orElseThrow(AssertionError::new).getRole());
        assertEquals(persisted.getPassword(), repository.findByUsername("buyer")
                .orElseThrow(AssertionError::new).getPassword());

        assertThrows(SecurityException.class, () -> service.changeRole(
                user("buyer", "buyer-password", AccountRole.ADMINISTRATOR),
                "buyer", AccountRole.CUSTOMER, AccountRole.ADMINISTRATOR));
        assertThrows(SecurityException.class, () -> service.changeRole(
                user("root", "wrong-password", AccountRole.ADMINISTRATOR),
                "buyer", AccountRole.CUSTOMER, AccountRole.EMPLOYEE));

        assertFalse(service.changeRole(user("root", "root-password", AccountRole.CUSTOMER),
                "buyer", AccountRole.EMPLOYEE, AccountRole.ADMINISTRATOR));
        assertTrue(service.changeRole(user("root", "root-password", AccountRole.ADMINISTRATOR),
                "buyer", AccountRole.CUSTOMER, AccountRole.EMPLOYEE));
        assertEquals(AccountRole.EMPLOYEE, repository.findByUsername("buyer")
                .orElseThrow(AssertionError::new).getRole());

        assertThrows(SecurityException.class, () -> service.changePassword(
                user("buyer", "not-the-password", AccountRole.EMPLOYEE), "replacement-password"));
        assertTrue(service.changePassword(user("buyer", "buyer-password", AccountRole.CUSTOMER),
                "replacement-password"));
        assertFalse(service.authenticate(user("buyer", "buyer-password", AccountRole.CUSTOMER)).isPresent());
        assertTrue(service.authenticate(user("buyer", "replacement-password", AccountRole.EMPLOYEE)).isPresent());
        assertFalse(repository.updateCredentialIfCurrent("buyer", persisted.getPassword(), "stale-write"));
        assertEquals(AccountRole.EMPLOYEE, repository.findByUsername("buyer")
                .orElseThrow(AssertionError::new).getRole());
    }

    private static User user(String username, String password, AccountRole role) {
        User result = new User();
        result.setUsername(username);
        result.setPassword(password);
        result.setRole(role);
        return result;
    }
}
