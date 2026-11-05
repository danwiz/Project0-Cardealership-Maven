package com.revature.cardealer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Compatibility facade for inventory listings and purchase requests.
 */
public class Offer {

    private final List<InventoryListing> listings = new ArrayList<>();
    private final List<PurchaseRequest> purchaseRequests = new ArrayList<>();

    private String oAvail;
    private int oAmt;
    private int oMpay;
    private int oPrice;
    private boolean oAccept;
    private boolean oStatus;
    private boolean oPending;
    private String oDetails;
    private Car cDetails;
    private String oCName;

    public static Offer restore(List<InventoryListing> restoredListings,
            List<PurchaseRequest> restoredRequests) {
        Offer offer = new Offer();
        for (InventoryListing listing : Objects.requireNonNull(restoredListings, "restoredListings")) {
            offer.listings.add(snapshotListing(Objects.requireNonNull(listing, "listing")));
        }
        for (PurchaseRequest request : Objects.requireNonNull(restoredRequests, "restoredRequests")) {
            offer.purchaseRequests.add(Objects.requireNonNull(request, "request").snapshot());
        }
        offer.refreshPendingFlag();
        return offer;
    }

    public synchronized void setpOffer(String customerName, int listingNumber) {
        InventoryListing listing = requireListing(listingNumber);
        if (!listing.isAvailable()) {
            throw new IllegalStateException("listing is not available");
        }
        PurchaseRequest request = new PurchaseRequest(purchaseRequests.size(), listingNumber, customerName);
        purchaseRequests.add(request);
        oPending = true;
        System.out.println("[" + request.getId() + "] " + listing.describe()
                + "     Pending Offer for: " + request.getCustomerName());
    }

    public synchronized void getpOffers() {
        boolean found = false;
        for (PurchaseRequest request : purchaseRequests) {
            if (request.isPending()) {
                InventoryListing listing = requireListing(request.getListingId());
                System.out.println("[" + request.getId() + "] " + listing.describe()
                        + "   |---> Pending Offer: " + request.getCustomerName());
                found = true;
            }
        }
        if (!found) {
            System.out.println("\nWe have no pending purchase requests at this time!");
        }
    }

    public synchronized void registerOffer(String make, String model, int year, int price, String avail, int amount) {
        Car car = new Car(make, model, year);
        boolean active = parseAvailability(avail);
        InventoryListing listing = new InventoryListing(listings.size(), car, price, amount, active);
        listings.add(listing);
        cDetails = car;
        oPrice = price;
        oAvail = active && amount > 0 ? "yes" : "no";
        oAmt = amount;
        oStatus = listing.isAvailable();
        oAccept = false;
        oPending = false;
        System.out.println(listing.describe());
    }

    public void setOffer(Car details, int price, String avail, int amount) {
        cDetails = snapshotCar(Objects.requireNonNull(details, "details"));
        oPrice = price;
        oAvail = parseAvailability(avail) && amount > 0 ? "yes" : "no";
        oAmt = amount;
        oStatus = "yes".equals(oAvail);
    }

    public void setOffer(String make, String model, int year, int price, String avail, int amount) {
        setOffer(new Car(make, model, year), price, avail, amount);
        oAccept = false;
        oPending = false;
    }

    public synchronized void getOfferAll() {
        boolean found = false;
        for (InventoryListing listing : listings) {
            if (listing.isActive()) {
                System.out.println(listing.describe());
                found = true;
            }
        }
        if (!found) {
            System.out.println("\nWe have no cars at this time!");
        }
    }

    public String getOffer() {
        if (cDetails == null) {
            return "";
        }
        String offer = "Car:-  " + cDetails.getCarMake() + "   Model: "
                + cDetails.getCarModel() + "   Year: " + cDetails.getCarYear()
                + "   Price: " + oPrice + "   Avail: " + oAvail
                + "   Stock Qty: " + oAmt;
        System.out.println(offer);
        return offer;
    }

