package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.DealershipApplicationContext;

/** Idempotently seeds the default dealership inventory. */
public final class SeedDefaultInventoryCommand {
    private final ApplicationPorts.Inventory inventory;

    public SeedDefaultInventoryCommand(DealershipApplicationContext context) {
        this(new ContextDomainAdapter(context));
    }

    public SeedDefaultInventoryCommand(ApplicationPorts.Inventory inventory) {
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    public boolean execute() {
        if (inventory.listingCount() > 0) return false;
        inventory.addListing("Honda ", "Accord ", 2017, 15000, 7);
        inventory.addListing("Chevy ", "Malibu ", 2020, 17456, 4);
        inventory.addListing("BMW   ", "4Series", 2016, 10456, 6);
        inventory.addListing("Toyota", "Corolla", 2014, 13456, 3);
        return true;
    }
}