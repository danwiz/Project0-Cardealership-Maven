package com.revature.repository;

import java.util.List;

import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;

public interface PaymentTransactionRepository {
    List<PaymentTransaction> findAll();
    Payments ledger();
}
