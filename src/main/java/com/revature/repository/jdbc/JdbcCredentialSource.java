package com.revature.repository.jdbc;

import java.util.Arrays;
import java.util.Objects;

/**
 * Credential delivery seam for a separately deployed, trusted JDBC process.
 *
 * A production implementation must obtain a fresh, owned credential buffer
 * from a managed secret store and be safe for concurrent calls. The source is
 * consulted for every new connection, without fallback to a cached password.
 */
@FunctionalInterface
public interface JdbcCredentialSource {
    CredentialLease acquire();

    /** Takes ownership of the supplied password array and clears it on close. */
    final class CredentialLease implements AutoCloseable {
        private final String username;
        private char[] password;

        public CredentialLease(String username, char[] password) {
            if (username == null || username.trim().isEmpty()
                    || !username.equals(username.trim())) {
                throw new SecurityException("invalid database credential identity");
            }
            if (password == null || password.length == 0) {
                throw new SecurityException("database credential secret required");
            }
            this.username = username;
            this.password = password;
        }

        public String username() { return username; }

        /* DriverManager requires a String; this unavoidable copy is not erasable. */
        String passwordForDriver() {
            if (password == null) {
                throw new IllegalStateException("database credential lease already closed");
            }
            return new String(password);
        }

        @Override
        public void close() {
            if (password != null) {
                Arrays.fill(password, '\0');
                password = null;
            }
        }
    }
}
