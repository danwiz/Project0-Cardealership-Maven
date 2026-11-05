package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class Pbkdf2PasswordHasherTest {

    @Test
    @Tag("security")
    void encodedPasswordMatchesOriginalPlaintext() {
        PasswordHasher hasher = new Pbkdf2PasswordHasher();

        String encoded = hasher.hash("correct horse battery staple");

        assertTrue(encoded.startsWith("pbkdf2$"));
        assertTrue(hasher.matches("correct horse battery staple", encoded));
        assertFalse(hasher.matches("wrong password", encoded));
    }

    @Test
    @Tag("security")
    void identicalPasswordsReceiveDifferentRandomSalts() {
        PasswordHasher hasher = new Pbkdf2PasswordHasher();

        String first = hasher.hash("secret");
        String second = hasher.hash("secret");

        assertNotEquals(first, second);
        assertTrue(hasher.matches("secret", first));
        assertTrue(hasher.matches("secret", second));
    }

    @Test
    @Tag("security")
    void rejectsUntrustedWorkFactorsBeforeHashing() {
        PasswordHasher hasher = new Pbkdf2PasswordHasher();
        String encoded = hasher.hash("secret");
        String[] fields = encoded.split("\\$", -1);

        assertEquals("600000", fields[1]);
        assertFalse(hasher.matches("secret", "pbkdf2$120000$" + fields[2] + "$" + fields[3]));
        assertFalse(hasher.matches("secret", "pbkdf2$2147483647$" + fields[2] + "$" + fields[3]));
        assertFalse(hasher.matches("secret", "pbkdf2$600000$" + fields[2] + "$" + fields[3] + "junk"));
    }

    @Test
    void malformedOrMissingEncodedPasswordsAreRejected() {
        PasswordHasher hasher = new Pbkdf2PasswordHasher();

        assertFalse(hasher.matches("secret", null));
        assertFalse(hasher.matches(null, "pbkdf2$120000$bad$bad"));
        assertFalse(hasher.matches("secret", "plaintext"));
        assertFalse(hasher.matches("secret", "pbkdf2$not-a-number$bad$bad"));
    }
}
