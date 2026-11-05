package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.DealershipApplicationContext;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.service.Permission;

/** Application use case for approving or rejecting purchase requests. */
public final class ReviewPurchaseRequestCommand {
    private final ApplicationPorts.Authorization authorization;
    private final ApplicationPorts.Inventory inventory;
    private final ApplicationPorts.Ownership ownership;
    private final ApplicationPorts.PurchaseApproval atomicApproval;

    public ReviewPurchaseRequestCommand(DealershipApplicationContext context) {
        this(new ContextDomainAdapter(context), new ContextDomainAdapter(context),
                new ContextDomainAdapter(context));
    }

    public ReviewPurchaseRequestCommand(ApplicationPorts.Authorization authorization,
            ApplicationPorts.Inventory inventory, ApplicationPorts.Ownership ownership) {
        this(authorization, inventory, ownership, null);
    }

    public ReviewPurchaseRequestCommand(ApplicationPorts.Authorization authorization,
            ApplicationPorts.Inventory inventory, ApplicationPorts.Ownership ownership,
            ApplicationPorts.PurchaseApproval atomicApproval) {
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        this.inventory = Objects.requireNonNull(inventory, "inventory");
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.atomicApproval = atomicApproval;
    }

    public int approve(User actor, int requestId, int paymentMonths, boolean rejectOtherPending) {
        authorization.requireAuthorized(actor, Permission.REVIEW_PURCHASE_REQUESTS);
        if (paymentMonths <= 0) throw new IllegalArgumentException("payment months must be positive");
        if (atomicApproval != null) {
            return atomicApproval.approve(actor, requestId, paymentMonths, rejectOtherPending);
        }
        if (requestId < 0 || requestId >= inventory.purchaseRequests().size()) {
            throw new IllegalArgumentException("unknown purchase request number: " + requestId);
        }
        PurchaseRequest request = inventory.purchaseRequests().get(requestId);
        String contractId = PurchaseRequest.contractIdFor(request.getId());
        int price = inventory.decideRequest(requestId, paymentMonths, true);
        ownership.addOwnedVehicle(request.getCustomerName(), contractId,
                inventory.carForListing(request.getListingId()), price, paymentMonths);
        if (rejectOtherPending) inventory.rejectAllPendingRequests();
        return price;
    }

    public void reject(User actor, int requestId) {
        authorization.requireAuthorized(actor, Permission.REVIEW_PURCHASE_REQUESTS);
        inventory.decideRequest(requestId, 1, false);
    }
}
