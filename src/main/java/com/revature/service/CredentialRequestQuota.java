package com.revature.service;

/**
 * Fail-closed admission contract for a credential request security boundary.
 *
 * The caller supplies a key established by trusted ingress or a verified
 * principal, never by an untrusted request parameter. Implementations must
 * charge every attempt and throw SecurityException when admission is denied
 * or cannot be determined. Production implementations must share atomic
 * counters across every replica and restart.
 */
public interface CredentialRequestQuota {
    void acquire(String trustedKey);
}
