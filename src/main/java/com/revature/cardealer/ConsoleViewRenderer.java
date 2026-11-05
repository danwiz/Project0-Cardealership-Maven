package com.revature.cardealer;

import java.util.List;

import com.revature.application.DealershipQueryService.AccountView;
import com.revature.application.DealershipQueryService.InventoryView;
import com.revature.application.DealershipQueryService.OwnershipView;
import com.revature.application.DealershipQueryService.PaymentReportView;
import com.revature.application.DealershipQueryService.PaymentTransactionView;
import com.revature.application.DealershipQueryService.PurchaseRequestView;

/** Console-specific rendering of presentation-neutral query responses. */
public final class ConsoleViewRenderer {
    private final ConsoleIO io;

    public ConsoleViewRenderer(ConsoleIO io) {
        if (io == null) throw new IllegalArgumentException("io must not be null");
        this.io = io;
    }

    public void account(AccountView account) { io.writeLine("Account: " + account.getUsername() + " Role: " + account.getRole()); }

    public void inventory(List<InventoryView> listings) {
        if (listings.isEmpty()) { io.writeLine("\nWe have no cars at this time!"); return; }
        for (InventoryView listing : listings) {
            io.writeLine("[" + listing.getId() + "]   " + listing.getMake() + " " + listing.getModel()
                    + " " + listing.getYear() + "   Price: " + listing.getPrice()
                    + "   Avail: " + (listing.isAvailable() ? "yes" : "no")
                    + "   Stock Qty: " + listing.getStockQuantity());
        }
    }

    public void pendingRequests(List<PurchaseRequestView> requests) {
        if (requests.isEmpty()) { io.writeLine("\nWe have no pending purchase requests at this time!"); return; }
        for (PurchaseRequestView request : requests) {
            io.writeLine("[" + request.getId() + "] Listing " + request.getListingId()
                    + "   |---> Pending Offer: " + request.getCustomerName());
        }
    }

    public void ownership(List<OwnershipView> ownership) {
        if (ownership.isEmpty()) { io.writeLine("No vehicles owned."); return; }
        for (OwnershipView item : ownership) {
            io.writeLine("[" + item.getOwnershipId() + "] " + item.getMake() + " " + item.getModel()
                    + " " + item.getYear() + "   Contract: " + item.getContractId().orElse("historical")
                    + "   Price: " + item.getPurchasePrice()
                    + "   Amount Paid: " + item.getAmountPaid()
                    + "   Balance: " + item.getRemainingBalance()
                    + "   Monthly Cost: " + item.getMonthlyPayment());
        }
    }

    public void payments(PaymentReportView report) {
        if (report.getTransactions().isEmpty()) { io.writeLine("No payments have been recorded."); return; }
        for (PaymentTransactionView transaction : report.getTransactions()) {
            String ownership = transaction.getOwnershipId().isPresent()
                    ? " Ownership: " + transaction.getOwnershipId().getAsLong() : "";
            io.writeLine("[" + transaction.getTransactionId() + "] Cost: " + report.getAmountOwed()
                    + " Amount Paid: " + transaction.getAmount()
                    + " Total Paid: " + transaction.getTotalPaid()
                    + " Balance: " + transaction.getRemainingBalance()
                    + " Customer: " + transaction.getCustomerName() + ownership
                    + " Contract: " + transaction.getContractId().orElse("historical")
                    + " Recorded At: " + transaction.getRecordedAt());
        }
    }
}
