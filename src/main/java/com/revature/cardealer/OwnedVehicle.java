package com.revature.cardealer;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

/** One customer-owned vehicle and its associated payment plan. */
public class OwnedVehicle implements Serializable {

    private static final long serialVersionUID = 1L;

    private final long ownershipId;
    private final String contractId;
    private final Car vehicle;
    private final PaymentPlan paymentPlan;

    /** Compatibility constructor for historical snapshots without explicit identifiers. */
    public OwnedVehicle(Car vehicle, PaymentPlan paymentPlan) {
        this(0, null, vehicle, paymentPlan);
    }

    public OwnedVehicle(long ownershipId, Car vehicle, PaymentPlan paymentPlan) {
        this(ownershipId, null, vehicle, paymentPlan);
    }

    public OwnedVehicle(long ownershipId, String contractId, Car vehicle, PaymentPlan paymentPlan) {
        if (ownershipId < 0) throw new IllegalArgumentException("ownershipId must not be negative");
        this.ownershipId = ownershipId;
        this.contractId = normalizeContractId(contractId);
        this.vehicle = Objects.requireNonNull(vehicle, "vehicle");
        this.paymentPlan = Objects.requireNonNull(paymentPlan, "paymentPlan");
    }

    public long getOwnershipId() { return ownershipId; }
    public boolean hasOwnershipId() { return ownershipId > 0; }
    public Optional<String> getContractId() { return Optional.ofNullable(contractId); }
    public Car getVehicle() { return vehicle; }
    public PaymentPlan getPaymentPlan() { return paymentPlan; }

    private static String normalizeContractId(String value) {
        if (value == null) return null;
        String normalized = value.trim();
        if (!normalized.matches("ACQ-\\d{6}")) throw new IllegalArgumentException("invalid acquisition contract identifier");
        return normalized;
    }
}
