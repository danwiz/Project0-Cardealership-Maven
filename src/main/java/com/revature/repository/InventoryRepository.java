package com.revature.repository;

import java.util.List;

import com.revature.cardealer.Car;
import com.revature.cardealer.InventoryListing;
import com.revature.cardealer.PurchaseRequest;

public interface InventoryRepository {
    int listingCount();
    List<InventoryListing> listings();
    List<PurchaseRequest> purchaseRequests();
    void addListing(String make, String model, int year, int price, int stockQuantity);
    void removeListing(int listingId);
    void requestPurchase(String customerName, int listingId);
    int decideRequest(int requestId, int paymentMonths, boolean accepted);
    void rejectAllPendingRequests();
    Car carForListing(int listingId);
}
