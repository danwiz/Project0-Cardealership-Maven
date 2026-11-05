package com.revature.application;

import java.util.List;
import java.util.Objects;

import com.revature.cardealer.Car;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.repository.InventoryRepository;
import com.revature.repository.OwnershipRepository;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/** Repository-backed command adapter for either configured infrastructure mode. */
public final class ConfiguredDomainAdapter implements ApplicationPorts.Authorization,
        ApplicationPorts.Inventory, ApplicationPorts.Ownership {
    private final ConfiguredDealershipApplication application;
    private final RoleAuthorizationService authorization;
    private final InventoryRepository inventory;

    public ConfiguredDomainAdapter(ConfiguredDealershipApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        this.authorization = application.getContext().getAuthorizationService();
        this.inventory = application.getInventory();
    }

    @Override public boolean isAuthorized(User account, Permission permission) { return authorization.isAuthorized(account, permission); }
    @Override public void requireAuthorized(User account, Permission permission) { authorization.requireAuthorized(account, permission); }
    @Override public int listingCount() { return inventory.listingCount(); }
    @Override public void addListing(String make, String model, int year, int price, int stockQuantity) { inventory.addListing(make, model, year, price, stockQuantity); }
    @Override public void removeListing(int listingId) { inventory.removeListing(listingId); }
    @Override public void requestPurchase(String customerName, int listingId) { inventory.requestPurchase(customerName, listingId); }
    @Override public List<PurchaseRequest> purchaseRequests() { return inventory.purchaseRequests(); }
    @Override public int decideRequest(int requestId, int paymentMonths, boolean accepted) { return inventory.decideRequest(requestId, paymentMonths, accepted); }
    @Override public void rejectAllPendingRequests() { inventory.rejectAllPendingRequests(); }
    @Override public Car carForListing(int listingId) { return inventory.carForListing(listingId); }

    @Override
    public void addOwnedVehicle(Car car, int purchasePrice, int paymentMonths) {
        throw new IllegalStateException("customer identity is required for configured ownership");
    }

    @Override
    public void addOwnedVehicle(String customerName, Car car, int purchasePrice, int paymentMonths) {
        application.ownershipFor(customerName).add(car, purchasePrice, paymentMonths);
    }

    @Override
    public void addOwnedVehicle(String customerName, String contractId, Car car,
            int purchasePrice, int paymentMonths) {
        OwnershipRepository ownership = application.ownershipFor(customerName);
        ownership.add(contractId, car, purchasePrice, paymentMonths);
    }
}
