package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.UserAccountRepository;
import com.revature.service.UserLoginService;
import com.revature.support.TestAccounts;

/** Transactional customer password rotation on a disposable PostgreSQL 16 schema. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlCustomerCredentialRotationTest {
    @Test
    @Timeout(75)
    void currentPasswordRotatesCredentialAndSessionWhilePriorSnapshotExpires() throws Exception {
        String url = System.getenv("DEALERSHIP_PG_TEST_URL");
        String username = System.getenv("DEALERSHIP_PG_TEST_USER");
        String password = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                        && "dealership_ci_owner".equals(username)
                        && "ci-only-postgres-fixture".equals(password),
                "test requires disposable PostgreSQL16 CI");
        String schema = "w22_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase server = new JdbcDatabase(url, username, password);
        try {
            try (Connection c = server.openConnection(); Statement s = c.createStatement()) {
                try (ResultSet r = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(r.next());
                    int v = Integer.parseInt(r.getString(1));
                    assertTrue(v >= 160000 && v < 170000);
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            JdbcDatabase db = new JdbcDatabase(url + "?currentSchema=" + schema,
                    username, password);
            db.migrate();
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(db);
            TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "first-password");
            User before = accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            JdbcTrustedCustomerCredentialWriter writer = new JdbcTrustedCustomerCredentialWriter(db);

            // Public service uses the separately injected trusted writer, rather
            // than unrestricted hash-only account UPDATE.
            UserLoginService service = new UserLoginService(
                    new JdbcCustomerOnlyRegistrationRepository(db, writer),
                    (UserAccountRepository) null);
            User current = new User();
            current.setUsername("buyer");
            current.setPassword("first-password");
            current.setRole(AccountRole.CUSTOMER);
            assertTrue(service.changePassword(current, "second-password"));
            User after = accounts.findByUsername("buyer").orElseThrow(AssertionError::new);
            assertNotEquals(before.getSessionRevision(), after.getSessionRevision());
            assertNotEquals(before.getPassword(), after.getPassword());
            assertEquals(AccountRole.CUSTOMER, after.getRole());
            assertFalse(service.authenticate(current).isPresent());
            assertFalse(new Pbkdf2PasswordHasher().matches("first-password", after.getPassword()));
            assertTrue(new Pbkdf2PasswordHasher().matches("second-password", after.getPassword()));
            assertThrows(SecurityException.class,
                    () -> writer.rotateCustomerPassword(before, "first-password",
                            new Pbkdf2PasswordHasher().hash("third-password")));
        } finally {
            try (Connection c = server.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
