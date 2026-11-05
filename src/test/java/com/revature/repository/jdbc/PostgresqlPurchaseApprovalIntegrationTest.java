package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/** Isolated PostgreSQL 16 CI verification, never a persistent production database. */
@Tag("POSTGRESQL")
@Tag("SECURITY")
class PostgresqlPurchaseApprovalIntegrationTest {

    @Test
    void persistedApprovalReplaysOnceAndForgedStoredReviewerIsDenied() throws Exception {
        Fixture fixture = fixture("happy");
        fixture.inventory.addListing("Honda", "Accord", 2024, 23000, 2);
        fixture.inventory.requestPurchase("buyer1", 0);
        JdbcPurchaseApprovalService service = fixture.approver();
        assertEquals(23000, service.approve(fixture.reviewer, 0, 23, false));
        assertEquals(1, fixture.inventory.listings().get(0).getStockQuantity());
        assertEquals(1, rows(fixture.db, "owned_vehicles"));
        assertEquals(1, rows(fixture.db, "payment_plans"));

        // Reopening the connection and the service must not make a second sale.
        assertEquals(23000, fixture.approver().approve(fixture.reviewer, 0, 23, false));
        assertEquals(1, rows(fixture.db, "owned_vehicles"));
        assertEquals(1, fixture.inventory.listings().get(0).getStockQuantity());

        User copiedStoredAccount = new JdbcUserAccountRepository(fixture.db)
                .findByUsername("reviewer").orElseThrow(AssertionError::new);
        assertThrows(SecurityException.class,
                () -> service.approve(copiedStoredAccount, 0, 23, false));
        assertThrows(IllegalStateException.class,
                () -> service.approve(fixture.reviewer, 0, 12, false));
    }

    @Test
    void postgresFailureAfterOwnershipInsertRollsBackAllFourEntities() throws Exception {
        Fixture fixture = fixture("rollback");
        fixture.inventory.addListing("Mazda", "3", 2023, 18000, 1);
        fixture.inventory.requestPurchase("buyer1", 0);
        JdbcPurchaseApprovalService failing = new JdbcPurchaseApprovalService(
                fixture.db, fixture.app.getContext().getAuthorizationService(), stage -> {
                    if (stage == JdbcPurchaseApprovalService.Stage.AFTER_OWNERSHIP) {
                        throw new IllegalStateException("injected ownership failure");
                    }
                });
        assertThrows(IllegalStateException.class,
                () -> failing.approve(fixture.reviewer, 0, 18, false));
        assertTrue(fixture.inventory.purchaseRequests().get(0).isPending());
        assertEquals(1, fixture.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, rows(fixture.db, "owned_vehicles"));
        assertEquals(0, rows(fixture.db, "payment_plans"));

        assertEquals(18000, fixture.approver().approve(fixture.reviewer, 0, 18, false));
        assertEquals(1, rows(fixture.db, "owned_vehicles"));
    }

    @Test
    void separatePostgresqlConnectionsProduceOnlyOneCompleteSale() throws Exception {
        Fixture fixture = fixture("competition");
        fixture.inventory.addListing("Kia", "Soul", 2022, 21000, 1);
        fixture.inventory.requestPurchase("buyer1", 0);
        fixture.inventory.requestPurchase("buyer2", 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        JdbcPurchaseApprovalService first = fixture.approver();
        JdbcPurchaseApprovalService second = fixture.approver();
        try {
            Future<Boolean> result1 = executor.submit(() -> compete(first, fixture.reviewer, 0, go));
            Future<Boolean> result2 = executor.submit(() -> compete(second, fixture.reviewer, 1, go));
            go.countDown();
            assertTrue(result1.get(20, TimeUnit.SECONDS) != result2.get(20, TimeUnit.SECONDS));
            assertEquals(0, fixture.inventory.listings().get(0).getStockQuantity());
            assertEquals(1, rows(fixture.db, "owned_vehicles"));
            assertEquals(1, rows(fixture.db, "payment_plans"));
            long accepted = fixture.inventory.purchaseRequests().stream()
                    .filter(x -> x.getStatus() == PurchaseRequestStatus.ACCEPTED).count();
            assertEquals(1L, accepted);
        } finally {
            go.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(20, TimeUnit.SECONDS));
        }
    }

    private static boolean compete(JdbcPurchaseApprovalService service, User actor,
            int requestId, CountDownLatch go) throws Exception {
        if (!go.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("race not started");
        try {
            service.approve(actor, requestId, 12, false);
            return true;
        } catch (IllegalStateException expectedStockOrSerializationFailure) {
            return false;
        }
    }

    private static Fixture fixture(String name) throws Exception {
        String baseUrl = System.getenv("DEALERSHIP_PG_TEST_URL");
        String user = System.getenv("DEALERSHIP_PG_TEST_USER");
        String pass = System.getenv("DEALERSHIP_PG_TEST_PASSWORD");
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                        && "jdbc:postgresql://localhost:5432/dealership_ci".equals(baseUrl)
                        && "dealership_ci_owner".equals(user)
                        && "ci-only-postgres-fixture".equals(pass),
                "PostgreSQL purchase integration runs only against the disposable CI fixture");

        String schema = "purchase_" + name + "_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase owner = new JdbcDatabase(baseUrl, user, pass);
        try (Connection connection = owner.openConnection();
                Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
        String scopedUrl = baseUrl + "?currentSchema=" + schema;
        JdbcDatabase db = new JdbcDatabase(scopedUrl, user, pass);
        db.migrate();
        ConfiguredDealershipApplication app = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(scopedUrl, user, pass));
        TestAccounts.seed(new JdbcUserAccountRepository(db),
                AccountRole.EMPLOYEE, "reviewer", "reviewer-test-password");
        app.getContext().getCustomerLoginService().registerUser("buyer1", "buyer1-password");
        app.getContext().getCustomerLoginService().registerUser("buyer2", "buyer2-password");
        User reviewer = AuthenticatedAccounts.login(
                app, AccountRole.EMPLOYEE, "reviewer", "reviewer-test-password");
        return new Fixture(db, app, app.getInventory(), reviewer);
    }

    private static int rows(JdbcDatabase db, String name) throws Exception {
        if (!"owned_vehicles".equals(name) && !"payment_plans".equals(name)) {
            throw new IllegalArgumentException("unexpected table name");
        }
        try (Connection connection = db.openConnection();
                Statement sql = connection.createStatement();
                ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM " + name)) {
            rows.next();
            return rows.getInt(1);
        }
    }

    private static final class Fixture {
        final JdbcDatabase db;
        final ConfiguredDealershipApplication app;
        final com.revature.repository.InventoryRepository inventory;
        final User reviewer;
        Fixture(JdbcDatabase db, ConfiguredDealershipApplication app,
                com.revature.repository.InventoryRepository inventory, User reviewer) {
            this.db = db;
            this.app = app;
            this.inventory = inventory;
            this.reviewer = reviewer;
        }
        JdbcPurchaseApprovalService approver() {
            return new JdbcPurchaseApprovalService(db,
                    app.getContext().getAuthorizationService());
        }
    }
}
