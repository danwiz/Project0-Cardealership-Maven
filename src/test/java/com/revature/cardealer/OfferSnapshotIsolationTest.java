package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Regression evidence for protected legacy-facade read-only boundaries. */
@Tag("SECURITY")
class OfferSnapshotIsolationTest {

    @Test
    void returnedListingAndVehicleObjectsCannotMutateInternalInventory() {
        Offer offers = new Offer();
        offers.registerOffer("Honda", "Accord", 2021, 12000, "yes", 2);
        List<InventoryListing> snapshot = offers.getListings();
        assertThrows(UnsupportedOperationException.class, snapshot::clear);

        InventoryListing external = snapshot.get(0);
        external.decrementStock();
        external.remove();
        external.setPrice(1);
        external.getCar().setCarMake("Forged");
        Car exposedCar = offers.getCarDB(0);
        exposedCar.setCarModel("Altered");

        InventoryListing authoritativeRead = offers.getListings().get(0);
        assertEquals(2, authoritativeRead.getStockQuantity());
        assertEquals(12000, authoritativeRead.getPrice());
        assertTrue(authoritativeRead.isAvailable());
        assertEquals("Honda", authoritativeRead.getCar().getCarMake());
        assertEquals("Accord", offers.getCarDB(0).getCarModel());
    }

    @Test
    void requestSnapshotsCannotApproveOrRejectAuthoritativeRequests() {
        Offer offers = new Offer();
        offers.registerOffer("Toyota", "Corolla", 2020, 15000, "yes", 1);
        offers.setpOffer("buyer", 0);
        List<PurchaseRequest> requests = offers.getPurchaseRequests();
        assertThrows(UnsupportedOperationException.class, requests::clear);

        requests.get(0).reject();
        assertTrue(offers.getPurchaseRequests().get(0).isPending());
        assertEquals(1, offers.getListings().get(0).getStockQuantity());

        assertEquals(15000, offers.setAccept(0, 12, true));
        PurchaseRequest approved = offers.getPurchaseRequests().get(0);
        assertEquals(PurchaseRequestStatus.ACCEPTED, approved.getStatus());
        assertEquals(12, approved.getPaymentMonths());
        assertEquals(1250, approved.getMonthlyPayment());
        assertEquals("ACQ-000000", approved.getContractId().orElseThrow());
        assertEquals(0, offers.getListings().get(0).getStockQuantity());
        assertEquals(PurchaseRequestStatus.REJECTED, requests.get(0).getStatus());

        // Even accepted snapshots must not give the caller a path back to the
        // live request or retroactively change prior read snapshots.
        assertFalse(requests.get(0).isPending());
        assertThrows(IllegalStateException.class, approved::reject);
    }

    @Test
    void restoredCollectionsAreCopiedBeforeBecomingAuthoritativeState() {
        Car car = new Car("Ford", "Focus", 2018);
        InventoryListing sourceListing = new InventoryListing(0, car, 9000, 2, true);
        PurchaseRequest sourceRequest = new PurchaseRequest(0, 0, "restored-buyer");
        Offer restored = Offer.restore(Arrays.asList(sourceListing), Arrays.asList(sourceRequest));

        sourceListing.decrementStock();
        sourceListing.remove();
        car.setCarModel("Forged");
        sourceRequest.accept(9000, 12);

        InventoryListing authoritativeListing = restored.getListings().get(0);
        assertEquals(2, authoritativeListing.getStockQuantity());
        assertEquals("Focus", authoritativeListing.getCar().getCarModel());
        assertTrue(authoritativeListing.isAvailable());
        assertTrue(restored.getPurchaseRequests().get(0).isPending());
        assertEquals(9000, restored.setAccept(0, 12, true));
    }
}
