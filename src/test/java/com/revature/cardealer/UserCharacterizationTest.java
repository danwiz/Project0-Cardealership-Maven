package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("legacy-behavior")
@DisplayName("User legacy behavior characterization")
class UserCharacterizationTest {

    @Test
    @DisplayName("new users begin with null credentials")
    void newUsersBeginWithNullCredentials() {
        User user = new User();

        assertNull(user.getUsername());
        assertNull(user.getPassword());
    }

    @Test
    @DisplayName("username and password setters retain exact supplied values")
    void settersRetainExactSuppliedValues() {
        User user = new User();

        user.setUsername("customer1");
        user.setPassword("plain-text-secret");

        assertEquals("customer1", user.getUsername());
        assertEquals("plain-text-secret", user.getPassword());
    }
}
