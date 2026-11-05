package com.revature.cardealer;

import java.io.Serializable;

/** Payment state associated with one purchased vehicle. */
public class PaymentPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    private final int purchasePrice;
    private final int termMonths;
    private int amountPaid;

    public PaymentPlan(int purchasePrice, int termMonths) {
        if (purchasePrice < 0) throw new IllegalArgumentException("purchasePrice must not be negative");
        if (termMonths <= 0) throw new IllegalArgumentException("termMonths must be positive");
        this.purchasePrice = purchasePrice;
        this.termMonths = termMonths;
    }

    public int getPurchasePrice() { return purchasePrice; }
    public int getTermMonths() { return termMonths; }
    public int getAmountPaid() { return amountPaid; }
    public int getRemainingBalance() { return purchasePrice - amountPaid; }
    public int getMonthlyPayment() { return purchasePrice / termMonths; }

    public void recordPayment(int amount) {
        if (amount <= 0) throw new IllegalArgumentException("payment amount must be positive");
        if (amount > getRemainingBalance()) throw new IllegalArgumentException("payment exceeds remaining balance");
        amountPaid += amount;
    }
}
