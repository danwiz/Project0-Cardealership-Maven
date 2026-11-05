package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;
import com.revature.service.UserAccountRepository;
import com.revature.service.Pbkdf2PasswordHasher;

/** Reviewer re-authorized before SQL lock must remain revoked after an ABA role change. */
@Tag("SECURITY")
class JdbcReviewerRevocationRaceTest {

    @Test
    void roleDemotionAndRestorationBetweenPolicyCheckAndSqlLockCannotApprove() {
        JdbcDatabase db = JdbcDatabase.inMemory("reviewer_aba_" + System.nanoTime());
        UserAccountRepository accounts = new JdbcUserAccountRepository(db);
        User storedReviewer = new User();
        storedReviewer.setUsername("reviewer");
        storedReviewer.setRole(AccountRole.EMPLOYEE);
        storedReviewer.setPassword(new Pbkdf2PasswordHasher().hash("test-reviewer-password"));
        accounts.save(storedReviewer);
        User reviewer = accounts.findByUsername("reviewer").orElseThrow(AssertionError::new);

        User buyer = new User();
        buyer.setUsername("buyer");
        buyer.setRole(AccountRole.CUSTOMER);
        buyer.setPassword(new Pbkdf2PasswordHasher().hash("test-buyer-password"));
        accounts.save(buyer);

        JdbcInventoryRepository inventory = new JdbcInventoryRepository(db);
        inventory.addListing("Honda", "Civic", 2022, 20000, 1);
        inventory.requestPurchase("buyer", 0);

        RoleAuthorizationService authorization = new RoleAuthorizationService() {
            private boolean revoked;
            @Override
            public void requireAuthorized(User actor, Permission permission) {
                super.requireAuthorized(actor, permission);
                if (!revoked && permission == Permission.REVIEW_PURCHASE_REQUESTS) {
                    revoked = true;
                    assertTrue(accounts.updateRoleIfCurrent("reviewer",
                            AccountRole.EMPLOYEE, AccountRole.CUSTOMER));
                    assertTrue(accounts.updateRoleIfCurrent("reviewer",
                            AccountRole.CUSTOMER, AccountRole.EMPLOYEE));
                }
            }
        };
        authorization.requireVerifiedSessions(() -> accounts);
        User credentials = new User();
        credentials.setUsername("reviewer");
        credentials.setPassword("test-reviewer-password");
        authorization.establishVerifiedSession(reviewer, credentials);

        JdbcPurchaseApprovalService approval = new JdbcPurchaseApprovalService(db, authorization);
        assertThrows(SecurityException.class,
                () -> approval.approve(reviewer, 0, 12, false),
                "persisted session generation must be locked and checked inside the transaction");

        assertTrue(inventory.purchaseRequests().get(0).isPending());
        assertEquals(1, inventory.listings().get(0).getStockQuantity());
        assertEquals(0, new JdbcOwnershipRepository(db, "buyer").findAll().size());
    }
}
