package com.revature.application;

import com.revature.cardealer.PaymentTransaction;

/** Atomic boundary for applying a payment to one customer ownership plan. */
public interface PaymentProcessor {
    PaymentTransaction record(String customerName, long ownershipId, int amount);
}
