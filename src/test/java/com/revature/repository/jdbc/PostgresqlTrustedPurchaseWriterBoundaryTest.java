package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
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
import com.revature.support.TestAccounts;

/**
 * Disposable PostgreSQL 16 proof for three distinct DB identities:
 * schema owner, ordinary read-only runtime, and isolated EXECUTE-only writer.
 * Not wired into application runtime and not a production migration.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlTrustedPurchaseWriterBoundaryTest {

    @Test
    void onlyDedicatedWriterMayExecuteAtomicOwnerFunctionAndNeitherRuntimeCanWriteTables()
            throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String ownerName = System.getenv("DEALERSHIP_PG_TEST_USER");
        String ownerPassword = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(ownerName)
                        && "ci-only-postgres-fixture".equals(ownerPassword),
                "Trusted writer proof is restricted to the disposable PostgreSQL 16 CI fixture");

        String id = UUID.randomUUID().toString().replace("-", "");
        String schema = "pfn_" + id;
        String readerRole = "pread_" + id;
        String writerRole = "pwrite_" + id;
        String readerSecret = "ci-readonly-purchase-fixture";
        String writerSecret = "ci-executeonly-purchase-fixture";
        JdbcDatabase serverOwner = new JdbcDatabase(baseUrl, ownerName, ownerPassword);
        try (Connection connection = serverOwner.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute("CREATE SCHEMA " + schema);
            sql.execute("CREATE ROLE " + readerRole
                    + " LOGIN PASSWORD '" + readerSecret + "'");
            sql.execute("CREATE ROLE " + writerRole
                    + " LOGIN PASSWORD '" + writerSecret + "'");
        }

        String scopedUrl = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase owner = new JdbcDatabase(scopedUrl, ownerName, ownerPassword);
        owner.migrate();
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(owner);
        TestAccounts.seed(accounts, AccountRole.EMPLOYEE, "reviewer", "test-reviewer-password");
        TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "test-buyer-password");
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(owner);
        inventory.addListing("Toyota", "Corolla", 2024, 24000, 1);
        inventory.requestPurchase("buyer", 0);
        inventory.requestPurchase("buyer", 0);
        inventory.addListing("Honda", "Civic", 2023, 15000, 1);
        inventory.requestPurchase("buyer", 1);

        // The owner creates and grants the narrow entrypoint in ONE committed
        // transaction. There must be no PUBLIC EXECUTE exposure window.
        String signature = schema + ".approve_purchase_trusted(integer, integer)";
        try (Connection connection = serverOwner.openConnection();
                Statement sql = connection.createStatement()) {
            connection.setAutoCommit(false);
            try {
                sql.execute(loadTemplate()
                        .replace("__SCHEMA__", schema)
                        .replace("__WRITER__", writerRole));
                sql.execute("REVOKE ALL ON FUNCTION " + signature + " FROM PUBLIC");
                sql.execute("GRANT USAGE ON SCHEMA " + schema + " TO "
                        + readerRole + ", " + writerRole);
                sql.execute("GRANT SELECT ON " + schema
                        + ".schema_history TO " + writerRole);
                sql.execute("GRANT SELECT ON " + schema
                        + ".schema_history, " + schema + ".accounts, "
                        + schema + ".inventory_listings, "
                        + schema + ".purchase_requests, "
                        + schema + ".owned_vehicles, "
                        + schema + ".payment_plans TO " + readerRole);
                sql.execute("GRANT EXECUTE ON FUNCTION " + signature
                        + " TO " + writerRole);
                connection.commit();
            } catch (SQLException | RuntimeException error) {
                connection.rollback();
                throw error;
            }
        }

        JdbcDatabase reader = new JdbcDatabase(scopedUrl, readerRole, readerSecret);
        JdbcDatabase writer = new JdbcDatabase(scopedUrl, writerRole, writerSecret);
        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, reader.schemaVersion());
        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, writer.schemaVersion());

        // The ordinary app runtime cannot invoke the privileged entrypoint,
        // nor directly mutate inventory, account identities or owned vehicles.
        assertDenied(() -> invoke(reader, schema, 0, 12));
        assertDenied(() -> execute(reader,
                "UPDATE inventory_listings SET stock_quantity = 0 WHERE listing_id = 0"));
        assertDenied(() -> execute(reader,
                "UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = 'buyer'"));
        assertDenied(() -> execute(reader,
                "INSERT INTO owned_vehicles(owner_username, make, model, vehicle_year) "
                        + "VALUES ('buyer', 'forged', 'vehicle', 2024)"));
        assertDenied(() -> execute(reader, "CREATE TABLE forged_schema (id integer)"));

        // A compromised writer credential can invoke its dedicated function;
        // it still has NO direct DML/SELECT access to sensitive tables.
        assertDenied(() -> execute(writer,
                "UPDATE inventory_listings SET stock_quantity = 0 WHERE listing_id = 0"));
        assertDenied(() -> execute(writer,
                "UPDATE accounts SET encoded_credential = 'forged' WHERE username = 'reviewer'"));
        assertDenied(() -> execute(writer,
                "DELETE FROM purchase_requests WHERE request_id = 0"));
        assertDenied(() -> execute(writer, "SELECT * FROM accounts"));
        assertDenied(() -> execute(writer, "CREATE TABLE forbidden_schema (id integer)"));

        assertEquals(24000, invoke(writer, schema, 0, 12));
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                inventory.purchaseRequests().get(0).getStatus());
        assertEquals(0, inventory.listings().get(0).getStockQuantity());
        assertEquals(1, rows(owner, "owned_vehicles"));
        assertEquals(1, rows(owner, "payment_plans"));

        // Durable replay and duplicate stock protection must remain intact.
        assertEquals(24000, invoke(writer, schema, 0, 12));
        assertEquals(1, rows(owner, "owned_vehicles"));
        assertEquals(0, inventory.listings().get(0).getStockQuantity());
        assertEquals("23514",
                assertThrows(SQLException.class,
                        () -> invoke(writer, schema, 1, 12)).getSQLState());
        assertTrue(inventory.purchaseRequests().get(1).isPending());
        assertEquals(1, rows(owner, "owned_vehicles"));

        // Owner-provisioned reviewer role revocation is checked on EACH call.
        execute(owner, "UPDATE accounts SET role = 'CUSTOMER' WHERE username = 'reviewer'");
        assertDenied(() -> invoke(writer, schema, 2, 12));
        assertTrue(inventory.purchaseRequests().get(2).isPending());
        assertEquals(1, inventory.listings().get(1).getStockQuantity());

        // Revoking EXECUTE removes the writer's approval authority without
        // changing business tables or a general application's read access.
        execute(owner, "REVOKE EXECUTE ON FUNCTION " + signature
                + " FROM " + writerRole);
        assertDenied(() -> invoke(writer, schema, 0, 12));
        assertEquals(1, rows(owner, "owned_vehicles"));
    }

    private static String loadTemplate() throws Exception {
        try (InputStream input = PostgresqlTrustedPurchaseWriterBoundaryTest.class
                .getResourceAsStream("/postgresql/trusted_purchase_writer_ci.sql")) {
            if (input == null) throw new IllegalStateException("trusted writer SQL fixture missing");
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static int invoke(JdbcDatabase database, String schema,
            int requestId, int months) throws SQLException {
        try (Connection connection = database.openConnection();
                PreparedStatement call = connection.prepareStatement(
                        "SELECT " + schema + ".approve_purchase_trusted(?, ?)")) {
            call.setInt(1, requestId);
            call.setInt(2, months);
            try (ResultSet response = call.executeQuery()) {
                if (!response.next()) throw new IllegalStateException("no purchase decision");
                return response.getInt(1);
            }
        }
    }

    private static void execute(JdbcDatabase db, String statement) throws SQLException {
        try (Connection connection = db.openConnection();
                Statement sql = connection.createStatement()) {
            sql.execute(statement);
        }
    }

    private static int rows(JdbcDatabase db, String table) throws SQLException {
        if (!"owned_vehicles".equals(table) && !"payment_plans".equals(table)) {
            throw new IllegalArgumentException("unexpected table");
        }
        try (Connection connection = db.openConnection();
                Statement sql = connection.createStatement();
                ResultSet result = sql.executeQuery("SELECT COUNT(*) FROM " + table)) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void assertDenied(Executable operation) {
        SQLException error = assertThrows(SQLException.class, operation);
        assertEquals("42501", error.getSQLState());
    }
}
