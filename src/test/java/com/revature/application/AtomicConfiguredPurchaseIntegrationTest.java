package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.repository.jdbc.JdbcDatabase;
import com.revature.repository.jdbc.JdbcUserAccountRepository;
import com.revature.service.Pbkdf2PasswordHasher;
import com.revature.support.AuthenticatedAccounts;

/** Verifies the actual configured console command path uses the atomic port. */
@Tag("SECURITY")
@Tag("TARGET-BEHAVIOR")
class AtomicConfiguredPurchaseIntegrationTest {

    @Test
    void configuredJdbcCommandCreatesDurableOwnershipInOneApproval() {
        String url = "jdbc:h2:mem:configured_atomic_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(
                InfrastructureConfiguration.jdbc(url));
        assertNotNull(application.getPurchaseApproval());
        JdbcDatabase database = new JdbcDatabase(url);

        // Test fixture creates roles outside of public self-registration.
        User employee = new User();
        employee.setUsername("reviewer");
        employee.setPassword(new Pbkdf2PasswordHasher().hash("valid-secret"));
        employee.setRole(AccountRole.EMPLOYEE);
        new JdbcUserAccountRepository(database).save(employee);
        application.getContext().getCustomerLoginService().registerUser("buyer", "valid-secret");
        User authorized = AuthenticatedAccounts.login(
                application, AccountRole.EMPLOYEE, "reviewer", "valid-secret");

        application.getInventory().addListing("Honda", "Civic", 2022, 24000, 2);
        application.getInventory().requestPurchase("buyer", 0);
        ConfiguredDomainAdapter domain = new ConfiguredDomainAdapter(application);
        ReviewPurchaseRequestCommand command = new ReviewPurchaseRequestCommand(
                domain, domain, domain, application.getPurchaseApproval());

        User forged = new User();
        forged.setUsername(authorized.getUsername());
        forged.setPassword(authorized.getPassword());
        forged.setRole(authorized.getRole());
        assertThrows(SecurityException.class, () -> command.approve(forged, 0, 24, false));
        assertTrue(application.getInventory().purchaseRequests().get(0).isPending());

        assertEquals(24000, command.approve(authorized, 0, 24, false));
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                application.getInventory().purchaseRequests().get(0).getStatus());
        assertEquals(1, application.getInventory().listings().get(0).getStockQuantity());
        assertEquals(1, application.ownershipFor("buyer").findAll().size());
        assertEquals("ACQ-000000",
                application.ownershipFor("buyer").findAll().get(0).getContractId().orElseThrow());
        assertEquals(24000,
                application.ownershipFor("buyer").findAll().get(0).getPaymentPlan().getRemainingBalance());
        assertEquals(24000, command.approve(authorized, 0, 24, false));
        assertEquals(1, application.ownershipFor("buyer").findAll().size());
    }
}
