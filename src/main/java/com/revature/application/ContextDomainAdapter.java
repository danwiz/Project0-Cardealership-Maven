package com.revature.application;

import java.util.List;
import java.util.Objects;

import com.revature.cardealer.Car;
import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.repository.CustomerOwnershipRepository;
import com.revature.repository.InventoryRepository;
import com.revature.repository.OfferInventoryRepository;
import com.revature.repository.OwnershipRepository;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/** Binds application domain ports to repository-oriented adapters. */
public final class ContextDomainAdapter implements ApplicationPorts.Authorization,
        ApplicationPorts.Inventory, ApplicationPorts.Ownership {
    private final RoleAuthorizationService authorization;
    private final InventoryRepository inventory;
    private final OwnershipRepository ownership;

    public ContextDomainAdapter(DealershipApplicationContext context) {
        this(context.getAuthorizationService(),
                new OfferInventoryRepository(context.getInventory()),
                new CustomerOwnershipRepository(context.getCustomerLoginService()));
    }

    public ContextDomainAdapter(RoleAuthorizationService authorization,
            InventoryRepository inventory, OwnershipRepository ownership) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
    }

    @Override public boolean isAuthorized(User account, Permission permission) {
        return authorization.isAuthorized(account, permission);
    }
    @Override public void requireAuthorized(User account, Permission permission) {
        authorization.requireAuthorized(account, permission);
    }
    @Override public int listingCount() { return inventory.listingCount(); }
    @Override public void addListing(String make, String model, int year, int price, int stockQuantity) {
        inventory.addListing(make, model, year, price, stockQuantity);
    }
    @Override public void removeListing(int listingId) { inventory.removeListing(listingId); }
    @Override public void requestPurchase(String customerName, int listingId) {
        inventory.requestPurchase(customerName, listingId);
    }
    @Override public List<PurchaseRequest> purchaseRequests() { return inventory.purchaseRequests(); }
    @Override public int decideRequest(int requestId, int paymentMonths, boolean accepted) {
        return inventory.decideRequest(requestId, paymentMonths, accepted);
    }
    @Override public void rejectAllPendingRequests() { inventory.rejectAllPendingRequests(); }
    @Override public Car carForListing(int listingId) { return inventory.carForListing(listingId); }
    @Override public void addOwnedVehicle(Car car, int purchasePrice, int paymentMonths) {
        ownership.add(car, purchasePrice, paymentMonths);
    }
}