    @Override
    public String toString() { return getOffer(); }
    public void setPrice(int price) { if (price < 0) throw new IllegalArgumentException("price must not be negative"); oPrice = price; }
    public int getPrice() { return oPrice; }
    public void setMpay(int monthlyPayment) { if (monthlyPayment < 0) throw new IllegalArgumentException("monthly payment must not be negative"); oMpay = monthlyPayment; }
    public int getMpay() { return oMpay; }
    public String getAvail() { return oAvail; }
    public void setAvail(String avail) { oAvail = parseAvailability(avail) ? "yes" : "no"; oStatus = "yes".equals(oAvail) && oAmt > 0; }
    public void setAmt(int amount) { if (amount < 0) throw new IllegalArgumentException("stock quantity must not be negative"); oAmt = amount; oStatus = amount > 0 && "yes".equals(oAvail); }
    public int getAmt() { return oAmt; }
    public boolean isAvail() { return oStatus && oAmt > 0; }
    public void setStatus(boolean status) { oStatus = status; }
    public boolean getStatus() { return oStatus; }

    public synchronized void getAccept(Offer[] ignored) {
        for (PurchaseRequest request : purchaseRequests) {
            if (request.getStatus() == PurchaseRequestStatus.ACCEPTED) {
                InventoryListing listing = requireListing(request.getListingId());
                System.out.println(listing.describe() + "   Offer Accepted: " + request.getCustomerName());
            }
        }
    }

    public void setAccept() { oAccept = true; }

    public synchronized int setAccept(int requestNumber, int months, boolean accept) {
        PurchaseRequest request = requireRequest(requestNumber);
        InventoryListing listing = requireListing(request.getListingId());
        if (!accept) {
            request.reject();
            refreshPendingFlag();
            oAccept = false;
            return listing.getPrice();
        }
        if (!listing.isAvailable()) throw new IllegalStateException("listing is not available");
        request.accept(listing.getPrice(), months);
        listing.decrementStock();
        oAccept = true;
        oMpay = request.getMonthlyPayment();
        oPrice = listing.getPrice();
        oAmt = listing.getStockQuantity();
        oStatus = listing.isAvailable();
        oAvail = listing.isAvailable() ? "yes" : "no";
        oCName = request.getCustomerName();
        oDetails = listing.describe() + "   Offer Accepted: " + oCName + "   Monthly Payment: " + oMpay;
        refreshPendingFlag();
        System.out.println(oDetails);
        return listing.getPrice();
    }

    public synchronized void rejectAllOffers() {
        for (PurchaseRequest request : purchaseRequests) if (request.isPending()) request.reject();
        refreshPendingFlag();
        System.out.println("\nMessage: All Pending Offers REJECTED!! ");
    }

    public synchronized void removeOffer(int listingNumber) {
        InventoryListing listing = requireListing(listingNumber);
        listing.remove();
        System.out.println(listing.describe() + "\nMessage: Car Listing Removed!! ");
    }

    public boolean getAccept() { return oAccept; }
    public synchronized Car getCarDB(int listingNumber) { return snapshotCar(requireListing(listingNumber).getCar()); }
    public synchronized int getListingCount() { return listings.size(); }
    public synchronized List<InventoryListing> getListings() {
        List<InventoryListing> result = new ArrayList<>();
        for (InventoryListing listing : listings) result.add(snapshotListing(listing));
        return Collections.unmodifiableList(result);
    }
    public synchronized List<PurchaseRequest> getPurchaseRequests() {
        List<PurchaseRequest> result = new ArrayList<>();
        for (PurchaseRequest request : purchaseRequests) result.add(request.snapshot());
        return Collections.unmodifiableList(result);
    }

    private static Car snapshotCar(Car car) {
        return new Car(car.getCarMake(), car.getCarModel(), car.getCarYear());
    }

    private static InventoryListing snapshotListing(InventoryListing listing) {
        return new InventoryListing(listing.getId(), snapshotCar(listing.getCar()),
                listing.getPrice(), listing.getStockQuantity(), listing.isActive());
    }

    private InventoryListing requireListing(int listingNumber) {
        if (listingNumber < 0 || listingNumber >= listings.size()) throw new IllegalArgumentException("unknown listing number: " + listingNumber);
        return listings.get(listingNumber);
    }

    private PurchaseRequest requireRequest(int requestNumber) {
        if (requestNumber < 0 || requestNumber >= purchaseRequests.size()) throw new IllegalArgumentException("unknown purchase request number: " + requestNumber);
        return purchaseRequests.get(requestNumber);
    }

    private void refreshPendingFlag() { oPending = purchaseRequests.stream().anyMatch(PurchaseRequest::isPending); }

    private static boolean parseAvailability(String availability) {
        return availability != null && ("yes".equalsIgnoreCase(availability.trim())
                || "y".equalsIgnoreCase(availability.trim())
                || "true".equalsIgnoreCase(availability.trim()));
    }
}
