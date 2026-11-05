package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.User;

class UserLoginServiceCharacterizationTest {

    private PrintStream originalOut;

    @BeforeEach
    void captureOutput() {
        originalOut = System.out;
    }

    @AfterEach
    void restoreOutput() {
        System.setOut(originalOut);
    }

    @Test
    void registeredUserAuthenticatesWithMatchingPassword() {
        UserLoginService service = new UserLoginService();
        service.registerUser("dane", "secret");

        assertTrue(service.authenticateUser(user("dane", "secret")));
    }

    @Test
    void authenticationFailsWhenPasswordDoesNotMatch() {
        UserLoginService service = new UserLoginService();
        service.registerUser("dane", "secret");

        assertFalse(service.authenticateUser(user("dane", "wrong")));
    }

    @Test
    @Tag("SECURITY")
    @Tag("TARGET-BEHAVIOR")
    void authenticationDoesNotPrintStoredPasswordToStandardOutput() {
        UserLoginService service = new UserLoginService();
        service.registerUser("dane", "secret");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        System.setOut(new PrintStream(output));

        service.authenticateUser(user("dane", "secret"));

        assertFalse(output.toString().contains("secret"));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void constructingAnotherServiceDoesNotResetExistingServiceState() {
        UserLoginService first = new UserLoginService();
        first.registerUser("dane", "secret");

        new UserLoginService();

        assertTrue(first.authenticateUser(user("dane", "secret")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void registrationIsNotLimitedToTenAccounts() {
        UserLoginService service = serviceWithFastHasher();
        for (int i = 0; i < 25; i++) {
            service.registerUser("user" + i, "pw" + i);
        }

        assertTrue(service.authenticateUser(user("user24", "pw24")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void getUserNamesReturnsOnlyRegisteredUsersInRegistrationOrder() {
        UserLoginService service = serviceWithFastHasher();
        service.registerUser("dane", "secret");
        service.registerUser("alex", "pw");

        assertArrayEquals(new String[] {"dane", "alex"}, service.getUserNames());
    }

    @Test
    void unauthenticatedDeletionFailsClosed() {
        UserLoginService service = serviceWithFastHasher();
        User registered = service.registerUser("dane", "secret");

        assertThrows(SecurityException.class, () -> service.removeUser(registered));
        assertTrue(service.authenticateUser(user("dane", "secret")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void servicesCanShareAnExplicitRepository() {
        UserAccountRepository repository = new InMemoryUserAccountRepository();
        PasswordHasher hasher = new DeterministicPasswordHasher();
        UserLoginService registrationService = new UserLoginService(repository, hasher);
        UserLoginService authenticationService = new UserLoginService(repository, hasher);

        registrationService.registerUser("dane", "secret");

        assertTrue(authenticationService.authenticateUser(user("dane", "secret")));
    }

    @Test
    @Tag("SECURITY")
    @Tag("TARGET-BEHAVIOR")
    void registrationStoresAnEncodedPasswordInsteadOfPlaintext() {
        UserLoginService service = serviceWithFastHasher();

        User registered = service.registerUser(" Dane ", " Secret ");

        assertEquals(" Dane ", registered.getUsername());
        assertNotEquals(" Secret ", registered.getPassword());
        assertEquals("encoded: Secret ", registered.getPassword());
        assertTrue(service.authenticateUser(user(" Dane ", " Secret ")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void duplicateUsernameIsRejectedWithoutReplacingExistingCredential() {
        UserLoginService service = serviceWithFastHasher();
        service.registerUser("dane", "first");

        assertThrows(IllegalArgumentException.class,
                () -> service.registerUser("dane", "second"));
        assertTrue(service.authenticateUser(user("dane", "first")));
        assertFalse(service.authenticateUser(user("dane", "second")));
    }

    @Test
    @Tag("TARGET-BEHAVIOR")
    void nullAndBlankCredentialsAreRejectedAtRegistration() {
        UserLoginService service = serviceWithFastHasher();

        assertThrows(IllegalArgumentException.class, () -> service.registerUser(null, "secret"));
        assertThrows(IllegalArgumentException.class, () -> service.registerUser("   ", "secret"));
        assertThrows(IllegalArgumentException.class, () -> service.registerUser("dane", null));
        assertThrows(IllegalArgumentException.class, () -> service.registerUser("dane", "   "));
    }

    @Test
    void invalidAuthenticationInputIsRejectedWithoutException() {
        UserLoginService service = serviceWithFastHasher();

        assertFalse(service.authenticateUser(null));
        assertFalse(service.authenticateUser(user(null, "secret")));
        assertFalse(service.authenticateUser(user("   ", "secret")));
        assertFalse(service.authenticateUser(user("dane", null)));
    }

    private static UserLoginService serviceWithFastHasher() {
        return new UserLoginService(new InMemoryUserAccountRepository(), new DeterministicPasswordHasher());
    }

    private static User user(String username, String password) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(password);
        return user;
    }

    private static final class DeterministicPasswordHasher implements PasswordHasher {

        @Override
        public String hash(String plaintextPassword) {
            return "encoded:" + plaintextPassword;
        }

        @Override
        public boolean matches(String plaintextPassword, String encodedPassword) {
            return encodedPassword != null && encodedPassword.equals(hash(plaintextPassword));
        }
    }
}
