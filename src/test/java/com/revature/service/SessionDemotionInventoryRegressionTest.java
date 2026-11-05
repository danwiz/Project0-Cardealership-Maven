package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.ConfiguredDomainAdapter;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.application.ManageInventoryCommand;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/**
 * Negative tests transferred by behavior from the short legacy-console branch.
 * These checks prove immediate denial after persisted role demotion; they do
 * NOT prove irreversible invalidation after demote-then-restore (issue #98).
 */
@Tag("SECURITY")
class SessionDemotionInventoryRegressionTest {

    @Test
    void demotionDeniesExistingEmployeeInventorySessionInMemory() {
        assertDemotionDeniesExistingSession(
                DealershipCompositionRoot.create(InfrastructureConfiguration.inMemory()));
    }

    @Test
    void demotionDeniesExistingEmployeeInventorySessionJdbc() {
        String url = "jdbc:h2:mem:inventory_role_revocation_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        assertDemotionDeniesExistingSession(
                DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url)));
    }


    @Test
    void customerForgeryAndDeletedEmployeeCannotMutateInventoryInMemory() {
        assertUntrustedAndRevokedActorsNeverMutateInventory(
                DealershipCompositionRoot.create(InfrastructureConfiguration.inMemory()));
    }

    @Test
    void customerForgeryAndDeletedEmployeeCannotMutateInventoryJdbc() {
        String url = "jdbc:h2:mem:inventory_actor_boundary_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        assertUntrustedAndRevokedActorsNeverMutateInventory(
                DealershipCompositionRoot.create(InfrastructureConfiguration.jdbc(url)));
    }

    private static void assertUntrustedAndRevokedActorsNeverMutateInventory(
            ConfiguredDealershipApplication application) {
        UserAccountRepository accounts = TestAccounts.trustedRepository(application);
        TestAccounts.seed(accounts, AccountRole.CUSTOMER, "buyer", "BuyerPass123!");
        TestAccounts.seed(accounts, AccountRole.EMPLOYEE, "staff", "StaffPass123!");

        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        ManageInventoryCommand command = new ManageInventoryCommand(adapter, adapter);

        User invalid = new User();
        invalid.setUsername("staff");
        invalid.setPassword("WrongPassword");
        com.revature.application.AuthenticateAccountCommand.Result bad =
                new com.revature.application.AuthenticateAccountCommand(
                        new com.revature.application.ContextIdentityAdapter(
                                application.getContext()), adapter)
                        .execute(AccountRole.EMPLOYEE, invalid, Permission.MANAGE_INVENTORY);
        assertEquals(com.revature.application.AuthenticateAccountCommand.Status.INVALID_CREDENTIALS,
                bad.getStatus());

        User buyer = AuthenticatedAccounts.login(application,
                AccountRole.CUSTOMER, "buyer", "BuyerPass123!");
        int initial = application.getInventory().listingCount();
        assertThrows(SecurityException.class,
                () -> command.add(buyer, "Honda", "Civic", 2022, 17000, 1));
        assertThrows(SecurityException.class, () -> command.remove(buyer, 0));

        // Matching username, encoded hash and session revision are still
        // insufficient: a copied User was never authenticated by this context.
        User forged = new User();
        forged.setUsername(buyer.getUsername());
        forged.setPassword(buyer.getPassword());
        forged.setSessionRevision(buyer.getSessionRevision());
        forged.setRole(AccountRole.ADMINISTRATOR);
        assertThrows(SecurityException.class,
                () -> command.add(forged, "Ford", "Focus", 2021, 12000, 1));
        assertThrows(SecurityException.class, () -> command.remove(forged, 0));
        assertEquals(initial, application.getInventory().listingCount());

        User staff = AuthenticatedAccounts.login(application,
                AccountRole.EMPLOYEE, "staff", "StaffPass123!");
        command.add(staff, "Toyota", "Corolla", 2022, 19000, 1);
        int afterAuthorized = application.getInventory().listingCount();
        assertEquals(initial + 1, afterAuthorized);

        // Deletion invalidates the verified actor immediately. No add/remove
        // command may touch either H2 or in-memory inventory after revocation.
        assertTrue(accounts.removeByUsername("staff"));
        assertThrows(SecurityException.class,
                () -> command.add(staff, "Chevrolet", "Bolt", 2020, 14000, 1));
        assertThrows(SecurityException.class, () -> command.remove(staff, 0));
        assertEquals(afterAuthorized, application.getInventory().listingCount());
    }

    private static void assertDemotionDeniesExistingSession(
            ConfiguredDealershipApplication application) {
        UserAccountRepository repository = TestAccounts.trustedRepository(application);
        TestAccounts.seed(repository, AccountRole.EMPLOYEE, "staff", "TestPass123!");

        User authenticated = AuthenticatedAccounts.login(application,
                AccountRole.EMPLOYEE, "staff", "TestPass123!");
        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        ManageInventoryCommand command = new ManageInventoryCommand(adapter, adapter);

        int initial = application.getInventory().listingCount();
        command.add(authenticated, "Honda", "Accord", 2020, 15000, 2);
        assertEquals(initial + 1, application.getInventory().listingCount());

        // A trusted fixture changes persisted authority after the actor logged in.
        assertTrue(repository.updateRoleIfCurrent(
                "staff", AccountRole.EMPLOYEE, AccountRole.CUSTOMER));

        assertThrows(SecurityException.class,
                () -> command.add(authenticated, "Toyota", "Camry", 2021, 18000, 1));
        assertThrows(SecurityException.class,
                () -> command.remove(authenticated, 0));
        assertEquals(initial + 1, application.getInventory().listingCount());
        assertEquals(AccountRole.CUSTOMER,
                repository.findByUsername("staff").orElseThrow(AssertionError::new).getRole());
    }
}
