package com.revature.service;

import com.revature.cardealer.User;

/**
 * Explicit, privileged customer-only credential rotation capability.
 *
 * The caller MUST NOT use a generic account UPDATE or an encoded-hash-only
 * compare-and-swap as proof of the user's identity. The implementation must
 * independently verify the supplied CURRENT PLAINTEXT password, persisted
 * role, and session generation inside the same trusted transaction that
 * updates the credential.
 *
 * This capability must be provisioned separately from public registration.
 */
public interface TrustedCustomerCredentialWriter {
    boolean rotateCustomerPassword(User authenticatedSnapshot,
                                   String currentPlaintext,
                                   String replacementEncoded);
}
