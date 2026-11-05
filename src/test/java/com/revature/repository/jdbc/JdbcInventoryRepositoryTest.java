package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.InventoryListing;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;

@Tag("TARGET-BEHAVIOR")
class JdbcInventoryRepositoryTest {

    @Test
    void listingsAndRequestsPersistAcrossRepositoryInstances() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_restart");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository first = new JdbcInventoryRepository(database);
        first.addListing("Honda", "Civic", 2022, 24000, 2);
        first.requestPurchase("buyer", 0);

        JdbcInventoryRepository reopened = new JdbcInventoryRepository(database);
        assertEquals(1, reopened.listingCount());
        assertEquals(1, reopened.purchaseRequests().size());
        assertEquals(PurchaseRequestStatus.PENDING, reopened.purchaseRequests().get(0).getStatus());
        assertEquals("Honda", reopened.carForListing(0).getCarMake());
    }

    @Test
    void acceptanceAtomicallyUpdatesRequestAndStock() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_acceptance");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Toyota", "Corolla", 2020, 18000, 1);
        repository.requestPurchase("buyer", 0);

        int price = repository.decideRequest(0, 18, true);

        PurchaseRequest request = repository.purchaseRequests().get(0);
        InventoryListing listing = repository.listings().get(0);
        assertEquals(18000, price);
        assertEquals(PurchaseRequestStatus.ACCEPTED, request.getStatus());
        assertEquals(18, request.getPaymentMonths());
        assertEquals(1000, request.getMonthlyPayment());
        assertEquals(0, listing.getStockQuantity());
        assertFalse(listing.isActive());
        assertFalse(listing.isAvailable());
    }

    @Test
    void requestCanBeDecidedOnlyOnceWithoutAdditionalStockMutation() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_one_decision");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Mazda", "3", 2021, 20000, 2);
        repository.requestPurchase("buyer", 0);
        repository.decideRequest(0, 20, true);

        assertThrows(IllegalStateException.class, () -> repository.decideRequest(0, 20, true));
        assertEquals(1, repository.listings().get(0).getStockQuantity());
    }

    @Test
    void rejectionDoesNotChangeInventoryStock() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_rejection");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Ford", "Focus", 2019, 12000, 3);
        repository.requestPurchase("buyer", 0);

        repository.decideRequest(0, 1, false);

        assertEquals(PurchaseRequestStatus.REJECTED, repository.purchaseRequests().get(0).getStatus());
        assertEquals(3, repository.listings().get(0).getStockQuantity());
    }

    @Test
    void rejectsAllPendingRequestsWithoutChangingFinalDecisions() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_reject_all");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Honda", "Accord", 2020, 21000, 3);
        repository.requestPurchase("buyer", 0);
        repository.requestPurchase("buyer", 0);
        repository.decideRequest(0, 12, true);

        repository.rejectAllPendingRequests();

        List<PurchaseRequest> requests = repository.purchaseRequests();
        assertEquals(PurchaseRequestStatus.ACCEPTED, requests.get(0).getStatus());
        assertEquals(PurchaseRequestStatus.REJECTED, requests.get(1).getStatus());
        assertEquals(2, repository.listings().get(0).getStockQuantity());
    }

    @Test
    void removedOrExhaustedListingsCannotReceiveRequests() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_availability");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("BMW", "3 Series", 2020, 30000, 1);
        repository.removeListing(0);

        assertThrows(IllegalStateException.class, () -> repository.requestPurchase("buyer", 0));
        assertFalse(repository.listings().get(0).isActive());
    }

    @Test
    void repositoryReturnsImmutableSnapshotsAndStableIdentifiers() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_immutable");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Honda", "Civic", 2020, 15000, 1);
        repository.addListing("Honda", "Accord", 2021, 20000, 1);
        repository.requestPurchase("buyer", 1);

        assertEquals(0, repository.listings().get(0).getId());
        assertEquals(1, repository.listings().get(1).getId());
        assertEquals(0, repository.purchaseRequests().get(0).getId());
        assertThrows(UnsupportedOperationException.class, repository.listings()::clear);
        assertThrows(UnsupportedOperationException.class, repository.purchaseRequests()::clear);
    }

    @Test
    void unknownIdentifiersAndInvalidTermsAreRejected() {
        JdbcDatabase database = JdbcDatabase.inMemory("inventory_validation");
        saveCustomer(database, "buyer");
        JdbcInventoryRepository repository = new JdbcInventoryRepository(database);
        repository.addListing("Honda", "Civic", 2020, 15000, 1);
        repository.requestPurchase("buyer", 0);

        assertThrows(IllegalArgumentException.class, () -> repository.carForListing(99));
        assertThrows(IllegalArgumentException.class, () -> repository.removeListing(99));
        assertThrows(IllegalArgumentException.class, () -> repository.decideRequest(0, 0, true));
        assertTrue(repository.purchaseRequests().get(0).isPending());
        assertEquals(1, repository.listings().get(0).getStockQuantity());
    }

    private static void saveCustomer(JdbcDatabase database, String username) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-credential");
        user.setRole(AccountRole.CUSTOMER);
        new JdbcUserAccountRepository(database).save(user);
    }
}