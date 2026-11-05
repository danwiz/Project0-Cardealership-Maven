package com.revature.cardealer;

import java.io.Serializable;
import java.util.Objects;
import java.util.Optional;

public class PurchaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int id;
    private final int listingId;
    private final String customerName;
    private PurchaseRequestStatus status = PurchaseRequestStatus.PENDING;
    private int paymentMonths;
    private int monthlyPayment;
    private String contractId;

    public PurchaseRequest(int id, int listingId, String customerName) {
        if (id < 0 || listingId < 0) throw new IllegalArgumentException("request and listing ids must not be negative");
        this.customerName = Objects.requireNonNull(customerName, "customerName").trim();
        if (this.customerName.isEmpty()) throw new IllegalArgumentException("customer name must not be blank");
        this.id = id;
        this.listingId = listingId;
    }

    public int getId() { return id; }
    public int getListingId() { return listingId; }
    public String getCustomerName() { return customerName; }
    public PurchaseRequestStatus getStatus() { return status; }
    public int getPaymentMonths() { return paymentMonths; }
    public int getMonthlyPayment() { return monthlyPayment; }
    public Optional<String> getContractId() { return Optional.ofNullable(contractId); }
    public boolean isPending() { return status == PurchaseRequestStatus.PENDING; }

    /** Independent read snapshot. Never return an authoritative mutable request. */
    PurchaseRequest snapshot() {
        PurchaseRequest copy = new PurchaseRequest(id, listingId, customerName);
        copy.status = status;
        copy.paymentMonths = paymentMonths;
        copy.monthlyPayment = monthlyPayment;
        copy.contractId = contractId;
        return copy;
    }

    public void accept(int price, int months) {
        accept(price, months, contractIdFor(id));
    }

    public void accept(int price, int months, String contractId) {
        requirePending();
        if (months <= 0) throw new IllegalArgumentException("payment months must be positive");
        this.contractId = requireContractId(contractId);
        status = PurchaseRequestStatus.ACCEPTED;
        paymentMonths = months;
        monthlyPayment = price / months;
    }

    public void reject() {
        requirePending();
        status = PurchaseRequestStatus.REJECTED;
    }

    public static String contractIdFor(int requestId) {
        if (requestId < 0) throw new IllegalArgumentException("request id must not be negative");
        return String.format("ACQ-%06d", requestId);
    }

    private static String requireContractId(String value) {
        String normalized = Objects.requireNonNull(value, "contractId").trim();
        if (!normalized.matches("ACQ-\\d{6}")) throw new IllegalArgumentException("invalid acquisition contract identifier");
        return normalized;
    }

    private void requirePending() {
        if (!isPending()) throw new IllegalStateException("purchase request has already been decided");
    }
}
