package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;
import com.revature.cardealer.User;

class JdbcPaymentTransactionRepositoryTest {
    private JdbcDatabase database;

    @BeforeEach
    void setUp() {
        database = JdbcDatabase.inMemory("payments_" + System.nanoTime());
        database.migrate();
        JdbcUserAccountRepository accounts = new JdbcUserAccountRepository(database);
        accounts.save(user("alice", AccountRole.CUSTOMER));
        accounts.save(user("bob", AccountRole.CUSTOMER));
        accounts.save(user("employee", AccountRole.EMPLOYEE));
    }

    @Test
    void recordsStableSequenceAndReconstructsLedger() {
        JdbcPaymentTransactionRepository repository = new JdbcPaymentTransactionRepository(database, "alice");
        PaymentTransaction first = repository.recordPayment(200, 1000);
        PaymentTransaction second = repository.recordPayment(300, 1000);

        assertEquals("PAY-000001", first.getTransactionId());
        assertEquals("PAY-000002", second.getTransactionId());
        assertEquals(500, second.getTotalPaid());
        assertEquals(500, second.getRemainingBalance());

        Payments ledger = repository.ledger();
        assertEquals(1000, ledger.getAmtOwed());
        assertEquals(500, ledger.getTotalPaid());
        assertEquals(500, ledger.getBalance());
    }

    @Test
    void persistsAcrossRepositoryInstancesAndScopesCustomers() {
        new JdbcPaymentTransactionRepository(database, "alice").recordPayment(100, 500);
        new JdbcPaymentTransactionRepository(database, "bob").recordPayment(40, 200);

        assertEquals(1, new JdbcPaymentTransactionRepository(database, "alice").findAll().size());
        assertEquals(1, new JdbcPaymentTransactionRepository(database, "bob").findAll().size());
        assertEquals(100, new JdbcPaymentTransactionRepository(database, "alice").findAll().get(0).getAmount());
    }

    @Test
    void rejectsOverpaymentAndMismatchedLedgerTotalWithoutMutation() {
        JdbcPaymentTransactionRepository repository = new JdbcPaymentTransactionRepository(database, "alice");
        repository.recordPayment(100, 500);

        assertThrows(IllegalArgumentException.class, () -> repository.recordPayment(401, 500));
        assertThrows(IllegalArgumentException.class, () -> repository.recordPayment(10, 700));
        assertEquals(1, repository.findAll().size());
    }

    @Test
    void replaceAllRestoresHistoryAndSequence() {
        JdbcPaymentTransactionRepository repository = new JdbcPaymentTransactionRepository(database, "alice");
        List<PaymentTransaction> restored = Arrays.asList(
                new PaymentTransaction("PAY-000004", "alice", 100, 100, 400, Instant.parse("2026-01-01T00:00:00Z")),
                new PaymentTransaction("PAY-000007", "alice", 150, 250, 250, Instant.parse("2026-01-02T00:00:00Z")));
        repository.replaceAll(restored);

        assertEquals(2, repository.findAll().size());
        assertEquals("PAY-000008", repository.recordPayment(50, 500).getTransactionId());
    }

    @Test
    void invalidReplacementLeavesExistingHistoryUntouched() {
        JdbcPaymentTransactionRepository repository = new JdbcPaymentTransactionRepository(database, "alice");
        repository.recordPayment(100, 500);
        List<PaymentTransaction> invalid = Arrays.asList(
                new PaymentTransaction("PAY-000001", "alice", 100, 100, 400, Instant.now()),
                new PaymentTransaction("PAY-000002", "alice", 50, 180, 320, Instant.now()));

        assertThrows(IllegalArgumentException.class, () -> repository.replaceAll(invalid));
        assertEquals(1, repository.findAll().size());
        assertEquals(100, repository.findAll().get(0).getAmount());
    }

    @Test
    void replacementCanClearOnlySelectedCustomer() {
        JdbcPaymentTransactionRepository alice = new JdbcPaymentTransactionRepository(database, "alice");
        JdbcPaymentTransactionRepository bob = new JdbcPaymentTransactionRepository(database, "bob");
        alice.recordPayment(100, 500);
        bob.recordPayment(50, 200);

        alice.replaceAll(Collections.emptyList());

        assertEquals(0, alice.findAll().size());
        assertEquals(1, bob.findAll().size());
    }

    @Test
    void validatesCustomerRoleAndImmutableResults() {
        assertThrows(IllegalArgumentException.class,
                () -> new JdbcPaymentTransactionRepository(database, "employee"));
        JdbcPaymentTransactionRepository repository = new JdbcPaymentTransactionRepository(database, "alice");
        repository.recordPayment(100, 500);
        assertThrows(UnsupportedOperationException.class,
                () -> repository.findAll().add(repository.findAll().get(0)));
    }

    private static User user(String username, AccountRole role) {
        User user = new User();
        user.setUsername(username);
        user.setPassword("encoded-credential");
        user.setRole(role);
        return user;
    }
}
