package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Security invariants: account changes must permanently revoke old verified
 * sessions, even if role, username and encoded password later look identical.
 * Uses trusted fixtures; it does not provision public privileged identities.
 */
@Tag("SECURITY")
class SessionRevocationEpochRegressionTest {

    @Test
    void roleDemotionAndRestorationCannotResurrectMemorySession() {
        assertDemoteRestoreDoesNotResurrect(
                DealershipCompositionRoot.create(InfrastructureConfiguration.inMemory()));
    }

    @Test
    void roleDemotionAndRestorationCannotResurrectJdbcSession() {
        assertDemoteRestoreDoesNotResurrect(jdbc("demote_restore_"));
    }

    @Test
    void deletingAndRecreatingAnAccountWithTheSameHashCannotResurrectMemorySession() {
        assertDeleteRecreateDoesNotResurrect(
                DealershipCompositionRoot.create(InfrastructureConfiguration.inMemory()));
    }

    @Test
    void deletingAndRecreatingAnAccountWithTheSameHashCannotResurrectJdbcSession() {
        assertDeleteRecreateDoesNotResurrect(jdbc("delete_recreate_"));
    }

    private static ConfiguredDealershipApplication jdbc(String prefix) {
        String url = "jdbc:h2:mem:" + prefix + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        return DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url));
    }

    private static void assertDemoteRestoreDoesNotResurrect(
            ConfiguredDealershipApplication application) {
        UserAccountRepository accounts = TestAccounts.trustedRepository(application);
        TestAccounts.seed(accounts, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        User original = AuthenticatedAccounts.login(
                application, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        RoleAuthorizationService gate = application.getContext().getAuthorizationService();

        assertTrue(gate.isAuthorized(original, Permission.MANAGE_INVENTORY));
        assertTrue(accounts.updateRoleIfCurrent("staff",
                AccountRole.EMPLOYEE, AccountRole.CUSTOMER));
        // Do not check the cached session at the intermediate role: the
        // persisted revision alone must prevent revival after role restoration.
        assertTrue(accounts.updateRoleIfCurrent("staff",
                AccountRole.CUSTOMER, AccountRole.EMPLOYEE));

        assertFalse(gate.isAuthorized(original, Permission.MANAGE_INVENTORY),
                "reinstating a role must not resurrect the old verified session");
        User relogged = AuthenticatedAccounts.login(
                application, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        assertTrue(gate.isAuthorized(relogged, Permission.MANAGE_INVENTORY));
    }

    private static void assertDeleteRecreateDoesNotResurrect(
            ConfiguredDealershipApplication application) {
        UserAccountRepository accounts = TestAccounts.trustedRepository(application);
        TestAccounts.seed(accounts, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        User original = AuthenticatedAccounts.login(
                application, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        RoleAuthorizationService gate = application.getContext().getAuthorizationService();
        User previouslyStored = accounts.findByUsername("staff").orElseThrow(AssertionError::new);

        assertTrue(gate.isAuthorized(original, Permission.MANAGE_INVENTORY));
        assertTrue(accounts.removeByUsername("staff"));
        // No intermediate session check: recreate must still invalidate
        // the original verified object via its different persisted generation.

        // Exact same username, role AND encoded hash; new account incarnation.
        accounts.save(previouslyStored);
        User recreated = accounts.findByUsername("staff").orElseThrow(AssertionError::new);
        assertNotEquals(previouslyStored.getSessionRevision(), recreated.getSessionRevision(),
                "new account incarnation must get a new durable session revision");
        assertFalse(gate.isAuthorized(original, Permission.MANAGE_INVENTORY),
                "deleting/recreating account cannot reactivate the prior session");
        User relogged = AuthenticatedAccounts.login(
                application, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        assertTrue(gate.isAuthorized(relogged, Permission.MANAGE_INVENTORY));
    }
}
