package com.revature.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.cardealer.User;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/** Reconstructs one acquisition contract across request, ownership, plan, and payment history. */
public final class AcquisitionLifecycleQueryService {
    private final ConfiguredDealershipApplication application;
    private final RoleAuthorizationService authorization;

    public AcquisitionLifecycleQueryService(ConfiguredDealershipApplication application) {
        this.application = Objects.requireNonNull(application, "application");
        this.authorization = application.getContext().getAuthorizationService();
    }

    public AcquisitionLifecycleView find(User actor, String contractId) {
        String contract = requireContractId(contractId);
        // Reject unauthenticated callers before probing whether a contract exists.
        if (actor == null || (!authorization.isAuthorized(actor, Permission.VIEW_OWN_PAYMENTS)
                && !authorization.isAuthorized(actor, Permission.VIEW_CUSTOMER_PAYMENTS))) {
            throw new SecurityException("authenticated account is required for acquisition contracts");
        }
        PurchaseRequest request = application.getInventory().purchaseRequests().stream()
                .filter(candidate -> candidate.getContractId().map(contract::equals).orElse(false))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown acquisition contract: " + contract));
        authorize(actor, request.getCustomerName());

        OwnedVehicle ownership = application.ownershipFor(request.getCustomerName()).findAll().stream()
                .filter(candidate -> candidate.getContractId().map(contract::equals).orElse(false))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("contract has no ownership record: " + contract));

        List<PaymentEntryView> payments = new ArrayList<>();
        for (PaymentTransaction transaction : application.paymentsFor(request.getCustomerName()).findAll()) {
            if (transaction.getContractId().map(contract::equals).orElse(false)) {
                payments.add(new PaymentEntryView(transaction.getTransactionId(), transaction.getAmount(),
                        transaction.getTotalPaid(), transaction.getRemainingBalance(), transaction.getRecordedAt()));
            }
        }

        Car car = ownership.getVehicle();
        PaymentPlan plan = ownership.getPaymentPlan();
        return new AcquisitionLifecycleView(contract, request.getId(), request.getListingId(),
                request.getCustomerName(), request.getStatus(), request.getPaymentMonths(),
                request.getMonthlyPayment(), ownership.getOwnershipId(), car.getCarMake(),
                car.getCarModel(), car.getCarYear(), plan.getPurchasePrice(), plan.getAmountPaid(),
                plan.getRemainingBalance(), plan.getMonthlyPayment(), plan.getTermMonths(),
                Collections.unmodifiableList(payments));
    }

    private void authorize(User actor, String customerName) {
        if (actor == null) throw new SecurityException("authenticated account is required");
        if (actor.getRole() == AccountRole.CUSTOMER) {
            authorization.requireAuthorized(actor, Permission.VIEW_OWN_PAYMENTS);
            if (!actor.getUsername().equals(customerName)) {
                throw new SecurityException("customers may only view their own acquisition contracts");
            }
            return;
        }
        authorization.requireAuthorized(actor, Permission.VIEW_CUSTOMER_PAYMENTS);
    }

    private static String requireContractId(String value) {
        String contract = Objects.requireNonNull(value, "contractId").trim();
        if (!contract.matches("ACQ-\\d{6}")) {
            throw new IllegalArgumentException("invalid acquisition contract identifier: " + contract);
        }
        return contract;
    }

    public static final class AcquisitionLifecycleView {
        private final String contractId;
        private final int requestId;
        private final int listingId;
        private final String customerName;
        private final PurchaseRequestStatus requestStatus;
        private final int approvedPaymentMonths;
        private final int approvedMonthlyPayment;
        private final long ownershipId;
        private final String make;
        private final String model;
        private final int year;
        private final int purchasePrice;
        private final int amountPaid;
        private final int remainingBalance;
        private final int monthlyPayment;
        private final int termMonths;
        private final List<PaymentEntryView> payments;

        AcquisitionLifecycleView(String contractId, int requestId, int listingId, String customerName,
                PurchaseRequestStatus requestStatus, int approvedPaymentMonths, int approvedMonthlyPayment,
                long ownershipId, String make, String model, int year, int purchasePrice, int amountPaid,
                int remainingBalance, int monthlyPayment, int termMonths, List<PaymentEntryView> payments) {
            this.contractId = contractId;
            this.requestId = requestId;
            this.listingId = listingId;
            this.customerName = customerName;
            this.requestStatus = requestStatus;
            this.approvedPaymentMonths = approvedPaymentMonths;
            this.approvedMonthlyPayment = approvedMonthlyPayment;
            this.ownershipId = ownershipId;
            this.make = make;
            this.model = model;
            this.year = year;
            this.purchasePrice = purchasePrice;
            this.amountPaid = amountPaid;
            this.remainingBalance = remainingBalance;
            this.monthlyPayment = monthlyPayment;
            this.termMonths = termMonths;
            this.payments = payments;
        }

        public String getContractId() { return contractId; }
        public int getRequestId() { return requestId; }
        public int getListingId() { return listingId; }
        public String getCustomerName() { return customerName; }
        public PurchaseRequestStatus getRequestStatus() { return requestStatus; }
        public int getApprovedPaymentMonths() { return approvedPaymentMonths; }
        public int getApprovedMonthlyPayment() { return approvedMonthlyPayment; }
        public long getOwnershipId() { return ownershipId; }
        public String getMake() { return make; }
        public String getModel() { return model; }
        public int getYear() { return year; }
        public int getPurchasePrice() { return purchasePrice; }
        public int getAmountPaid() { return amountPaid; }
        public int getRemainingBalance() { return remainingBalance; }
        public int getMonthlyPayment() { return monthlyPayment; }
        public int getTermMonths() { return termMonths; }
        public List<PaymentEntryView> getPayments() { return payments; }
    }

    public static final class PaymentEntryView {
        private final String transactionId;
        private final int amount;
        private final int cumulativePaid;
        private final int remainingBalance;
        private final Instant recordedAt;

        PaymentEntryView(String transactionId, int amount, int cumulativePaid,
                int remainingBalance, Instant recordedAt) {
            this.transactionId = transactionId;
            this.amount = amount;
            this.cumulativePaid = cumulativePaid;
            this.remainingBalance = remainingBalance;
            this.recordedAt = recordedAt;
        }

        public String getTransactionId() { return transactionId; }
        public int getAmount() { return amount; }
        public int getCumulativePaid() { return cumulativePaid; }
        public int getRemainingBalance() { return remainingBalance; }
        public Instant getRecordedAt() { return recordedAt; }
    }
}
