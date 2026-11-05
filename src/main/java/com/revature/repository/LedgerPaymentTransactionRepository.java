package com.revature.repository;

import java.util.List;
import java.util.Objects;

import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;

public final class LedgerPaymentTransactionRepository implements PaymentTransactionRepository {
    private final Payments payments;

    public LedgerPaymentTransactionRepository(Payments payments) {
        this.payments = Objects.requireNonNull(payments, "payments");
    }

    @Override public List<PaymentTransaction> findAll() { return payments.getTransactions(); }
    @Override public Payments ledger() { return payments; }
}
