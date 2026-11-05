package com.revature.service;

/**
 * Encodes and verifies user credentials without exposing plaintext passwords to repositories.
 */
public interface PasswordHasher {

    String hash(String plaintextPassword);

    boolean matches(String plaintextPassword, String encodedPassword);
}
