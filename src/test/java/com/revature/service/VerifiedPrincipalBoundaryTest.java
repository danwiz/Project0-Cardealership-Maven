package com.revature.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.AuthenticateAccountCommand;
import com.revature.application.AcquisitionLifecycleQueryService;
import com.revature.application.ConfiguredDealershipApplication;
import com.revature.application.ConfiguredDomainAdapter;
import com.revature.application.ContextIdentityAdapter;
import com.revature.application.DealershipCompositionRoot;
import com.revature.application.InfrastructureConfiguration;
import com.revature.application.ManageInventoryCommand;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.User;
import com.revature.support.AuthenticatedAccounts;
import com.revature.support.TestAccounts;

/** Regression tests for the distinction between a mutable User DTO and verified login. */
@Tag("SECURITY")
class VerifiedPrincipalBoundaryTest {

    @Test
    void forgedAdministratorAndCopiedAccountAreNotVerifiedSessions() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.ADMINISTRATOR, "admin", "TestPass123!");
        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        ManageInventoryCommand command = new ManageInventoryCommand(adapter, adapter);

        User forged = user("admin", "TestPass123!", AccountRole.ADMINISTRATOR);
        application.getContext().setCurrentAccount(forged);
        assertThrows(SecurityException.class,
                () -> command.add(forged, "Honda", "Civic", 2022, 10000, 1));

        User verified = AuthenticatedAccounts.login(application, AccountRole.ADMINISTRATOR, "admin", "TestPass123!");
        User copied = user(verified.getUsername(), verified.getPassword(), verified.getRole());
        assertThrows(SecurityException.class,
                () -> command.add(copied, "Honda", "Civic", 2022, 10000, 1));
        command.add(verified, "Honda", "Civic", 2022, 10000, 1);
        assertEquals(1, application.getInventory().listingCount());
    }

    @Test
    void editingAValidSessionRoleNeverElevatesCustomer() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        application.getContext().getUserLoginService().registerUser("buyer", "TestPass123!");
        User verified = AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "buyer", "TestPass123!");
        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        ManageInventoryCommand command = new ManageInventoryCommand(adapter, adapter);

        verified.setRole(AccountRole.ADMINISTRATOR);
        assertThrows(SecurityException.class,
                () -> command.add(verified, "BMW", "3", 2021, 19000, 1));
        assertFalse(adapter.isAuthorized(verified, Permission.MANAGE_USERS));
        assertEquals(AccountRole.CUSTOMER, application.getAccounts().findByUsername("buyer")
                .orElseThrow(AssertionError::new).getRole());
    }

    @Test
    void deletedOrPasswordRotatedAccountLosesExistingSession() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.EMPLOYEE, "staff", "TestPass123!");
        User verified = AuthenticatedAccounts.login(application, AccountRole.EMPLOYEE, "staff", "TestPass123!");
        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        assertTrue(adapter.isAuthorized(verified, Permission.MANAGE_INVENTORY));

        TestAccounts.rotateCredential(com.revature.support.TestAccounts.trustedRepository(application), "staff", "ChangedPass123!");
        assertFalse(adapter.isAuthorized(verified, Permission.MANAGE_INVENTORY));
        TestAccounts.trustedRepository(application).removeByUsername("staff");
        assertFalse(adapter.isAuthorized(verified, Permission.MANAGE_INVENTORY));
    }

    @Test
    void persistentJdbcAdapterAlsoRejectsForgedAdministrator() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc("jdbc:h2:mem:verified_actor_" + System.nanoTime()
                    + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"));
        TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.ADMINISTRATOR, "admin", "TestPass123!");
        ConfiguredDomainAdapter adapter = new ConfiguredDomainAdapter(application);
        ManageInventoryCommand command = new ManageInventoryCommand(adapter, adapter);
        User fake = user("admin", "invalid", AccountRole.ADMINISTRATOR);
        assertThrows(SecurityException.class,
                () -> command.add(fake, "BMW", "3", 2021, 19000, 1));
        assertEquals(0, application.getInventory().listingCount());

        User verified = AuthenticatedAccounts.login(application, AccountRole.ADMINISTRATOR, "admin", "TestPass123!");
        command.add(verified, "BMW", "3", 2021, 19000, 1);
        assertEquals(1, application.getInventory().listingCount());
    }

    @Test
    void loginCannotClaimAnAdministratorRoleForCustomerCredentials() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        application.getContext().getUserLoginService().registerUser("buyer", "TestPass123!");
        User credentials = user("buyer", "TestPass123!", AccountRole.ADMINISTRATOR);
        AuthenticateAccountCommand.Result result = new AuthenticateAccountCommand(
                new ContextIdentityAdapter(application.getContext()),
                new ConfiguredDomainAdapter(application))
                .execute(AccountRole.ADMINISTRATOR, credentials, Permission.VIEW_INVENTORY);
        assertEquals(AuthenticateAccountCommand.Status.INVALID_CREDENTIALS, result.getStatus());
    }


    @Test
    void unauthenticatedActorCannotProbeAcquisitionContractExistence() {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.inMemory());
        User forged = user("admin", "invented", AccountRole.ADMINISTRATOR);
        AcquisitionLifecycleQueryService queries = new AcquisitionLifecycleQueryService(application);
        assertThrows(SecurityException.class, () -> queries.find(forged, "ACQ-000000"));
        assertThrows(SecurityException.class, () -> queries.find(forged, "ACQ-999999"));
    }

    private static User user(String username, String password, AccountRole role) {
        User result = new User();
        result.setUsername(username);
        result.setPassword(password);
        result.setRole(role);
        return result;
    }
}
