package com.revature.cardealer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("TARGET-BEHAVIOR")
class PaymentsCharacterizationTest {

    @Test
    void setAmtDueUpdatesDueAmountWithoutChangingLastPayment() {
        Payments payments = new Payments();
        payments.setAmtPaid(125);

        payments.setAmtDue(425);

        assertEquals(125, payments.getAmtPaid());
        assertEquals(425, payments.getAmtDue());
    }

    @Test
    void monthlyPaymentCalculationUpdatesSummary() {
        Payments payments = new Payments();
        payments.setAmtOwed(12_000);

        int monthlyAmount = payments.MPay(12);

        assertEquals(1_000, monthlyAmount);
        assertEquals(1_000, payments.getAmtDue());
        assertNotNull(payments.getpPayment());
        assertTrue(payments.getpPayment().contains("Monthly Due: 1000"));
    }

    @Test
    void paymentsAccumulateAndCreateAttributedTransactions() {
        Payments payments = new Payments();
        payments.setAmtOwed(1_000);

        payments.makePayment("customer-a", 250);
        payments.makePayment("customer-a", 300);

        assertEquals(450, payments.getBalance());
        assertEquals(300, payments.getAmtPaid());
        assertEquals(550, payments.getTotalPaid());
        assertEquals(2, payments.getTransactions().size());

        PaymentTransaction first = payments.getTransactions().get(0);
        PaymentTransaction second = payments.getTransactions().get(1);
        assertEquals("PAY-000001", first.getTransactionId());
        assertEquals("PAY-000002", second.getTransactionId());
        assertEquals("customer-a", second.getCustomerName());
        assertEquals(300, second.getAmount());
        assertEquals(550, second.getTotalPaid());
        assertEquals(450, second.getRemainingBalance());
        assertNotNull(second.getRecordedAt());
    }

    @Test
    void overpaymentIsRejectedWithoutChangingLedgerOrBalance() {
        Payments payments = new Payments();
        payments.setAmtOwed(500);

        assertThrows(IllegalArgumentException.class,
                () -> payments.makePayment("customer", 700));

        assertEquals(500, payments.getBalance());
        assertEquals(0, payments.getTotalPaid());
        assertTrue(payments.getTransactions().isEmpty());
    }

    @Test
    void paymentHistoryIsNotLimitedToTwentyEntries() {
        Payments payments = new Payments();
        payments.setAmtOwed(10_000);

        for (int index = 0; index < 25; index++) {
            payments.makePayment("customer", 1);
        }

        assertEquals(25, payments.getTransactions().size());
        assertEquals("PAY-000025", payments.getTransactions().get(24).getTransactionId());
        assertEquals(9_975, payments.getBalance());
    }

    @Test
    void transactionSnapshotsCannotBeModifiedExternally() {
        Payments payments = new Payments();
        payments.setAmtOwed(100);
        payments.makePayment("customer", 25);

        List<PaymentTransaction> snapshot = payments.getTransactions();

        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        assertEquals(1, payments.getTransactions().size());
    }

    @Test
    void exactFinalPaymentProducesZeroBalance() {
        Payments payments = new Payments();
        payments.setAmtOwed(500);

        payments.makePayment("customer", 500);

        assertEquals(0, payments.getBalance());
        assertEquals(500, payments.getTotalPaid());
        assertEquals(0, payments.getTransactions().get(0).getRemainingBalance());
    }

    @Test
    void invalidPaymentInputIsRejected() {
        Payments payments = new Payments();
        payments.setAmtOwed(500);

        assertThrows(IllegalArgumentException.class,
                () -> payments.makePayment(" ", 100));
        assertThrows(IllegalArgumentException.class,
                () -> payments.makePayment("customer", 0));
        assertThrows(IllegalArgumentException.class,
                () -> payments.makePayment("customer", -1));
        assertFalse(payments.getTransactions().iterator().hasNext());
    }

    @Test
    void paymentReportHandlesEmptyAndPopulatedLedgers() {
        Payments payments = new Payments();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        PrintStream original = System.out;
        try {
            System.setOut(new PrintStream(output));
            payments.getPaymentsAll();
            assertTrue(output.toString().contains("No payments"));

            output.reset();
            payments.setAmtOwed(1_000);
            payments.setAmtDue(100);
            payments.makePayment("dane", 100);
            payments.getPaymentsAll();
            String report = output.toString();
            assertTrue(report.contains("PAY-000001"));
            assertTrue(report.contains("Customer: dane"));
            assertTrue(report.contains("Balance: 900"));
        } finally {
            System.setOut(original);
        }
    }

    @Test
    void zeroLengthPaymentTermReturnsZero() {
        Payments payments = new Payments();
        payments.setAmtOwed(1_200);

        assertEquals(0, payments.MPay(0));
        assertEquals(0, payments.getAmtDue());
    }
}
