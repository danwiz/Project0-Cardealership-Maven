package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.application.AcquisitionLifecycleQueryService.AcquisitionLifecycleView;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.User;

@Tag("TARGET-BEHAVIOR")
class AcquisitionLifecycleQueryServiceTest {

    @Test
    void reconstructsCompleteJdbcLifecycleAcrossRestart() {
        String url = "jdbc:h2:mem:lifecycle_query_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        InfrastructureConfiguration configuration = InfrastructureConfiguration.jdbc(url);
        ConfiguredDealershipApplication application = createAcceptedContract(configuration, "buyer");
        OwnedVehicle ownership = application.ownershipFor("buyer").findAll().get(0);
        RecordPaymentCommand payments = new RecordPaymentCommand(application);
        payments.execute(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "buyer", "TestPass123!"), "buyer", ownership.getOwnershipId(), 500);
        payments.execute(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.EMPLOYEE, "employee", "TestPass123!"), "buyer", ownership.getOwnershipId(), 250);

        ConfiguredDealershipApplication restarted = DealershipCompositionRoot.create(configuration);
        AcquisitionLifecycleView view = new AcquisitionLifecycleQueryService(restarted)
                .find(com.revature.support.AuthenticatedAccounts.login(restarted, AccountRole.EMPLOYEE, "employee", "TestPass123!"), "ACQ-000000");

        assertEquals("ACQ-000000", view.getContractId());
        assertEquals(0, view.getRequestId());
        assertEquals(0, view.getListingId());
        assertEquals("buyer", view.getCustomerName());
        assertEquals(ownership.getOwnershipId(), view.getOwnershipId());
        assertEquals("Honda", view.getMake());
        assertEquals("Accord", view.getModel());
        assertEquals(2024, view.getYear());
        assertEquals(24000, view.getPurchasePrice());
        assertEquals(750, view.getAmountPaid());
        assertEquals(23250, view.getRemainingBalance());
        assertEquals(2, view.getPayments().size());
        assertEquals("PAY-000001", view.getPayments().get(0).getTransactionId());
        assertEquals("PAY-000002", view.getPayments().get(1).getTransactionId());
        assertEquals(750, view.getPayments().get(1).getCumulativePaid());
    }

    @Test
    void customerCanReadOwnContractButNotAnotherCustomersContract() {
        ConfiguredDealershipApplication application = createAcceptedContract(
                InfrastructureConfiguration.inMemory(), "buyer");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, "other", "TestPass123!");
        AcquisitionLifecycleQueryService query = new AcquisitionLifecycleQueryService(application);

        assertEquals("buyer", query.find(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "buyer", "TestPass123!"), "ACQ-000000")
                .getCustomerName());
        assertThrows(SecurityException.class,
                () -> query.find(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "other", "TestPass123!"), "ACQ-000000"));
    }

    @Test
    void rejectsMalformedAndUnknownContractIdentifiers() {
        ConfiguredDealershipApplication application = createAcceptedContract(
                InfrastructureConfiguration.inMemory(), "buyer");
        AcquisitionLifecycleQueryService query = new AcquisitionLifecycleQueryService(application);
        User employee = com.revature.support.AuthenticatedAccounts.login(application,
                AccountRole.EMPLOYEE, "employee", "TestPass123!");

        assertThrows(IllegalArgumentException.class, () -> query.find(employee, "ACQ-1"));
        assertThrows(IllegalArgumentException.class, () -> query.find(employee, "ACQ-999999"));
    }

    private static ConfiguredDealershipApplication createAcceptedContract(
            InfrastructureConfiguration configuration, String customerName) {
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(configuration);
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, customerName, "TestPass123!");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.EMPLOYEE, "employee", "TestPass123!");
        application.getInventory().addListing("Honda", "Accord", 2024, 24000, 1);
        application.getInventory().requestPurchase(customerName, 0);
        ConfiguredDomainAdapter domain = new ConfiguredDomainAdapter(application);
        new ReviewPurchaseRequestCommand(domain, domain, domain)
                .approve(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.EMPLOYEE, "employee", "TestPass123!"), 0, 24, false);
        return application;
    }

    private static User user(String username, AccountRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-credential");
        user.setRole(role);
        return user;
    }
}
