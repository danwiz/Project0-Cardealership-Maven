package com.revature.cardealer;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Serializable, versioned representation of the dealership's durable state.
 * Runtime services and repositories are intentionally excluded.
 */
public final class ApplicationStateSnapshot implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final int CURRENT_VERSION = 1;

    private final int version;
    private final Instant createdAt;
    private final List<User> accounts;
    private final List<InventoryListing> inventoryListings;
    private final List<PurchaseRequest> purchaseRequests;
    private final List<OwnedVehicle> ownedVehicles;
    private final List<PaymentTransaction> paymentTransactions;

    public ApplicationStateSnapshot(List<User> accounts,
            List<InventoryListing> inventoryListings,
            List<PurchaseRequest> purchaseRequests,
            List<OwnedVehicle> ownedVehicles,
            List<PaymentTransaction> paymentTransactions) {
        this(CURRENT_VERSION, Instant.now(), accounts, inventoryListings,
                purchaseRequests, ownedVehicles, paymentTransactions);
    }

    public ApplicationStateSnapshot(int version, Instant createdAt,
            List<User> accounts, List<InventoryListing> inventoryListings,
            List<PurchaseRequest> purchaseRequests, List<OwnedVehicle> ownedVehicles,
            List<PaymentTransaction> paymentTransactions) {
        if (version <= 0) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.accounts = immutableCopy(accounts, "accounts");
        this.inventoryListings = immutableCopy(inventoryListings, "inventoryListings");
        this.purchaseRequests = immutableCopy(purchaseRequests, "purchaseRequests");
        this.ownedVehicles = immutableCopy(ownedVehicles, "ownedVehicles");
        this.paymentTransactions = immutableCopy(paymentTransactions, "paymentTransactions");
    }

    public int getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public List<User> getAccounts() {
        return accounts;
    }

    public List<InventoryListing> getInventoryListings() {
        return inventoryListings;
    }

    public List<PurchaseRequest> getPurchaseRequests() {
        return purchaseRequests;
    }

    public List<OwnedVehicle> getOwnedVehicles() {
        return ownedVehicles;
    }

    public List<PaymentTransaction> getPaymentTransactions() {
        return paymentTransactions;
    }

    private static <T> List<T> immutableCopy(List<T> source, String fieldName) {
        Objects.requireNonNull(source, fieldName);
        return Collections.unmodifiableList(new ArrayList<>(source));
    }
}
