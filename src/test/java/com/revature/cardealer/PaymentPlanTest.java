package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

class PaymentPlanTest {

    @Test
    @Tag("TARGET-BEHAVIOR")
    void recordsPaymentsAndCalculatesRemainingBalance() {
        PaymentPlan plan = new PaymentPlan(12000, 12);

        plan.recordPayment(1000);
        plan.recordPayment(500);

        assertEquals(12000, plan.getPurchasePrice());
        assertEquals(12, plan.getTermMonths());
        assertEquals(1000, plan.getMonthlyPayment());
        assertEquals(1500, plan.getAmountPaid());
        assertEquals(10500, plan.getRemainingBalance());
    }

    @Test
    void rejectsInvalidPlanTerms() {
        assertThrows(IllegalArgumentException.class, () -> new PaymentPlan(-1, 12));
        assertThrows(IllegalArgumentException.class, () -> new PaymentPlan(12000, 0));
    }

    @Test
    void rejectsNonPositiveAndExcessPaymentsWithoutMutatingBalance() {
        PaymentPlan plan = new PaymentPlan(12000, 12);

        assertThrows(IllegalArgumentException.class, () -> plan.recordPayment(0));
        assertThrows(IllegalArgumentException.class, () -> plan.recordPayment(-1));
        assertThrows(IllegalArgumentException.class, () -> plan.recordPayment(12001));
        assertEquals(0, plan.getAmountPaid());
        assertEquals(12000, plan.getRemainingBalance());
    }

    @Test
    void permitsExactFinalPayment() {
        PaymentPlan plan = new PaymentPlan(12000, 12);

        plan.recordPayment(12000);

        assertEquals(0, plan.getRemainingBalance());
        assertThrows(IllegalArgumentException.class, () -> plan.recordPayment(1));
    }
}