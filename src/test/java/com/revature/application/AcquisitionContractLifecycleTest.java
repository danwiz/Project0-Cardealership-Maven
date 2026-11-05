package com.revature.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;

@Tag("TARGET-BEHAVIOR")
class AcquisitionContractLifecycleTest {

    @Test
    void acceptedRequestOwnershipAndPaymentShareContractAcrossRestart() {
        String url = "jdbc:h2:mem:contract_lifecycle_" + System.nanoTime()
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE";
        InfrastructureConfiguration configuration = InfrastructureConfiguration.jdbc(url);
        ConfiguredDealershipApplication application = DealershipCompositionRoot.create(configuration);
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.CUSTOMER, "buyer", "TestPass123!");
        com.revature.support.TestAccounts.seed(com.revature.support.TestAccounts.trustedRepository(application), AccountRole.EMPLOYEE, "employee", "TestPass123!");
        application.getInventory().addListing("Honda", "Accord", 2024, 24000, 1);
        application.getInventory().requestPurchase("buyer", 0);

        ConfiguredDomainAdapter domain = new ConfiguredDomainAdapter(application);
        new ReviewPurchaseRequestCommand(domain, domain, domain)
                .approve(com.revature.support.AuthenticatedAccounts.login(application, AccountRole.EMPLOYEE, "employee", "TestPass123!"), 0, 24, false);

        PurchaseRequest accepted = application.getInventory().purchaseRequests().get(0);
        String contractId = accepted.getContractId().orElseThrow(AssertionError::new);
        assertEquals("ACQ-000000", contractId);

        OwnedVehicle ownership = application.ownershipFor("buyer").findAll().get(0);
        assertEquals(contractId, ownership.getContractId().orElseThrow(AssertionError::new));

        PaymentTransaction payment = new RecordPaymentCommand(application).execute(
                com.revature.support.AuthenticatedAccounts.login(application, AccountRole.CUSTOMER, "buyer", "TestPass123!"), "buyer", ownership.getOwnershipId(), 1000);
        assertEquals(contractId, payment.getContractId().orElseThrow(AssertionError::new));
        assertTrue(payment.getOwnershipId().isPresent());
        assertEquals(ownership.getOwnershipId(), payment.getOwnershipId().getAsLong());

        ConfiguredDealershipApplication restarted = DealershipCompositionRoot.create(configuration);
        assertEquals(contractId, restarted.getInventory().purchaseRequests().get(0)
                .getContractId().orElseThrow(AssertionError::new));
        assertEquals(contractId, restarted.ownershipFor("buyer").findAll().get(0)
                .getContractId().orElseThrow(AssertionError::new));
        assertEquals(contractId, restarted.paymentsFor("buyer").findAll().get(0)
                .getContractId().orElseThrow(AssertionError::new));
    }

    @Test
    void contractIdentifierIsDeterministicAndValidated() {
        assertEquals("ACQ-000042", PurchaseRequest.contractIdFor(42));
        assertTrue(new PurchaseRequest(7, 1, "buyer").getContractId().isEmpty());
    }

    private static User user(String username, AccountRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-credential");
        user.setRole(role);
        return user;
    }
}
