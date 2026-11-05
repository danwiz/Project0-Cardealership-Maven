package com.revature.application;

import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.User;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/** Records a customer payment through the configured atomic processor. */
public final class RecordPaymentCommand {
    private final ConfiguredDealershipApplication application;
    private final RoleAuthorizationService authorization;

    public RecordPaymentCommand(ConfiguredDealershipApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        this.authorization = application.getContext().getAuthorizationService();
    }

    public PaymentTransaction execute(User actor, String customerName,
            long ownershipId, int amount) {
        authorization.requireAuthorized(actor, Permission.RECORD_PAYMENTS);
        String customer = requireText(customerName, "customerName");
        if (actor.getRole() == AccountRole.CUSTOMER
                && !actor.getUsername().equals(customer)) {
            throw new SecurityException("customers may only record their own payments");
        }
        return application.paymentProcessorFor(customer)
                .record(customer, ownershipId, amount);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
