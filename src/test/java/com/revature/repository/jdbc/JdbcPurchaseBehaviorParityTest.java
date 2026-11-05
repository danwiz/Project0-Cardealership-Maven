package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.ConfiguredDomainAdapter;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.DealershipQueryService;
import com.revature.application.InfrastructureConfiguration;
import com.revature.application.ReviewPurchaseRequestCommand;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;
import com.revature.service.UserAccountRepository;

/**
 * Behavior-level parity with the preserved short legacy-console branch.
 * Tests the actual configured JDBC/H2 transaction boundary, not the
 * non-transactional AuthorizedPurchaseReviewService compensation algorithm.
 */
@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class JdbcPurchaseBehaviorParityTest {

    @Test
    void revokedReviewerCannotApproveOrInspectPendingBuyers() {
        Fixture f = new Fixture("revoked");
        User reviewer = f.reviewer();
        f.inventory.addListing("Toyota", "Corolla", 2022, 21000, 1);
        f.inventory.requestPurchase("buyer", 0);
        DealershipQueryService queries = new DealershipQueryService(f.application);

        assertEquals("buyer", queries.pendingRequests(reviewer).get(0).getCustomerName());
        assertTrue(f.accounts.removeByUsername("reviewer"));
        assertThrows(SecurityException.class,
                () -> f.application.getPurchaseApproval().approve(reviewer, 0, 12, false));
        assertThrows(SecurityException.class, () -> queries.pendingRequests(reviewer));
        assertTrue(f.inventory.purchaseRequests().get(0).isPending());
        assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, f.count("owned_vehicles"));
        assertEquals(0, f.count("payment_plans"));
    }

    @Test
    void pendingBuyerProjectionRequiresVerifiedReviewerAndPreservesRequester() {
        Fixture f = new Fixture("pending");
        f.inventory.addListing("Honda", "Civic", 2023, 18000, 2);
        f.inventory.requestPurchase("buyer", 0);
        User reviewer = f.reviewer();
        User buyer = AuthenticatedAccounts.login(
                f.application, AccountRole.CUSTOMER, "buyer", "BuyerPass123!");
        DealershipQueryService queries = new DealershipQueryService(f.application);

        List<DealershipQueryService.PurchaseRequestView> result = queries.pendingRequests(reviewer);
        assertEquals(1, result.size());
        assertEquals("buyer", result.get(0).getCustomerName());
        assertEquals(0, result.get(0).getListingId());
        assertEquals(PurchaseRequestStatus.PENDING, result.get(0).getStatus());
        assertThrows(SecurityException.class, () -> queries.pendingRequests(buyer));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void missingBuyerIsRejectedAtForeignKeyBeforeCreatingAnOrphanRequest() {
        Fixture f = new Fixture("missing_buyer");
        f.inventory.addListing("Ford", "Escape", 2022, 20000, 1);
        assertThrows(IllegalStateException.class,
                () -> f.inventory.requestPurchase("not-registered", 0));
        assertTrue(f.inventory.purchaseRequests().isEmpty());
        assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, f.count("owned_vehicles"));
        assertEquals(0, f.count("payment_plans"));
    }

    @Test
    void rolledBackSecondPurchasePreservesPreviouslyCommittedCustomerOwnership() {
        Fixture f = new Fixture("existing_ownership");
        User reviewer = f.reviewer();
        JdbcOwnershipRepository ownership = new JdbcOwnershipRepository(f.database, "buyer");
        ownership.add(new Car("Mazda", "3", 2018), 9000, 12);
        List<OwnedVehicle> original = ownership.findAll();
        assertEquals(1, original.size());

        f.inventory.addListing("Honda", "Accord", 2024, 24000, 1);
        f.inventory.requestPurchase("buyer", 0);
        JdbcPurchaseApprovalService broken = new JdbcPurchaseApprovalService(
                f.database, f.application.getContext().getAuthorizationService(), step -> {
                    if (step == JdbcPurchaseApprovalService.Stage.AFTER_OWNERSHIP) {
                        throw new IllegalStateException("synthetic fault after unrelated ownership exists");
                    }
                });
        assertThrows(IllegalStateException.class,
                () -> broken.approve(reviewer, 0, 24, false));

        List<OwnedVehicle> retained = ownership.findAll();
        assertEquals(1, retained.size(), "existing ownership must survive rollback");
        assertEquals(original.get(0).getOwnershipId(), retained.get(0).getOwnershipId());
        assertEquals(9000, retained.get(0).getPaymentPlan().getPurchasePrice());
        assertEquals(1, f.count("owned_vehicles"));
        assertEquals(1, f.count("payment_plans"));
        assertTrue(f.inventory.purchaseRequests().get(0).isPending());
        assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
    }

    @Test
    void authorizedReviewerCannotCreatePurchasesWithInvalidTerms() {
        Fixture f = new Fixture("invalid_terms");
        User reviewer = f.reviewer();
        f.inventory.addListing("Nissan", "Leaf", 2021, 12000, 1);
        f.inventory.requestPurchase("buyer", 0);

        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(f.application);
        ReviewPurchaseRequestCommand command = new ReviewPurchaseRequestCommand(
                adapter, adapter, adapter, f.application.getPurchaseApproval());
        for (int months : new int[] {0, -1, Integer.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class,
                    () -> command.approve(reviewer, 0, months, false));
            assertThrows(IllegalArgumentException.class,
                    () -> f.application.getPurchaseApproval().approve(reviewer, 0, months, false));
        }
        assertThrows(IllegalArgumentException.class,
                () -> command.approve(reviewer, -1, 12, false));
        assertTrue(f.inventory.purchaseRequests().get(0).isPending());
        assertEquals(1, f.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, f.count("owned_vehicles"));
        assertEquals(0, f.count("payment_plans"));
    }

    private static final class Fixture {
        private final JdbcDatabase database;
        private final ConfiguredDealershipApplication application;
        private final UserAccountRepository accounts;
        private final JdbcInventoryRepository inventory;

        private Fixture(String label) {
            String url = "jdbc:h2:mem:purchase_parity_" + label + "_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
            database = new JdbcDatabase(url);
            application = DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url));
            accounts = TestAccounts.trustedRepository(application);
            TestAccounts.seed(accounts, AccountRole.EMPLOYEE, "reviewer", "ReviewerPass123!");
            TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "BuyerPass123!");
            inventory = new JdbcInventoryRepository(database);
        }

        private User reviewer() {
            return AuthenticatedAccounts.login(application,
                    AccountRole.EMPLOYEE, "reviewer", "ReviewerPass123!");
        }

        private int count(String table) {
            if (!"owned_vehicles".equals(table) && !"payment_plans".equals(table)) {
                throw new IllegalArgumentException("unexpected table");
            }
            try (Connection connection = database.openConnection();
                    Statement statement = connection.createStatement();
                    ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rows.next();
                return rows.getInt(1);
            } catch (Exception error) {
                throw new AssertionError(error);
            }
        }
    }
}
