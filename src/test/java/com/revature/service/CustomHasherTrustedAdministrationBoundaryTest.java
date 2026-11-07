package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;

/**
 * Documents the deliberately fail-closed behavior when a caller injects a
 * non-PBKDF2 credential format that the trusted administrator port does not
 * support. This is NOT proof of arbitrary custom hasher compatibility.
 */
@Tag("SECURITY")
class CustomHasherTrustedAdministrationBoundaryTest {
    @Test
    void unsupportedConfiguredPasswordHasherNeverBypassesTrustedAdministrator() {
        InMemoryUserAccountRepository store = new InMemoryUserAccountRepository();
        PasswordHasher unsupported = new PrefixedTestHasher();

        User root = new User();
        root.setUsername("root");
        root.setPassword(unsupported.hash("root-password"));
        root.setRole(AccountRole.ADMINISTRATOR);
        store.save(root);

        User customer = new User();
        customer.setUsername("buyer");
        customer.setPassword(unsupported.hash("buyer-password"));
        customer.setRole(AccountRole.CUSTOMER);
        store.save(customer);

        UserLoginService service = new UserLoginService(store, store, unsupported);
        User credentials = new User();
        credentials.setUsername("root");
        credentials.setPassword("root-password");
        credentials.setRole(AccountRole.ADMINISTRATOR);

        assertTrue(service.authenticate(credentials).isPresent(),
                "the injected hasher must authenticate its own fixture format");
        assertThrows(SecurityException.class, () ->
                service.provisionUser(credentials, "elevated", "elevated-password",
                        AccountRole.ADMINISTRATOR));
        assertThrows(SecurityException.class, () ->
                service.changeRole(credentials, "buyer", AccountRole.CUSTOMER,
                        AccountRole.EMPLOYEE));
        assertThrows(SecurityException.class, () ->
                service.removeUser(credentials, "buyer"));

        assertFalse(store.findByUsername("elevated").isPresent());
        assertEquals(AccountRole.CUSTOMER,
                store.findByUsername("buyer").orElseThrow(AssertionError::new).getRole());
        assertEquals(2, store.findAll().size());
    }

    private static final class PrefixedTestHasher implements PasswordHasher {
        private static final String PREFIX = "unsupported-test-only$";
        private final Pbkdf2PasswordHasher wrapped = new Pbkdf2PasswordHasher();

        @Override public String hash(String plaintext) {
            return PREFIX + wrapped.hash(plaintext);
        }

        @Override public boolean matches(String plaintext, String encoded) {
            return encoded != null && encoded.startsWith(PREFIX)
                    && wrapped.matches(plaintext, encoded.substring(PREFIX.length()));
        }
    }
}
