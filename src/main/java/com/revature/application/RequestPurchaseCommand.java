package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Application use case for submitting a customer purchase request. */
public final class RequestPurchaseCommand {
    private final ApplicationPorts.Authorization authorization;
    private final ApplicationPorts.Inventory inventory;

    public RequestPurchaseCommand(DealershipApplicationContext context) {
        this(new ContextDomainAdapter(context), new ContextDomainAdapter(context));
    }

    public RequestPurchaseCommand(ApplicationPorts.Authorization authorization,
            ApplicationPorts.Inventory inventory) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public void execute(User actor, int listingId) {
        authorization.requireAuthorized(actor, Permission.REQUEST_PURCHASE);
        if (listingId < 0 || listingId >= inventory.listingCount()) {
            throw new IllegalArgumentException("unknown listing number: " + listingId);
        }
        inventory.requestPurchase(actor.getUsername(), listingId);
    }
}