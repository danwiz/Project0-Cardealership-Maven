package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.revature.application.ProviderPaymentIdentity;

/**
 * W34.2 real PostgreSQL16 local posting lab. Signed receipts are issued and
 * verified with Wave33 test-only CI HMAC; NOT real provider credentials.
 *
 * All 3 methods must run (no skip) on each exact PR SHA in Java11 and Java17.
 * A passing lab is NOT authorization to migrate or use this in production.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
@Tag("SECURITY")
class Wave34PostgresqlPaymentReconciliationTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";

    @Test
    @Timeout(100)
    void simultaneousSameReceiptCreatesExactlyOneAtomicPaymentAndReplay() throws Exception {
        requiredCi();
        try (Fixture f = new Fixture()) {
            ProviderPaymentIdentity original = signed(f.ownership, "req-a", "txn-a",
                    "buyer", 1250, "USD", false);
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch start = new CountDownLatch(1);
                java.util.concurrent.Callable<JdbcWave34PaymentReconciliationCandidate.Outcome> task =
                        () -> {
                            ready.countDown();
                            if (!start.await(15, TimeUnit.SECONDS)) {
                                throw new AssertionError("simultaneous receipt worker not ready");
                            }
                            return f.reconciler.reconcile(original);
                        };
                Future<JdbcWave34PaymentReconciliationCandidate.Outcome> a = workers.submit(task);
                Future<JdbcWave34PaymentReconciliationCandidate.Outcome> b = workers.submit(task);
                assertTrue(ready.await(15, TimeUnit.SECONDS), "both workers ready");
                start.countDown();
                JdbcWave34PaymentReconciliationCandidate.Outcome first =
                        a.get(55, TimeUnit.SECONDS);
                JdbcWave34PaymentReconciliationCandidate.Outcome second =
                        b.get(55, TimeUnit.SECONDS);
                assertEquals(first.getLocalTransactionId(), second.getLocalTransactionId());
                assertTrue(first.isReplay() ^ second.isReplay(),
                        "exactly one initial local posting and one replay");
            } finally {
                workers.shutdownNow();
            }
            f.assertState(1, 1250, 22750);
            JdbcWave34PaymentReconciliationCandidate.Outcome retry =
                    f.reconciler.reconcile(original);
            assertTrue(retry.isReplay());
            f.assertState(1, 1250, 22750);
        }
    }

    @Test
    @Timeout(100)
    void signedConflictAndInvalidProofCannotChangeExistingCommittedPayment() throws Exception {
        requiredCi();
        try (Fixture f = new Fixture()) {
            ProviderPaymentIdentity original = signed(f.ownership, "req-a", "txn-a",
                    "buyer", 1250, "USD", false);
            String localId = f.reconciler.reconcile(original).getLocalTransactionId();
            assertNotNull(localId);
            f.assertState(1, 1250, 22750);

            // A *validly signed* different amount with the same external
            // request must be a conflict, not a second payment.
            ProviderPaymentIdentity changedAmount = signed(f.ownership, "req-a", "txn-a",
                    "buyer", 1251, "USD", false);
            assertThrows(SecurityException.class, () -> f.reconciler.reconcile(changedAmount));
            f.assertState(1, 1250, 22750);

            // A fabricated signature must fail BEFORE any new SQL write.
            ProviderPaymentIdentity invalidSignature = signed(f.ownership, "req-b", "txn-b",
                    "buyer", 1250, "USD", true);
            assertThrows(SecurityException.class,
                    () -> f.reconciler.reconcile(invalidSignature));
            f.assertState(1, 1250, 22750);

            // Valid HMAC on wrong customer still cannot bind the ownership.
            ProviderPaymentIdentity wrongCustomer = signed(f.ownership, "req-c", "txn-c",
                    "other", 1250, "USD", false);
            assertThrows(SecurityException.class,
                    () -> f.reconciler.reconcile(wrongCustomer));
            f.assertState(1, 1250, 22750);

            // The original receipt can still replay with the same local ID.
            JdbcWave34PaymentReconciliationCandidate.Outcome replay =
                    f.reconciler.reconcile(original);
            assertTrue(replay.isReplay());
            assertEquals(localId, replay.getLocalTransactionId());
        }
    }

    @Test
    @Timeout(100)
    void rejectedPostRollsBackReceiptAndTwoRealInstallmentsAreDistinct() throws Exception {
        requiredCi();
        try (Fixture f = new Fixture()) {
            ProviderPaymentIdentity tooLarge = signed(f.ownership, "req-too-large",
                    "txn-too-large", "buyer", 25000, "USD", false);
            assertThrows(IllegalArgumentException.class,
                    () -> f.reconciler.reconcile(tooLarge));
            f.assertState(0, 0, 24000);

            ProviderPaymentIdentity first = signed(f.ownership, "req-a", "txn-a",
                    "buyer", 1250, "USD", false);
            ProviderPaymentIdentity second = signed(f.ownership, "req-b", "txn-b",
                    "buyer", 1250, "USD", false);
            JdbcWave34PaymentReconciliationCandidate.Outcome a =
                    f.reconciler.reconcile(first);
            JdbcWave34PaymentReconciliationCandidate.Outcome b =
                    f.reconciler.reconcile(second);
            assertFalse(a.isReplay());
            assertFalse(b.isReplay());
            assertNotEquals(a.getLocalTransactionId(), b.getLocalTransactionId());
            f.assertState(2, 2500, 21500);

            // A different provider request may NOT reuse a provider transaction.
            ProviderPaymentIdentity txCollision = signed(f.ownership, "req-c", "txn-b",
                    "buyer", 1250, "USD", false);
            assertThrows(SecurityException.class,
                    () -> f.reconciler.reconcile(txCollision),
                    "a reused provider transaction is an immutable-identity conflict");
            f.assertState(2, 2500, 21500);

            assertTrue(f.reconciler.reconcile(first).isReplay());
            assertTrue(f.reconciler.reconcile(second).isReplay());
            f.assertState(2, 2500, 21500);
        }
    }

    private static void requiredCi() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD")),
                "disposable PG16 CI required, not local or production database");
    }

    private static ProviderPaymentIdentity signed(long ownership, String request,
            String providerTx, String customer, int amount, String currency,
            boolean maliciousSignature) {
        String valid = Wave33SyntheticProviderSignature.issue(request, providerTx,
                customer, "ACQ-000001", amount, "COMMITTED");
        // Fixed 64-hex data is structurally valid but cryptographically incorrect.
        String signature = maliciousSignature
                ? "0000000000000000000000000000000000000000000000000000000000000000"
                : valid;
        return new ProviderPaymentIdentity("synthetic", request, providerTx,
                customer, ownership, "ACQ-000001", amount, currency,
                "COMMITTED", Wave33SyntheticProviderSignature.KEY_ID, signature);
    }

    private static final class Fixture implements AutoCloseable {
        private final String schema = "w34_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcDatabase operator = new JdbcDatabase(URL, USER, PASSWORD);
        private JdbcDatabase scoped;
        private long ownership;
        private JdbcWave34PaymentReconciliationCandidate reconciler;

        Fixture() throws Exception {
            try (Connection c = operator.openConnection(); Statement s = c.createStatement()) {
                try (ResultSet version = s.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int number = Integer.parseInt(version.getString(1));
                    assertTrue(number >= 160000 && number < 170000, "real PostgreSQL16 only");
                }
                s.execute("CREATE SCHEMA " + schema);
            }
            scoped = new JdbcDatabase(URL + "?currentSchema=" + schema, USER, PASSWORD);
            scoped.migrate();
            ownership = seed();
            String ddl = new String(Files.readAllBytes(Paths.get(
                    "docs/audit/wave34/wave34_candidate_additive_receipt.sql")),
                    StandardCharsets.UTF_8);
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                s.execute(ddl);
            }
            reconciler = new JdbcWave34PaymentReconciliationCandidate(scoped,
                    receipt -> {
                        // Exact synthetic provider and currency pinned; Wave33
                        // shared test key intentionally does NOT sign currency.
                        if (!"synthetic".equals(receipt.getProvider())
                                || !"USD".equals(receipt.getCurrency())) {
                            throw new SecurityException("untrusted synthetic provider policy");
                        }
                        Wave33SyntheticProviderSignature.verify(
                                receipt.getKeyId(), receipt.getSignatureHex(),
                                receipt.getRequestId(), receipt.getTransactionId(),
                                receipt.getCustomer(), receipt.getContractId(),
                                receipt.getAmount(), receipt.getStatus());
                    }, "USD");
        }

        private long seed() throws SQLException {
            try (Connection c = scoped.openConnection()) {
                try (Statement s = c.createStatement()) {
                    s.execute("INSERT INTO accounts(username,encoded_credential,role) "
                            + "VALUES('buyer','synthetic-fixture-only','CUSTOMER')");
                }
                long id;
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO owned_vehicles "
                        + "(owner_username,make,model,vehicle_year,contract_id) "
                        + "VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                        Statement.RETURN_GENERATED_KEYS)) {
                    assertEquals(1, s.executeUpdate());
                    try (ResultSet generated = s.getGeneratedKeys()) {
                        assertTrue(generated.next());
                        id = generated.getLong(1);
                    }
                }
                try (PreparedStatement s = c.prepareStatement(
                        "INSERT INTO payment_plans "
                        + "(ownership_id,purchase_price,payment_months,monthly_payment,"
                        + "amount_paid,balance) VALUES(?,24000,24,1000,0,24000)")) {
                    s.setLong(1, id);
                    assertEquals(1, s.executeUpdate());
                }
                return id;
            }
        }

        private void assertState(int expectedPayments, int expectedPaid, int expectedBalance)
                throws SQLException {
            try (Connection c = scoped.openConnection(); Statement s = c.createStatement()) {
                try (ResultSet n = s.executeQuery("SELECT count(*) FROM payment_transactions")) {
                    assertTrue(n.next());
                    assertEquals(expectedPayments, n.getInt(1));
                }
                try (ResultSet n = s.executeQuery("SELECT count(*) FROM provider_payment_postings")) {
                    assertTrue(n.next());
                    assertEquals(expectedPayments, n.getInt(1),
                            "failed transactions must leave no orphan receipt");
                }
                try (ResultSet n = s.executeQuery("SELECT count(*) FROM provider_payment_postings "
                        + "WHERE local_transaction_id IS NULL OR posted_at IS NULL")) {
                    assertTrue(n.next());
                    assertEquals(0, n.getInt(1), "no unbound committed receipt");
                }
                try (ResultSet p = s.executeQuery("SELECT amount_paid,balance FROM payment_plans")) {
                    assertTrue(p.next());
                    assertEquals(expectedPaid, p.getInt(1));
                    assertEquals(expectedBalance, p.getInt(2));
                    assertFalse(p.next());
                }
            }
        }

        @Override
        public void close() throws Exception {
            try (Connection c = operator.openConnection(); Statement s = c.createStatement()) {
                s.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
