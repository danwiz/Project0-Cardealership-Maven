package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Application use case for adding and removing inventory listings. */
public final class ManageInventoryCommand {
    private final ApplicationPorts.Authorization authorization;
    private final ApplicationPorts.Inventory inventory;

    public ManageInventoryCommand(DealershipApplicationContext context) {
        this(new ContextDomainAdapter(context), new ContextDomainAdapter(context));
    }

    public ManageInventoryCommand(ApplicationPorts.Authorization authorization,
            ApplicationPorts.Inventory inventory) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public void add(User actor, String make, String model, int year, int price, int stockQuantity) {
        authorization.requireAuthorized(actor, Permission.MANAGE_INVENTORY);
        inventory.addListing(make, model, year, price, stockQuantity);
    }

    public void remove(User actor, int listingId) {
        authorization.requireAuthorized(actor, Permission.MANAGE_INVENTORY);
        inventory.removeListing(listingId);
    }
}