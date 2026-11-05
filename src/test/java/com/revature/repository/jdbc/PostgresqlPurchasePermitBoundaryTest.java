package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.TestAccounts;

/**
 * CI-only proof: trusted issuer creates a reviewer/buyer/request/terms-bound,
 * expiring permit; an EXECUTE-only SQL writer can consume it at most once.
 * Does not establish production reviewer login or credential custody.
 */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlPurchasePermitBoundaryTest {

    @Test
    void writerCannotMintOrForgePermitsAndAnApprovedPermitIsConsumedExactlyOnce() throws Exception {
        Fixture f = fixture("authorized");
        UUID permit = f.issue(0, "buyer", "reviewer", 12, 600, false);
        assertDenied(() -> f.approve(UUID.randomUUID(), 0, 12));
        assertDenied(() -> f.approve(permit, 0, 24));
        assertDenied(() -> f.approve(permit, 1, 12));
        assertDenied(() -> f.execute(f.writer,
                "INSERT INTO purchase_approval_permits(permit_id, request_id, reviewer_username, "
                + "buyer_username, payment_months, expires_at) VALUES "
                + "(gen_random_uuid(), 0, 'reviewer', 'buyer', 12, CURRENT_TIMESTAMP)"));
        assertDenied(() -> f.execute(f.writer,
                "UPDATE inventory_listings SET stock_quantity = 0 WHERE listing_id = 0"));
        assertDenied(() -> f.execute(f.writer,
                "UPDATE accounts SET role = 'ADMINISTRATOR' WHERE username = 'buyer'"));
        assertDenied(() -> f.approveAsReader(permit, 0, 12));

        assertEquals(24000, f.approve(permit, 0, 12));
        assertTrue(f.consumed(permit));
        assertEquals(0, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(1, f.rows("owned_vehicles"));
        assertEquals(1, f.rows("payment_plans"));

        assertDenied(() -> f.approve(permit, 0, 12));
        // A second valid permit cannot duplicate an already accepted request.
        UUID second = f.issue(0, "buyer", "reviewer", 12, 600, false);
        assertDenied(() -> f.approve(second, 0, 12));
        assertFalse(f.consumed(second));
        assertEquals(1, f.rows("owned_vehicles"));
    }

    @Test
    void expiryRevocationAndReviewerRoleAreCheckedAtExecution() throws Exception {
        Fixture f = fixture("denied");
        UUID expired = f.issue(0, "buyer", "reviewer", 12, -60, false);
        UUID revoked = f.issue(0, "buyer", "reviewer", 12, 600, true);
        UUID wrongBuyer = f.issue(0, "another_buyer", "reviewer", 12, 600, false);
        UUID approved = f.issue(0, "buyer", "reviewer", 12, 600, false);

        assertDenied(() -> f.approve(expired, 0, 12));
        assertDenied(() -> f.approve(revoked, 0, 12));
        assertDenied(() -> f.approve(wrongBuyer, 0, 12));

        f.execute(f.owner, "UPDATE accounts SET role = 'CUSTOMER' WHERE username = 'reviewer'");
        assertDenied(() -> f.approve(approved, 0, 12));
        assertFalse(f.consumed(approved));
        assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, f.rows("owned_vehicles"));
    }

    @Test
    void databaseFailureRollsBackStockOwnershipAndPermitConsumption() throws Exception {
        Fixture f = fixture("rollback");
        UUID permit = f.issue(0, "buyer", "reviewer", 12, 600, false);
        f.execute(f.owner, "CREATE FUNCTION " + f.schema
                + ".deny_ci_ownership() RETURNS trigger LANGUAGE plpgsql AS "
                + "$fault$ BEGIN RAISE EXCEPTION 'test-only ownership failure' "
                + "USING ERRCODE = '23514'; END; $fault$");
        f.execute(f.owner, "CREATE TRIGGER deny_ci_ownership BEFORE INSERT ON "
                + f.schema + ".owned_vehicles FOR EACH ROW EXECUTE FUNCTION "
                + f.schema + ".deny_ci_ownership()");
        try {
            SQLException error = assertThrows(SQLException.class, () -> f.approve(permit, 0, 12));
            assertEquals("23514", error.getSQLState());
            assertFalse(f.consumed(permit));
            assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
            assertTrue(f.inventory.purchaseRequests().get(0).isPending());
            assertEquals(0, f.rows("owned_vehicles"));
            assertEquals(0, f.rows("payment_plans"));
        } finally {
            f.execute(f.owner, "DROP TRIGGER deny_ci_ownership ON " + f.schema
                    + ".owned_vehicles");
        }

        assertEquals(24000, f.approve(permit, 0, 12));
        assertTrue(f.consumed(permit));
        assertEquals(1, f.rows("owned_vehicles"));
    }

    @Test
    void readOnlyReceiptDifferentiatesUnconfirmedCommitAndInconsistentState() throws Exception {
        Fixture f = fixture("receipt");
        UUID permit = f.issue(0, "buyer", "reviewer", 12, 600, false);

        Receipt before = f.receipt(permit, 0, 12);
        assertEquals("NOT_CONFIRMED", before.outcome);
        assertEquals(null, before.contract);
        assertEquals(null, before.price);
        assertDenied(() -> f.receipt(UUID.randomUUID(), 0, 12));
        assertDenied(() -> f.receipt(permit, 1, 12));
        assertDenied(() -> f.receipt(permit, 0, 24));
        assertDenied(() -> f.receiptAs(f.reader, permit, 0, 12));
        assertDenied(() -> f.receiptAs(f.writer, permit, 0, 12));
        assertDenied(() -> f.execute(f.reconciler,
                "SELECT * FROM purchase_approval_permits"));
        assertDenied(() -> f.execute(f.reconciler,
                "UPDATE purchase_requests SET status = 'ACCEPTED' WHERE request_id = 0"));

        assertEquals(24000, f.approve(permit, 0, 12));
        Receipt after = f.receipt(permit, 0, 12);
        assertEquals("COMMITTED", after.outcome);
        assertEquals("ACQ-000000", after.contract);
        assertEquals(Integer.valueOf(24000), after.price);
        assertEquals(1, f.rows("owned_vehicles"));
        assertEquals(1, f.rows("payment_plans"));

        // Owner-side corruption is detected, not reported as a valid receipt.
        f.execute(f.owner, "UPDATE payment_plans SET purchase_price = 1");
        Receipt inconsistent = f.receipt(permit, 0, 12);
        assertEquals("INCONSISTENT", inconsistent.outcome);
        assertEquals(null, inconsistent.contract);
        assertEquals(null, inconsistent.price);
        assertEquals(1, f.rows("owned_vehicles"));
    }

    @Test
    void simulatedLostReplyAfterCommittedWriteIsReconciledWithoutReapproval() throws Exception {
        Fixture f = fixture("lost_reply");
        UUID permit = f.issue(0, "buyer", "reviewer", 12, 600, false);

        // Simulates only the caller losing an acknowledgement AFTER PostgreSQL
        // completed COMMIT. It is NOT a real network fault / failover test.
        SQLException lostReply = assertThrows(SQLException.class, () -> {
            assertEquals(24000, f.approve(permit, 0, 12));
            throw new SQLException("simulated lost response after committed purchase", "08006");
        });
        assertEquals("08006", lostReply.getSQLState());

        Receipt receipt = f.receipt(permit, 0, 12);
        assertEquals("COMMITTED", receipt.outcome);
        assertEquals("ACQ-000000", receipt.contract);
        assertEquals(Integer.valueOf(24000), receipt.price);
        assertTrue(f.consumed(permit));
        assertEquals(0, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(1, f.rows("owned_vehicles"));
        assertEquals(1, f.rows("payment_plans"));
        // Reconciliation must NEVER invoke the purchase a second time.
        assertDenied(() -> f.approve(permit, 0, 12));
        assertEquals(1, f.rows("owned_vehicles"));
    }

    @Test
    void failedPurchaseRemainsNotConfirmedAndCannotBeMistakenForCommitted() throws Exception {
        Fixture f = fixture("receipt_rollback");
        UUID permit = f.issue(0, "buyer", "reviewer", 12, 600, false);
        f.execute(f.owner, "CREATE FUNCTION " + f.schema
                + ".deny_receipt_ownership() RETURNS trigger LANGUAGE plpgsql AS "
                + "$fault$ BEGIN RAISE EXCEPTION 'test-only failure' "
                + "USING ERRCODE = '23514'; END; $fault$");
        f.execute(f.owner, "CREATE TRIGGER deny_receipt_ownership BEFORE INSERT ON "
                + f.schema + ".owned_vehicles FOR EACH ROW EXECUTE FUNCTION "
                + f.schema + ".deny_receipt_ownership()");
        try {
            assertEquals("23514",
                    assertThrows(SQLException.class, () -> f.approve(permit, 0, 12)).getSQLState());
            Receipt receipt = f.receipt(permit, 0, 12);
            assertEquals("NOT_CONFIRMED", receipt.outcome);
            assertEquals(null, receipt.contract);
            assertEquals(null, receipt.price);
            assertFalse(f.consumed(permit));
            assertTrue(f.inventory.purchaseRequests().get(0).isPending());
            assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
            assertEquals(0, f.rows("owned_vehicles"));
            assertEquals(0, f.rows("payment_plans"));
        } finally {
            f.execute(f.owner, "DROP TRIGGER deny_receipt_ownership ON " + f.schema
                    + ".owned_vehicles");
        }
        // A fresh, explicitly authorized call can still complete the
        // unconsumed permit, but the receipt itself never submits that call.
        assertEquals(24000, f.approve(permit, 0, 12));
        assertEquals("COMMITTED", f.receipt(permit, 0, 12).outcome);
    }

    private static Fixture fixture(String scenario) throws Exception {
        String url = System.getenv("DEALERSHIP_PG_TEST_URL");
        String user = System.getenv("DEALERSHIP_PG_TEST_USER");
        String password = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(url)
                        && "dealership_ci_owner".equals(user)
                        && "ci-only-postgres-fixture".equals(password),
                "Purchase permit proof runs exclusively against disposable PostgreSQL 16 CI");

        String id = UUID.randomUUID().toString().replace("-", "");
        String schema = "apermit_" + scenario + "_" + id;
        String writerRole = "apwriter_" + id;
        String readerRole = "apreader_" + id;
        String reconcilerRole = "apreceipt_" + id;
        String writerPassword = "disposable-ci-only-writer";
        String readerPassword = "disposable-ci-only-reader";
        String reconcilerPassword = "disposable-ci-only-reconciler";
        JdbcDatabase admin = new JdbcDatabase(url, user, password);
        try (Connection conn = admin.openConnection(); Statement ddl = conn.createStatement()) {
            ddl.execute("CREATE SCHEMA " + schema);
            ddl.execute("CREATE ROLE " + writerRole + " LOGIN PASSWORD '" + writerPassword + "'");
            ddl.execute("CREATE ROLE " + readerRole + " LOGIN PASSWORD '" + readerPassword + "'");
            ddl.execute("CREATE ROLE " + reconcilerRole
                    + " LOGIN PASSWORD '" + reconcilerPassword + "'");
        }

        String scopedUrl = url + "?currentSchema=" + schema;
        JdbcDatabase owner = new JdbcDatabase(scopedUrl, user, password);
        owner.migrate();
        TestAccounts.seed(new JdbcUserAccountRepository(owner),
                AccountRole.EMPLOYEE, "reviewer", "test-reviewer-password");
        TestAccounts.seed(new JdbcUserAccountRepository(owner),
                AccountRole.CUSTOMER, "buyer", "test-buyer-password");
        TestAccounts.seed(new JdbcUserAccountRepository(owner),
                AccountRole.CUSTOMER, "another_buyer", "test-buyer-password");
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(owner);
        inventory.addListing("Honda", "Accord", 2024, 24000, 1);
        inventory.requestPurchase("buyer", 0);
        inventory.requestPurchase("buyer", 0);

        String function = loadFunction()
                .replace("__SCHEMA__", schema).replace("__WRITER__", writerRole);
        String receiptFunction = loadReceiptFunction()
                .replace("__SCHEMA__", schema).replace("__RECONCILER__", reconcilerRole);
        // No PUBLIC EXECUTE exposure, including between CREATE and REVOKE.
        try (Connection conn = admin.openConnection(); Statement ddl = conn.createStatement()) {
            conn.setAutoCommit(false);
            try {
                ddl.execute("CREATE TABLE " + schema + ".purchase_approval_permits ("
                        + "permit_id uuid PRIMARY KEY, request_id integer NOT NULL, "
                        + "reviewer_username varchar(100) NOT NULL, buyer_username varchar(100) NOT NULL, "
                        + "payment_months integer NOT NULL, expires_at timestamptz NOT NULL, "
                        + "revoked boolean NOT NULL DEFAULT false, consumed_at timestamptz, "
                        + "contract_id varchar(20), purchase_price integer)");
                ddl.execute(function);
                ddl.execute(receiptFunction);
                ddl.execute("REVOKE ALL ON FUNCTION " + schema
                        + ".approve_purchase_with_permit(uuid, integer, integer) FROM PUBLIC");
                ddl.execute("REVOKE ALL ON FUNCTION " + schema
                        + ".reconcile_purchase_permit(uuid, integer, integer) FROM PUBLIC");
                ddl.execute("GRANT USAGE ON SCHEMA " + schema + " TO "
                        + writerRole + ", " + readerRole + ", " + reconcilerRole);
                ddl.execute("GRANT EXECUTE ON FUNCTION " + schema
                        + ".reconcile_purchase_permit(uuid, integer, integer) TO " + reconcilerRole);
                ddl.execute("GRANT EXECUTE ON FUNCTION " + schema
                        + ".approve_purchase_with_permit(uuid, integer, integer) TO " + writerRole);
                ddl.execute("GRANT SELECT ON " + schema + ".schema_history, "
                        + schema + ".accounts TO " + readerRole);
                conn.commit();
            } catch (SQLException | RuntimeException error) {
                conn.rollback();
                throw error;
            }
        }
        return new Fixture(schema, owner,
                new JdbcDatabase(scopedUrl, writerRole, writerPassword),
                new JdbcDatabase(scopedUrl, readerRole, readerPassword),
                new JdbcDatabase(scopedUrl, reconcilerRole, reconcilerPassword), inventory);
    }

    private static String loadFunction() throws Exception {
        try (InputStream stream = PostgresqlPurchasePermitBoundaryTest.class
                .getResourceAsStream("/postgresql/trusted_purchase_permit_ci.sql")) {
            if (stream == null) throw new IllegalStateException("CI permit function not present");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String loadReceiptFunction() throws Exception {
        try (InputStream stream = PostgresqlPurchasePermitBoundaryTest.class
                .getResourceAsStream("/postgresql/trusted_purchase_receipt_ci.sql")) {
            if (stream == null) throw new IllegalStateException("CI receipt function not present");
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static void assertDenied(org.junit.jupiter.api.function.Executable call) {
        SQLException error = assertThrows(SQLException.class, call);
        assertEquals("42501", error.getSQLState());
    }

    private static final class Receipt {
        final String outcome;
        final String contract;
        final Integer price;
        Receipt(String outcome, String contract, Integer price) {
            this.outcome = outcome;
            this.contract = contract;
            this.price = price;
        }
    }

    private static final class Fixture {
        final String schema;
        final JdbcDatabase owner;
        final JdbcDatabase writer;
        final JdbcDatabase reader;
        final JdbcDatabase reconciler;
        final JdbcInventoryRepository inventory;

        Fixture(String schema, JdbcDatabase owner, JdbcDatabase writer,
                JdbcDatabase reader, JdbcDatabase reconciler, JdbcInventoryRepository inventory) {
            this.schema = schema;
            this.owner = owner;
            this.writer = writer;
            this.reader = reader;
            this.reconciler = reconciler;
            this.inventory = inventory;
        }

        UUID issue(int requestId, String buyer, String reviewer,
                int months, int lifetimeSeconds, boolean revoked) throws SQLException {
            UUID token = UUID.randomUUID();
            try (Connection conn = owner.openConnection();
                    PreparedStatement insert = conn.prepareStatement(
                        "INSERT INTO purchase_approval_permits("
                        + "permit_id, request_id, reviewer_username, buyer_username, "
                        + "payment_months, expires_at, revoked) "
                        + "VALUES (?, ?, ?, ?, ?, pg_catalog.clock_timestamp() "
                        + "+ (? * INTERVAL '1 second'), ?)")) {
                insert.setObject(1, token);
                insert.setInt(2, requestId);
                insert.setString(3, reviewer);
                insert.setString(4, buyer);
                insert.setInt(5, months);
                insert.setInt(6, lifetimeSeconds);
                insert.setBoolean(7, revoked);
                assertEquals(1, insert.executeUpdate());
            }
            return token;
        }

        boolean consumed(UUID permit) throws SQLException {
            try (Connection conn = owner.openConnection();
                    PreparedStatement query = conn.prepareStatement(
                            "SELECT consumed_at IS NOT NULL FROM purchase_approval_permits "
                            + "WHERE permit_id = ?")) {
                query.setObject(1, permit);
                try (ResultSet result = query.executeQuery()) {
                    assertTrue(result.next());
                    return result.getBoolean(1);
                }
            }
        }

        int approve(UUID permit, int requestId, int months) throws SQLException {
            return invoke(writer, permit, requestId, months);
        }

        int approveAsReader(UUID permit, int requestId, int months) throws SQLException {
            return invoke(reader, permit, requestId, months);
        }

        private int invoke(JdbcDatabase db, UUID permit, int requestId, int months)
                throws SQLException {
            try (Connection conn = db.openConnection();
                    PreparedStatement call = conn.prepareStatement("SELECT " + schema
                            + ".approve_purchase_with_permit(?, ?, ?)")) {
                call.setObject(1, permit);
                call.setInt(2, requestId);
                call.setInt(3, months);
                try (ResultSet result = call.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("missing SQL response");
                    return result.getInt(1);
                }
            }
        }

        Receipt receipt(UUID permit, int requestId, int months) throws SQLException {
            return receiptAs(reconciler, permit, requestId, months);
        }

        Receipt receiptAs(JdbcDatabase db, UUID permit, int requestId, int months)
                throws SQLException {
            try (Connection conn = db.openConnection();
                    PreparedStatement query = conn.prepareStatement("SELECT * FROM " + schema
                            + ".reconcile_purchase_permit(?, ?, ?)")) {
                query.setObject(1, permit);
                query.setInt(2, requestId);
                query.setInt(3, months);
                try (ResultSet result = query.executeQuery()) {
                    if (!result.next()) throw new IllegalStateException("receipt result missing");
                    Receipt receipt = new Receipt(result.getString("outcome"),
                            result.getString("acquisition_contract"),
                            (Integer) result.getObject("authorized_price"));
                    if (result.next()) throw new IllegalStateException("receipt must be single-row");
                    return receipt;
                }
            }
        }

        int rows(String table) throws SQLException {
            if (!"owned_vehicles".equals(table) && !"payment_plans".equals(table)) {
                throw new IllegalArgumentException("unsupported table");
            }
            try (Connection conn = owner.openConnection();
                    Statement q = conn.createStatement();
                    ResultSet rs = q.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getInt(1);
            }
        }

        void execute(JdbcDatabase db, String sql) throws SQLException {
            try (Connection conn = db.openConnection();
                    Statement statement = conn.createStatement()) {
                statement.execute(sql);
            }
        }
    }
}
