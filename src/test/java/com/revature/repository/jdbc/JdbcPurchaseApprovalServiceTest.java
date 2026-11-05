package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.RoleAuthorizationService;

@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class JdbcPurchaseApprovalServiceTest {

    @Test
    void approvedPurchaseCommitsAllFourRowsAndReplaysIdempotentlyAcrossInstances() {
        JdbcDatabase database = newDatabase("purchase_success");
        User reviewer = account(database, "reviewer", AccountRole.EMPLOYEE);
        account(database, "buyer", AccountRole.CUSTOMER);
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(database);
        inventory.addListing("Honda", "Accord", 2020, 12000, 2);
        inventory.requestPurchase("buyer", 0);

        assertEquals(12000, service(database, reviewer).approve(reviewer, 0, 12, false));
        assertEquals(PurchaseRequestStatus.ACCEPTED, inventory.purchaseRequests().get(0).getStatus());
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(1, count(database, "owned_vehicles"));
        assertEquals(1, count(database, "payment_plans"));
        assertEquals("ACQ-000000", inventory.purchaseRequests().get(0).getContractId().orElseThrow());

        // Idempotency uses durable request/contract identity, not a static Java map.
        assertEquals(12000, service(database, reviewer).approve(reviewer, 0, 12, false));
        assertEquals(1, count(database, "owned_vehicles"));
        assertEquals(1, count(database, "payment_plans"));
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertThrows(IllegalStateException.class,
                () -> service(database, reviewer).approve(reviewer, 0, 24, false));
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
    }

    @Test
    void everyInjectedWriteFailureRollsBackTheEntirePurchase() {
        for (JdbcPurchaseApprovalService.Stage stage : JdbcPurchaseApprovalService.Stage.values()) {
            JdbcDatabase database = newDatabase("purchase_rollback_" + stage);
            User reviewer = account(database, "reviewer", AccountRole.EMPLOYEE);
            account(database, "buyer", AccountRole.CUSTOMER);
            JdbcInventoryRepository inventory = new JdbcInventoryRepository(database);
            inventory.addListing("Toyota", "Corolla", 2019, 18000, 1);
            inventory.requestPurchase("buyer", 0);
            inventory.requestPurchase("buyer", 0);

            JdbcPurchaseApprovalService service = new JdbcPurchaseApprovalService(
                    database, verificationPolicy(database, reviewer), reached -> {
                        if (stage == reached) throw new IllegalStateException("fault " + stage);
                    });
            assertThrows(IllegalStateException.class,
                    () -> service.approve(reviewer, 0, 18, true), stage.name());
            assertTrue(inventory.purchaseRequests().get(0).isPending(), stage.name());
            assertTrue(inventory.purchaseRequests().get(1).isPending(), stage.name());
            assertEquals(1, inventory.listings().get(0).getStockQuantity(), stage.name());
            assertEquals(0, count(database, "owned_vehicles"), stage.name());
            assertEquals(0, count(database, "payment_plans"), stage.name());
            // Retrying the same request after a rollback is safe.
            assertEquals(18000, service(database, reviewer)
                    .approve(reviewer, 0, 18, true), stage.name());
            assertEquals(1, count(database, "owned_vehicles"), stage.name());
            assertEquals(PurchaseRequestStatus.REJECTED, inventory.purchaseRequests().get(1).getStatus());
        }
    }

    @Test
    void rejectsUnprivilegedDeletedOrForgedReviewersAndWrongCustomerRole() {
        JdbcDatabase database = newDatabase("purchase_privilege");
        account(database, "buyer", AccountRole.CUSTOMER);
        User employee = account(database, "reviewer", AccountRole.EMPLOYEE);
        User forged = account(database, "fraud", AccountRole.CUSTOMER);
        forged.setRole(AccountRole.ADMINISTRATOR);
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(database);
        inventory.addListing("Ford", "Focus", 2020, 10000, 1);
        inventory.requestPurchase("buyer", 0);

        JdbcPurchaseApprovalService service = service(database, employee);
        assertThrows(SecurityException.class, () -> service.approve(forged, 0, 12, false));
        User badHash = new User();
        badHash.setUsername(employee.getUsername());
        badHash.setRole(AccountRole.EMPLOYEE);
        badHash.setPassword("not-the-saved-hash");
        assertThrows(SecurityException.class, () -> service.approve(badHash, 0, 12, false));
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(0, count(database, "owned_vehicles"));

        // The persisted buyer role is rechecked in the same transaction.
        try (Connection connection = database.openConnection();
                Statement change = connection.createStatement()) {
            change.executeUpdate("UPDATE accounts SET role = 'EMPLOYEE' WHERE username = 'buyer'");
        } catch (Exception exception) { throw new AssertionError(exception); }
        assertThrows(SecurityException.class, () -> service.approve(employee, 0, 12, false));
        assertTrue(inventory.purchaseRequests().get(0).isPending());
    }

    @Test
    void twoBuyersCannotConsumeOneVehicleWithSeparateJdbcConnections() throws Exception {
        JdbcDatabase database = newDatabase("purchase_competing");
        User reviewer = account(database, "reviewer", AccountRole.EMPLOYEE);
        account(database, "buyer1", AccountRole.CUSTOMER);
        account(database, "buyer2", AccountRole.CUSTOMER);
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(database);
        inventory.addListing("Kia", "Soul", 2021, 19000, 1);
        inventory.requestPurchase("buyer1", 0);
        inventory.requestPurchase("buyer2", 0);

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Boolean> first = executor.submit(() -> attempt(service(database, reviewer),
                    reviewer, 0, start));
            Future<Boolean> second = executor.submit(() -> attempt(service(database, reviewer),
                    reviewer, 1, start));
            start.countDown();
            boolean firstWon = first.get(20, TimeUnit.SECONDS);
            boolean secondWon = second.get(20, TimeUnit.SECONDS);
            assertTrue(firstWon != secondWon, "only one complete purchase can win");
            assertEquals(0, inventory.listings().get(0).getStockQuantity());
            assertEquals(1, count(database, "owned_vehicles"));
            assertEquals(1, count(database, "payment_plans"));
            long accepted = inventory.purchaseRequests().stream()
                    .filter(x -> x.getStatus() == PurchaseRequestStatus.ACCEPTED).count();
            assertEquals(1L, accepted);
            long pending = inventory.purchaseRequests().stream()
                    .filter(x -> x.getStatus() == PurchaseRequestStatus.PENDING).count();
            assertEquals(1L, pending);
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    private static boolean attempt(JdbcPurchaseApprovalService service,
            User reviewer, int requestId, CountDownLatch start) {
        try {
            if (!start.await(5, TimeUnit.SECONDS)) throw new AssertionError("start timeout");
            service.approve(reviewer, requestId, 12, false);
            return true;
        } catch (IllegalStateException expectedConcurrentFailure) {
            return false;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new AssertionError(interrupted);
        }
    }

    /** Session policy is required even when callers bypass the application command. */
    private static JdbcPurchaseApprovalService service(JdbcDatabase database, User reviewer) {
        return new JdbcPurchaseApprovalService(database, verificationPolicy(database, reviewer));
    }

    private static RoleAuthorizationService verificationPolicy(JdbcDatabase database, User reviewer) {
        RoleAuthorizationService policy = new RoleAuthorizationService();
        policy.requireVerifiedSessions(() -> new JdbcUserAccountRepository(database));
        User credentials = new User();
        credentials.setUsername(reviewer.getUsername());
        credentials.setPassword("fixture-password-" + reviewer.getUsername());
        policy.establishVerifiedSession(reviewer, credentials);
        return policy;
    }

    @Test
    void cannotConstructJdbcPurchaseServiceWithPolicyOnlyAuthorization() {
        JdbcDatabase database = newDatabase("purchase_policy_configuration");
        assertThrows(SecurityException.class,
                () -> new JdbcPurchaseApprovalService(database, new RoleAuthorizationService()));
        RoleAuthorizationService configured = new RoleAuthorizationService();
        configured.requireVerifiedSessions(() -> new JdbcUserAccountRepository(database));
        assertTrue(configured.enforcesVerifiedSessions());
        // A properly configured instance has no implicit authenticated reviewer.
        JdbcPurchaseApprovalService service = new JdbcPurchaseApprovalService(database, configured);
        assertThrows(SecurityException.class,
                () -> service.approve(new User(), 0, 12, false));
    }

    @Test
    void directExecutorRejectsCopiedStoredAccountWithoutVerifiedSession() {
        JdbcDatabase database = newDatabase("purchase_public_api_boundary");
        User reviewer = account(database, "reviewer", AccountRole.EMPLOYEE);
        account(database, "buyer", AccountRole.CUSTOMER);
        JdbcInventoryRepository inventory = new JdbcInventoryRepository(database);
        inventory.addListing("Honda", "Civic", 2020, 22000, 1);
        inventory.requestPurchase("buyer", 0);
        JdbcPurchaseApprovalService executor = service(database, reviewer);

        // The database exposes an account record, NOT an authenticated session.
        User stolenRecord = new JdbcUserAccountRepository(database)
                .findByUsername("reviewer").orElseThrow(AssertionError::new);
        assertThrows(SecurityException.class, () -> executor.approve(stolenRecord, 0, 12, false));
        assertEquals(0, count(database, "owned_vehicles"));
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(22000, executor.approve(reviewer, 0, 12, false));
    }

    private static JdbcDatabase newDatabase(String suffix) {
        return JdbcDatabase.inMemory(suffix + "_" + System.nanoTime());
    }

    private static User account(JdbcDatabase database, String username, AccountRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword(new Pbkdf2PasswordHasher().hash("fixture-password-" + username));
        user.setRole(role);
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
        accounts.save(user);
        return accounts.findByUsername(username).orElseThrow(AssertionError::new);
    }

    private static int count(JdbcDatabase database, String table) {
        if (!"owned_vehicles".equals(table) && !"payment_plans".equals(table)) {
            throw new IllegalArgumentException("unsupported test table");
        }
        try (Connection connection = database.openConnection();
                Statement statement = connection.createStatement();
                ResultSet rows = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            rows.next();
            return rows.getInt(1);
        } catch (Exception exception) { throw new AssertionError(exception); }
    }
}
