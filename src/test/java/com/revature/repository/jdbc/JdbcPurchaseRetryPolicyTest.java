package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.service.RoleAuthorizationService;

/** No implicit retries for unknown commit outcomes or unrelated SQL failures. */
@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class JdbcPurchaseRetryPolicyTest {

    @Test
    void retriesWholeTransactionOnlyAfterSerializableConflict() {
        verifyRecoveredConflict("40001");
        verifyRecoveredConflict("40P01");
    }

    private static void verifyRecoveredConflict(String state) {
        Fixture fixture = new Fixture("conflict_" + state);
        AtomicInteger attempts = new AtomicInteger();
        JdbcPurchaseApprovalService retrying = fixture.withFault(stage -> {
            if (stage == JdbcPurchaseApprovalService.Stage.AFTER_PLAN
                    && attempts.incrementAndGet() < 3) {
                throw new SQLException("injected rolled-back conflict", state);
            }
        });

        assertEquals(24000, retrying.approve(fixture.reviewer, 0, 24, false));
        assertEquals(3, attempts.get(), "one initial attempt and two retries");
        assertEquals(1, fixture.inventory.listings().get(0).getStockQuantity());
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                fixture.inventory.purchaseRequests().get(0).getStatus());
        assertEquals(1, new JdbcOwnershipRepository(fixture.database, "buyer").findAll().size());
        assertEquals(24000, fixture.service().approve(fixture.reviewer, 0, 24, false));
        assertEquals(1, new JdbcOwnershipRepository(fixture.database, "buyer").findAll().size());
    }

    @Test
    void exhaustionReturnsExplicitFailureWithoutAnyPartialRows() {
        Fixture fixture = new Fixture("exhaustion");
        AtomicInteger attempts = new AtomicInteger();
        JdbcPurchaseApprovalService retrying = fixture.withFault(stage -> {
            if (stage == JdbcPurchaseApprovalService.Stage.AFTER_PLAN) {
                attempts.incrementAndGet();
                throw new SQLException("injected serialization exhaustion", "40001");
            }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> retrying.approve(fixture.reviewer, 0, 24, false));
        assertTrue(failure.getMessage().contains("bounded retries"));
        assertEquals(JdbcPurchaseApprovalService.MAX_TRANSACTION_ATTEMPTS, attempts.get());
        assertUnchanged(fixture);
    }

    @Test
    void neverRetriesConstraintViolationOrConnectionOutcomeUncertainty() {
        verifyNotRetried("23505", false);
        verifyNotRetried("08006", true);
        verifyNotRetried("40003", true);
    }

    private static void verifyNotRetried(String state, boolean uncertain) {
        Fixture fixture = new Fixture("non_retry_" + state);
        AtomicInteger attempts = new AtomicInteger();
        JdbcPurchaseApprovalService failing = fixture.withFault(stage -> {
            if (stage == JdbcPurchaseApprovalService.Stage.AFTER_STOCK) {
                attempts.incrementAndGet();
                throw new SQLException("injected SQL error", state);
            }
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> failing.approve(fixture.reviewer, 0, 24, false));
        assertEquals(1, attempts.get(), "never retry an arbitrary SQL failure");
        assertEquals(uncertain, failure.getMessage().contains("outcome uncertain"));
        assertUnchanged(fixture);

        // Recovery must use the *same* request ID and terms after
        // independently reconciling the persisted state.
        assertEquals(24000, fixture.service().approve(fixture.reviewer, 0, 24, false));
        assertEquals(1, new JdbcOwnershipRepository(fixture.database, "buyer").findAll().size());
    }

    private static void assertUnchanged(Fixture fixture) {
        assertTrue(fixture.inventory.purchaseRequests().get(0).isPending());
        assertEquals(2, fixture.inventory.listings().get(0).getStockQuantity());
        assertEquals(0, new JdbcOwnershipRepository(fixture.database, "buyer").findAll().size());
    }

    private static final class Fixture {
        private final JdbcDatabase database;
        private final JdbcInventoryRepository inventory;
        private final RoleAuthorizationService authorization;
        private final User reviewer;

        private Fixture(String name) {
            database = JdbcDatabase.inMemory("retry_" + name + "_" + System.nanoTime());
            JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
            User newReviewer = new User();
            newReviewer.setUsername("reviewer");
            newReviewer.setRole(AccountRole.EMPLOYEE);
            newReviewer.setPassword(new Pbkdf2PasswordHasher().hash("reviewer-password"));
            accounts.save(newReviewer);
            reviewer = accounts.findByUsername("reviewer").orElseThrow(AssertionError::new);
            User buyer = new User();
            buyer.setUsername("buyer");
            buyer.setRole(AccountRole.CUSTOMER);
            buyer.setPassword(new Pbkdf2PasswordHasher().hash("buyer-password"));
            accounts.save(buyer);

            authorization = new RoleAuthorizationService();
            authorization.requireVerifiedSessions(() -> accounts);
            User credentials = new User();
            credentials.setUsername("reviewer");
            credentials.setPassword("reviewer-password");
            authorization.establishVerifiedSession(reviewer, credentials);

            inventory = new JdbcInventoryRepository(database);
            inventory.addListing("Toyota", "Corolla", 2023, 24000, 2);
            inventory.requestPurchase("buyer", 0);
        }

        private JdbcPurchaseApprovalService service() {
            return new JdbcPurchaseApprovalService(database, authorization);
        }

        private JdbcPurchaseApprovalService withFault(JdbcPurchaseApprovalService.FailureHook hook) {
            return new JdbcPurchaseApprovalService(database, authorization, hook);
        }
    }
}
