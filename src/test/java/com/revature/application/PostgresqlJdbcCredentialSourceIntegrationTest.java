package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.repository.jdbc.JdbcCredentialSource;
import com.revature.repository.jdbc.JdbcDatabase;

/** Exercises only disposable PostgreSQL credentials, never production secrets. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlJdbcCredentialSourceIntegrationTest {

    @Test
    void connectionsFollowSecretRotationAndFailClosedOnOutages() throws Exception {
        String url = System.getenv("DEALERSHIP_PG_TEST_URL");
        String owner = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerSecret = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                        && "dealership_ci_owner".equals(owner)
                        && "ci-only-postgres-fixture".equals(ownerSecret),
                "Rotating credential integration runs only in the disposable CI fixture");

        String role = "rotating_writer_" + UUID.randomUUID().toString().replace("-", "");
        String initial = "ci-only-initial-secret";
        String replacement = "ci-only-rotated-secret";
        JdbcDatabase admin = new JdbcDatabase(url, owner, ownerSecret);
        try (Connection connection = admin.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + initial + "'");
        }

        try {
            AtomicReference<String> activeSecret = new AtomicReference<>(initial);
            AtomicReference<char[]> lastLease = new AtomicReference<>();
            AtomicBoolean available = new AtomicBoolean(true);
            JdbcCredentialSource source = () -> {
                if (!available.get()) return null;
                char[] delivered = activeSecret.get().toCharArray();
                lastLease.set(delivered);
                return new JdbcCredentialSource.CredentialLease(role, delivered);
            };
            JdbcDatabase writer = new JdbcDatabase(url, source);

            assertConnectedAs(writer, role);
            assertErased(lastLease.get());

            try (Connection connection = admin.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("ALTER ROLE " + role + " PASSWORD '" + replacement + "'");
            }
            SQLException denied = assertThrows(SQLException.class, () -> {
                try (Connection ignored = writer.openConnection()) { /* expected to fail */ }
            });
            assertEquals("28P01", denied.getSQLState());
            assertErased(lastLease.get());

            activeSecret.set(replacement);
            assertConnectedAs(writer, role);
            assertErased(lastLease.get());

            available.set(false);
            assertThrows(SecurityException.class, writer::openConnection);
        } finally {
            try (Connection connection = admin.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute("DROP ROLE " + role);
            }
        }
    }

    private static void assertConnectedAs(JdbcDatabase database, String expected) throws Exception {
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet result = statement.executeQuery("SELECT CURRENT_USER")) {
            result.next();
            assertEquals(expected, result.getString(1));
        }
    }

    private static void assertErased(char[] secret) {
        for (char value : secret) assertEquals('\0', value);
    }
}
