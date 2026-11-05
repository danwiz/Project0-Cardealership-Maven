package com.revature.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.Car;
import com.revature.cardealer.Offer;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.Payments;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.service.CustomerLoginService;

@Tag("TARGET-BEHAVIOR")
class DomainRepositoryAdapterTest {

    @Test
    void inventoryRepositoryPreservesStableListingAndRequestIdentifiers() {
        InventoryRepository repository = new OfferInventoryRepository(new Offer());
        repository.addListing("Honda", "Civic", 2021, 18000, 1);
        repository.requestPurchase("buyer", 0);
        int price = repository.decideRequest(0, 18, true);

        assertEquals(18000, price);
        assertEquals(0, repository.listings().get(0).getId());
        assertEquals(0, repository.purchaseRequests().get(0).getId());
        assertEquals(PurchaseRequestStatus.ACCEPTED,
                repository.purchaseRequests().get(0).getStatus());
        assertThrows(UnsupportedOperationException.class, repository.listings()::clear);
    }

    @Test
    void ownershipRepositorySupportsSnapshotAndReplacement() {
        CustomerLoginService customers = new CustomerLoginService();
        OwnershipRepository repository = new CustomerOwnershipRepository(customers);
        repository.add(new Car("Toyota", "Corolla", 2020), 15000, 30);

        assertEquals(1, repository.findAll().size());
        assertThrows(UnsupportedOperationException.class, repository.findAll()::clear);

        OwnedVehicle replacement = new OwnedVehicle(
                new Car("Mazda", "3", 2022), new PaymentPlan(20000, 20));
        repository.replaceAll(Collections.singletonList(replacement));

        assertEquals(1, repository.findAll().size());
        assertEquals("Mazda", repository.findAll().get(0).getVehicle().getCarMake());
    }

    @Test
    void paymentTransactionRepositoryExposesImmutableLedgerSnapshot() {
        Payments payments = new Payments();
        payments.setpPayment("buyer", 1000, 10);
        payments.makePayment("buyer", 100);
        PaymentTransactionRepository repository = new LedgerPaymentTransactionRepository(payments);

        assertEquals(1, repository.findAll().size());
        assertEquals("PAY-000001", repository.findAll().get(0).getTransactionId());
        assertTrue(repository.ledger() == payments);
        assertThrows(UnsupportedOperationException.class, repository.findAll()::clear);
    }
}
