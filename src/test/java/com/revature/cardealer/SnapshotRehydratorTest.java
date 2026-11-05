package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.service.InMemoryUserAccountRepository;
import com.revature.service.UserLoginService;

@Tag("TARGET-BEHAVIOR")
class SnapshotRehydratorTest {

    @Test
    void rebuildsSharedServicesAndAllDurableAggregates() {
        InMemoryUserAccountRepository sourceRepository = new InMemoryUserAccountRepository();
        UserLoginService sourceUsers = new UserLoginService(sourceRepository);
        sourceUsers.registerUser("customer-one", "secret");

        Offer sourceInventory = new Offer();
        sourceInventory.registerOffer("Honda", "Accord", 2020, 20_000, "yes", 2);
        sourceInventory.setpOffer("customer-one", 0);
        sourceInventory.setAccept(0, 20, true);

        PaymentPlan plan = new PaymentPlan(20_000, 20);
        plan.recordPayment(1_000);
        OwnedVehicle owned = new OwnedVehicle(new Car("Honda", "Accord", 2020), plan);
        PaymentTransaction transaction = new PaymentTransaction("PAY-000001", "customer-one",
                1_000, 1_000, 19_000, Instant.now());

        ApplicationStateSnapshot snapshot = new ApplicationStateSnapshot(
                sourceRepository.findAll(), sourceInventory.getListings(),
                sourceInventory.getPurchaseRequests(), Collections.singletonList(owned),
                Collections.singletonList(transaction));

        RehydratedApplicationState restored = new SnapshotRehydrator().rehydrate(snapshot);

        User credentials = new User();
        credentials.setUsername("customer-one");
        credentials.setPassword("secret");
        assertTrue(restored.getCustomerLoginService().authenticate(credentials).isPresent());
        assertTrue(restored.getEmployeeLoginService().authenticate(credentials).isPresent(),
                "all login services must share the rebuilt repository");
        assertEquals(1, restored.getInventory().getListingCount());
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                restored.getInventory().getPurchaseRequests().get(0).getStatus());
        assertEquals(1, restored.getCustomerLoginService().getOwnedVehicleRecords().size());
        assertEquals(19_000, restored.getCustomerLoginService().getOwnedVehicle(0)
                .getPaymentPlan().getRemainingBalance());
        assertEquals(1, restored.getPayments().getTransactions().size());
        assertEquals(19_000, restored.getPayments().getBalance());
    }

    @Test
    void rejectsDuplicateAccountsBeforeConstructingRuntimeState() {
        User first = account("duplicate", "encoded-one");
        User second = account("duplicate", "encoded-two");
        ApplicationStateSnapshot snapshot = new ApplicationStateSnapshot(
                Arrays.asList(first, second), Collections.emptyList(), Collections.emptyList(),
                Collections.emptyList(), Collections.emptyList());

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> new SnapshotRehydrator().rehydrate(snapshot));

        assertTrue(exception.getMessage().contains("duplicate username"));
    }

    @Test
    void rejectsRequestThatReferencesMissingListing() {
        PurchaseRequest request = new PurchaseRequest(0, 0, "customer");
        ApplicationStateSnapshot snapshot = new ApplicationStateSnapshot(
                Collections.emptyList(), Collections.emptyList(),
                Collections.singletonList(request), Collections.emptyList(), Collections.emptyList());

        assertThrows(IllegalArgumentException.class,
                () -> new SnapshotRehydrator().rehydrate(snapshot));
    }

    @Test
    void restoredLedgerContinuesStableTransactionSequence() {
        PaymentTransaction first = new PaymentTransaction("PAY-000004", "customer", 100,
                100, 100, Instant.now());
        Payments restored = Payments.restore(Collections.singletonList(first));

        restored.makePayment("customer", 100);

        assertEquals("PAY-000005", restored.getTransactions().get(1).getTransactionId());
        assertEquals(0, restored.getBalance());
    }

    private static User account(String username, String encodedPassword) {
        User account = new User();
        account.setUsername(username);
        account.setPassword(encodedPassword);
        account.setRole(AccountRole.CUSTOMER);
        return account;
    }
}
