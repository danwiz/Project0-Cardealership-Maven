package com.revature.repository;

import java.util.List;
import java.util.Objects;

import com.revature.cardealer.Car;
import com.revature.cardealer.InventoryListing;
import com.revature.cardealer.Offer;
import com.revature.cardealer.PurchaseRequest;

public final class OfferInventoryRepository implements InventoryRepository {
    private final Offer inventory;

    public OfferInventoryRepository(Offer inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    @Override public int listingCount() { return inventory.getListingCount(); }
    @Override public List<InventoryListing> listings() { return inventory.getListings(); }
    @Override public List<PurchaseRequest> purchaseRequests() { return inventory.getPurchaseRequests(); }
    @Override public void addListing(String make, String model, int year, int price, int stockQuantity) {
        inventory.registerOffer(make, model, year, price, "yes", stockQuantity);
    }
    @Override public void removeListing(int listingId) { inventory.removeOffer(listingId); }
    @Override public void requestPurchase(String customerName, int listingId) { inventory.setpOffer(customerName, listingId); }
    @Override public int decideRequest(int requestId, int paymentMonths, boolean accepted) {
        return inventory.setAccept(requestId, paymentMonths, accepted);
    }
    @Override public void rejectAllPendingRequests() { inventory.rejectAllOffers(); }
    @Override public Car carForListing(int listingId) { return inventory.getCarDB(listingId); }
}
