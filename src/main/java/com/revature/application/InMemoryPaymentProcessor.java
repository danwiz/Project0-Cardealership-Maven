package com.revature.application;

import java.util.List;
import java.util.Objects;

import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;
import com.revature.repository.OwnershipRepository;
import com.revature.repository.PaymentTransactionRepository;

/** Applies an in-memory payment after validating both ownership and ledger state. */
public final class InMemoryPaymentProcessor implements PaymentProcessor {
    private final OwnershipRepository ownership;
    private final PaymentTransactionRepository transactions;

    public InMemoryPaymentProcessor(OwnershipRepository ownership,
            PaymentTransactionRepository transactions) {
        this.ownership = Objects.requireNonNull(ownership, "ownership");
        this.transactions = Objects.requireNonNull(transactions, "transactions");
    }

    @Override
    public PaymentTransaction record(String customerName, long ownershipId, int amount) {
        String customer = requireText(customerName, "customerName");
        if (ownershipId <= 0) throw new IllegalArgumentException("ownership id must be positive");
        List<OwnedVehicle> vehicles = ownership.findAll();
        OwnedVehicle selected = ownership.findById(ownershipId)
                .orElseThrow(() -> new IllegalArgumentException("unknown ownership id: " + ownershipId));
        PaymentPlan plan = selected.getPaymentPlan();
        if (amount <= 0) throw new IllegalArgumentException("payment amount must be positive");
        if (amount > plan.getRemainingBalance()) {
            throw new IllegalArgumentException("payment amount exceeds remaining balance");
        }
        int aggregateOriginal = 0;
        for (OwnedVehicle vehicle : vehicles) aggregateOriginal += vehicle.getPaymentPlan().getPurchasePrice();

        Payments ledger = transactions.ledger();
        ledger.setAmtOwed(aggregateOriginal);
        ledger.makePayment(customer, Long.valueOf(ownershipId), selected.getContractId().orElse(null), amount);
        plan.recordPayment(amount);
        List<PaymentTransaction> recorded = ledger.getTransactions();
        return recorded.get(recorded.size() - 1);
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
}
