package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.RoleAuthorizationService;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Runnable, isolated TEST-source fixture for physical PostgreSQL16 HA checks.
 * Creates a real dealership schema and synthetic approved sale on the primary;
 * after promotion, reconnects through the new DB port and validates durable
 * stock, ownership and payment plan with idempotent purchase-approval replay.
 *
 * Never invoked by the ordinary application. Does not access user or prod data.
 */
public final class Wave21DealershipFailoverFixture {
    private static final String OWNER = "labowner";
    private static final String PASS = "w21-ci-only-owner-not-production";
    private static final String DB = "labdb";
    private static final int PRICE = 24000;

    private Wave21DealershipFailoverFixture() {}

    public static void main(String[] args) throws Exception {
        if (!"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || args.length != 2 || (!"seed".equals(args[0]) && !"recover".equals(args[0]))
                || (!"55421".equals(args[1]) && !"55422".equals(args[1]))) {
            throw new SecurityException("disposable CI only; seed/recover on expected local ports");
        }
        if ("seed".equals(args[0]) && !"55421".equals(args[1])
                || "recover".equals(args[0]) && !"55422".equals(args[1])) {
            throw new SecurityException("wrong primary/standby fixture port");
        }

        String url = "jdbc:postgresql://127.0.0.1:" + args[1] + "/" + DB;
        JdbcDatabase database = new JdbcDatabase(url, OWNER, PASS);
        if ("seed".equals(args[0])) {
            database.migrate();
            seed(database, url);
        } else {
            database.requireCurrentSchema();
            recover(database, url);
        }
    }

    private static ConfiguredDealershipApplication app(String url) {
        return DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url, OWNER, PASS));
    }

    private static void seed(JdbcDatabase db, String url) throws Exception {
        ConfiguredDealershipApplication app = app(url);
        TestAccounts.seed(new JdbcUserAccountRepository(db), AccountRole.EMPLOYEE,
                "ha-reviewer", "ha-reviewer-synthetic-password");
        app.getContext().getCustomerLoginService().registerUser(
                "ha-buyer", "ha-buyer-synthetic-password");
        app.getInventory().addListing("Toyota", "Corolla", 2024, PRICE, 1);
        app.getInventory().requestPurchase("ha-buyer", 0);
        User reviewer = AuthenticatedAccounts.login(app, AccountRole.EMPLOYEE,
                "ha-reviewer", "ha-reviewer-synthetic-password");
        int approved = new JdbcPurchaseApprovalService(db,
                app.getContext().getAuthorizationService())
                .approve(reviewer, 0, 24, false);
        if (approved != PRICE) throw new AssertionError("failed initial approval " + approved);
        verify(db, "seed");
    }

    private static void recover(JdbcDatabase db, String url) throws Exception {
        verify(db, "recover-before-replay");
        ConfiguredDealershipApplication app = app(url);
        User reviewer = AuthenticatedAccounts.login(app, AccountRole.EMPLOYEE,
                "ha-reviewer", "ha-reviewer-synthetic-password");
        int result = new JdbcPurchaseApprovalService(db,
                app.getContext().getAuthorizationService())
                .approve(reviewer, 0, 24, false);
        if (result != PRICE) {
            throw new AssertionError("replay must return exact durable price");
        }
        verify(db, "recover-after-replay");
    }

    private static void verify(JdbcDatabase db, String stage) throws Exception {
        require(db, "SELECT count(*) FROM accounts", 2, stage);
        require(db, "SELECT count(*) FROM accounts WHERE session_revision IS NOT NULL", 2, stage);
        require(db, "SELECT count(*) FROM inventory_listings WHERE listing_id = 0 AND stock_quantity = 0", 1, stage);
        require(db, "SELECT count(*) FROM purchase_requests WHERE request_id = 0 AND status = 'ACCEPTED'", 1, stage);
        require(db, "SELECT count(*) FROM owned_vehicles", 1, stage);
        require(db, "SELECT count(*) FROM payment_plans", 1, stage);
        require(db, "SELECT count(*) FROM owned_vehicles WHERE contract_id IS NOT NULL", 1, stage);
        require(db, "SELECT count(*) FROM payment_plans WHERE purchase_price = 24000 AND balance = 24000", 1, stage);
        require(db, "SELECT count(*) FROM payment_transactions", 0, stage);
        require(db, "SELECT COALESCE(MAX(version),0) FROM schema_history", 5, stage);
        System.out.println("W21_DEALERSHIP_HA_" + stage.toUpperCase().replace('-', '_')
                + "_PASS: stock=0, accepted=1, ownership=1, payment_plan=1, balance=24000, duplicate=0");
    }

    private static void require(JdbcDatabase db, String sql, int expected, String stage) throws Exception {
        try (Connection connection = db.openConnection();
                PreparedStatement query = connection.prepareStatement(sql);
                ResultSet result = query.executeQuery()) {
            if (!result.next() || result.getInt(1) != expected) {
                throw new AssertionError(stage + ": expected " + expected + " for " + sql);
            }
        }
    }
}
