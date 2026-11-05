package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("TARGET-BEHAVIOR")
class OfferCharacterizationTest {

    @Test
    void setOfferRespectsAvailability() {
        Offer offer = new Offer();
        offer.setOffer(new Car("Honda", "Accord", 2018), 15000, "no", 2);
        assertEquals("no", offer.getAvail());
        assertFalse(offer.isAvail());
        assertEquals(15000, offer.getPrice());
        assertEquals(2, offer.getAmt());
    }

    @Test
    void pendingRequestCanBeAcceptedOnce() {
        Offer offer = new Offer();
        offer.registerOffer("Honda", "Accord", 2018, 15000, "yes", 2);
        offer.setpOffer("customer-one", 0);
        assertEquals(15000, offer.setAccept(0, 12, true));
        assertTrue(offer.getAccept());
        assertEquals(1, offer.getListings().get(0).getStockQuantity());
        assertEquals(PurchaseRequestStatus.ACCEPTED, offer.getPurchaseRequests().get(0).getStatus());
        assertEquals(1250, offer.getPurchaseRequests().get(0).getMonthlyPayment());
        assertThrows(IllegalStateException.class, () -> offer.setAccept(0, 12, true));
        assertEquals(1, offer.getListings().get(0).getStockQuantity());
    }

    @Test
    void finalUnitDeactivatesListing() {
        Offer offer = new Offer();
        offer.registerOffer("Toyota", "Corolla", 2017, 12000, "yes", 1);
        offer.setpOffer("customer", 0);
        offer.setAccept(0, 12, true);
        InventoryListing listing = offer.getListings().get(0);
        assertEquals(0, listing.getStockQuantity());
        assertFalse(listing.isAvailable());
        assertThrows(IllegalStateException.class, () -> offer.setpOffer("another", 0));
    }

    @Test
    void rejectAllTransitionsEveryPendingRequest() {
        Offer offer = new Offer();
        offer.registerOffer("Honda", "Accord", 2018, 15000, "yes", 2);
        offer.registerOffer("Toyota", "Corolla", 2019, 16000, "yes", 2);
        offer.setpOffer("first", 0);
        offer.setpOffer("second", 1);
        offer.rejectAllOffers();
        assertEquals(PurchaseRequestStatus.REJECTED, offer.getPurchaseRequests().get(0).getStatus());
        assertEquals(PurchaseRequestStatus.REJECTED, offer.getPurchaseRequests().get(1).getStatus());
        assertEquals(2, offer.getListings().get(0).getStockQuantity());
        assertEquals(2, offer.getListings().get(1).getStockQuantity());
    }

    @Test
    void emptyPendingReportIsSafe() {
        Offer offer = new Offer();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        System.setOut(new PrintStream(output));
        try {
            offer.getpOffers();
        } finally {
            System.setOut(original);
        }
        assertTrue(output.toString().contains("no pending purchase requests"));
    }

    @Test
    void removedListingKeepsStableIndex() {
        Offer offer = new Offer();
        offer.registerOffer("Toyota", "Corolla", 2017, 12000, "yes", 1);
        offer.registerOffer("Honda", "Civic", 2018, 13000, "yes", 1);
        offer.removeOffer(0);
        assertEquals(2, offer.getListingCount());
        assertFalse(offer.getListings().get(0).isActive());
        assertEquals(1, offer.getListings().get(1).getId());
        assertThrows(IllegalStateException.class, () -> offer.setpOffer("customer", 0));
    }

    @Test
    void invalidIndexesAreRejected() {
        Offer offer = new Offer();
        assertThrows(IllegalArgumentException.class, () -> offer.setpOffer("customer", 0));
        assertThrows(IllegalArgumentException.class, () -> offer.removeOffer(-1));
        assertThrows(IllegalArgumentException.class, () -> offer.setAccept(0, 12, true));
    }

    @Test
    void collectionViewsAreImmutable() {
        Offer offer = new Offer();
        offer.registerOffer("Honda", "Accord", 2018, 15000, "yes", 1);
        offer.setpOffer("customer", 0);
        assertThrows(UnsupportedOperationException.class, () -> offer.getListings().clear());
        assertThrows(UnsupportedOperationException.class, () -> offer.getPurchaseRequests().clear());
    }

    @Test
    void rejectedRequestCannotBeAcceptedLater() {
        Offer offer = new Offer();
        offer.registerOffer("Honda", "Accord", 2018, 15000, "yes", 1);
        offer.setpOffer("customer", 0);
        offer.setAccept(0, 12, false);
        assertEquals(PurchaseRequestStatus.REJECTED, offer.getPurchaseRequests().get(0).getStatus());
        assertThrows(IllegalStateException.class, () -> offer.setAccept(0, 12, true));
        assertEquals(1, offer.getListings().get(0).getStockQuantity());
    }
}
