package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.service.RoleAuthorizationService;
import com.revature.support.TestAccounts;

/**
 * Disposable PostgreSQL CI proof of one restricted purchase principal.
 *
 * Important: SELECT ... FOR UPDATE on accounts requires an UPDATE grant on at
 * least one account column in PostgreSQL 16. An owner-installed trigger rejects
 * every UPDATE by the purchase principal, including the granted lock column.
 * Production must provision and independently audit equivalent safeguards.
 * This fixture does not grant the runtime owner/superuser rights.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlPurchaseLeastPrivilegeIntegrationTest {

    @Test
    void restrictedPrincipalCanApproveButCannotChangeAccountsOrSchema() throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Only the disposable PostgreSQL 16 GitHub CI instance may be used");

        String id = UUID.randomUUID().toString().replace("-", "");
        String schema = "purchase_lpg_" + id;
        String principal = "purchase_runtime_" + id;
        String runtimePassword = "ci-disposable-purchase-writer";
        JdbcDatabase ownerServer = new JdbcDatabase(baseUrl, ownerName, ownerPassword);

        try (Connection connection = ownerServer.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE ROLE " + principal + " LOGIN PASSWORD '" + runtimePassword + "'");
        }
        String url = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase migrator = new JdbcDatabase(url, ownerName, ownerPassword);
        migrator.migrate();
        JdbcUserAccountRepository ownerAccounts = new JdbcUserAccountRepository(migrator);
        TestAccounts.seed(ownerAccounts, AccountRole.EMPLOYEE, "reviewer", "reviewer-password");
        TestAccounts.seed(ownerAccounts, AccountRole.CUSTOMER, "buyer", "buyer-password");

        // Protected operator-owned DDL is separate from runtime startup. The
        // immutable guard makes an UPDATE-only lock grant unusable for writes.
        try (Connection connection = ownerServer.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE FUNCTION " + schema + ".deny_purchase_account_updates() "
                    + "RETURNS trigger LANGUAGE plpgsql SET search_path = pg_catalog AS $guard$ "
                    + "BEGIN RAISE EXCEPTION 'purchase runtime cannot update accounts' "
                    + "USING ERRCODE = '42501'; END; $guard$");
            sql.execute("REVOKE ALL ON FUNCTION " + schema
                    + ".deny_purchase_account_updates() FROM PUBLIC");
            sql.execute("CREATE TRIGGER deny_purchase_account_mutation BEFORE UPDATE ON "
                    + schema + ".accounts FOR EACH ROW EXECUTE FUNCTION "
                    + schema + ".deny_purchase_account_updates()");
            sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO " + principal);
            sql.execute("GRANT SELECT ON TABLE " + schema + ".schema_history, "
                    + schema + ".accounts, "
                    + schema + ".inventory_listings, "
                    + schema + ".purchase_requests, "
                    + schema + ".owned_vehicles, "
                    + schema + ".payment_plans TO " + principal);
            // PostgreSQL requires a column UPDATE grant even for SELECT FOR
            // UPDATE. The database-owner trigger denies actual modifications.
            sql.execute("GRANT UPDATE (encoded_credential) ON "
                    + schema + ".accounts TO " + principal);
            sql.execute("GRANT UPDATE (stock_quantity, active) ON "
                    + schema + ".inventory_listings TO " + principal);
            sql.execute("GRANT UPDATE (status, payment_months, monthly_payment, contract_id) ON "
                    + schema + ".purchase_requests TO " + principal);
            sql.execute("GRANT INSERT (owner_username, contract_id, make, model, vehicle_year) ON "
                    + schema + ".owned_vehicles TO " + principal);
            sql.execute("GRANT INSERT (ownership_id, purchase_price, payment_months, "
                    + "monthly_payment, amount_paid, balance) ON "
                    + schema + ".payment_plans TO " + principal);
        }

        JdbcInventoryRepository ownerInventory = new JdbcInventoryRepository(migrator);
        ownerInventory.addListing("Toyota", "Corolla", 2023, 24000, 1);
        ownerInventory.requestPurchase("buyer", 0);

        JdbcDatabase runtime = new JdbcDatabase(url, principal, runtimePassword);
        JdbcUserAccountRepository runtimeAccounts = new JdbcUserAccountRepository(runtime);
        RoleAuthorizationService authorization = new RoleAuthorizationService();
        authorization.requireVerifiedSessions(() -> runtimeAccounts);
        User reviewer = runtimeAccounts.findByUsername("reviewer")
                .orElseThrow(AssertionError::new);
        User credentials = new User();
        credentials.setUsername("reviewer");
        credentials.setPassword("reviewer-password");
        authorization.establishVerifiedSession(reviewer, credentials);

        // Prove the restricted principal can use the same row locks as the
        // production executor without changing any account record.
        try (Connection connection = runtime.openConnection();
                PreparedStatement select = connection.prepareStatement(
                        "SELECT role, encoded_credential FROM accounts "
                        + "WHERE username = ? FOR UPDATE")) {
            connection.setAutoCommit(false);
            select.setString(1, "reviewer");
            try (ResultSet rows = select.executeQuery()) {
                assertTrue(rows.next());
                assertEquals("EMPLOYEE", rows.getString("role"));
            }
            connection.rollback();
        }

        // No account credential mutation, role elevation, account deletion,
        // DDL, or arbitrary table creation through this purchase identity.
        assertDenied(() -> execute(runtime,
                "UPDATE accounts SET encoded_credential = 'forged' WHERE username = 'reviewer'"));
        assertDenied(() -> execute(runtime,
                "UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = 'buyer'"));
        assertDenied(() -> execute(runtime, "DELETE FROM accounts WHERE username = 'reviewer'"));
        assertDenied(() -> execute(runtime,
                "INSERT INTO accounts(username, encoded_credential, role) "
                + "VALUES ('forged', 'hash', 'ADMINISTRATOR')"));
        assertDenied(() -> execute(runtime, "CREATE TABLE forbidden_runtime_ddl (id INTEGER)"));
        assertDenied(() -> execute(runtime, "DELETE FROM owned_vehicles"));

        JdbcPurchaseApprovalService purchase = new JdbcPurchaseApprovalService(runtime, authorization);
        assertEquals(24000, purchase.approve(reviewer, 0, 24, false));
        assertEquals(0, ownerInventory.listings().get(0).getStockQuantity());
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                ownerInventory.purchaseRequests().get(0).getStatus());
        assertEquals(1, new JdbcOwnershipRepository(migrator, "buyer").findAll().size());
        assertEquals(24000,
                new JdbcOwnershipRepository(migrator, "buyer")
                    .findAll().get(0).getPaymentPlan().getRemainingBalance());
        assertEquals(24000, purchase.approve(reviewer, 0, 24, false));
        assertEquals(1, new JdbcOwnershipRepository(migrator, "buyer").findAll().size());

        User copied = runtimeAccounts.findByUsername("reviewer")
                .orElseThrow(AssertionError::new);
        assertThrows(SecurityException.class,
                () -> purchase.approve(copied, 0, 24, false));

        // Database role remains denied even after successful application use.
        assertDenied(() -> execute(runtime,
                "UPDATE accounts SET encoded_credential = 'forged' WHERE username = 'reviewer'"));
    }

    private static void execute(JdbcDatabase database, String statement) throws SQLException {
        try (Connection connection = database.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private static void assertDenied(Executable operation) {
        SQLException failure = assertThrows(SQLException.class, operation);
        assertEquals("42501", failure.getSQLState());
    }
}
