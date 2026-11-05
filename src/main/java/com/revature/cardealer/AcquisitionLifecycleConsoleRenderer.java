package com.revature.cardealer;

import com.revature.application.AcquisitionLifecycleQueryService.AcquisitionLifecycleView;
import com.revature.application.AcquisitionLifecycleQueryService.PaymentEntryView;

/** Console renderer for one complete acquisition contract timeline. */
public final class AcquisitionLifecycleConsoleRenderer {
    private final ConsoleIO io;

    public AcquisitionLifecycleConsoleRenderer(ConsoleIO io) {
        if (io == null) throw new IllegalArgumentException("io must not be null");
        this.io = io;
    }

    public void render(AcquisitionLifecycleView view) {
        io.writeLine("Contract: " + view.getContractId()
                + " Customer: " + view.getCustomerName()
                + " Request: " + view.getRequestId()
                + " Listing: " + view.getListingId()
                + " Status: " + view.getRequestStatus());
        io.writeLine("Ownership: " + view.getOwnershipId()
                + " Vehicle: " + view.getMake() + " " + view.getModel() + " " + view.getYear());
        io.writeLine("Purchase Price: " + view.getPurchasePrice()
                + " Term: " + view.getTermMonths()
                + " Monthly Payment: " + view.getMonthlyPayment()
                + " Amount Paid: " + view.getAmountPaid()
                + " Balance: " + view.getRemainingBalance());
        if (view.getPayments().isEmpty()) {
            io.writeLine("No contract payments have been recorded.");
            return;
        }
        for (PaymentEntryView payment : view.getPayments()) {
            io.writeLine("[" + payment.getTransactionId() + "] Amount: " + payment.getAmount()
                    + " Cumulative Paid: " + payment.getCumulativePaid()
                    + " Balance: " + payment.getRemainingBalance()
                    + " Recorded At: " + payment.getRecordedAt());
        }
    }
}
