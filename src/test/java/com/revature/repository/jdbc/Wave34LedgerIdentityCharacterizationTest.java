package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
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

import com.revature.cardealer.PaymentTransaction;

/**
 * Wave34.4 NEGATIVE CHARACTERIZATION of unchanged legacy payment IDs,
 * plus a disposable PostgreSQL sequence-design experiment. Not a production
 * fix, migration, guarantee of real-world payment correctness or release gate.
 */
@Tag("WAVE34")
class Wave34LedgerIdentityCharacterizationTest {
    private static final String PG_URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String PG_USER = "dealership_ci_owner";
    private static final String PG_PASSWORD = "ci-only-postgres-fixture";

    @Test
    void sevenDigitBoundarySortsAfterLegacyMaximumAndBlocksNextPayment() throws Exception {
        JdbcDatabase db = JdbcDatabase.inMemory(
                "w344_id_limit_" + UUID.randomUUID().toString().replace("-", ""));
        db.migrate();
        long ownership = seedBuyer(db, 1, 23999);
        try (Connection c = db.openConnection();
                PreparedStatement statement = c.prepareStatement(
                        "INSERT INTO payment_transactions "
                        + "(transaction_id,customer_name,ownership_id,contract_id,"
                        + "amount,cumulative_paid,resulting_balance,recorded_at)"
                        + " VALUES ('PAY-999999','buyer',?,'ACQ-000001',1,1,23999,CURRENT_TIMESTAMP)")) {
            statement.setLong(1, ownership);
            assertEquals(1, statement.executeUpdate());
        }

        JdbcPaymentProcessor payments = new JdbcPaymentProcessor(db);
        PaymentTransaction millionth = payments.record("buyer", ownership, 1);
        assertEquals("PAY-1000000", millionth.getTransactionId(),
                "six digits is a *minimum formatting width*, not a maximum");
        // The VARCHAR ordering itself is unsafe: PAY-999999 sorts after
        // PAY-1000000 and is selected as the "latest" ID again.
        try (Connection c = db.openConnection(); Statement stmt = c.createStatement();
                ResultSet latest = stmt.executeQuery(
                        "SELECT transaction_id FROM payment_transactions "
                        + "ORDER BY transaction_id DESC LIMIT 1")) {
            assertTrue(latest.next());
            assertEquals("PAY-999999", latest.getString(1),
                    "lexical ordering is not numeric ledger-ID ordering");
        }
        IllegalStateException duplicate = assertThrows(IllegalStateException.class,
                () -> payments.record("buyer", ownership, 1),
                "the allocator issues the same PAY-1000000 again");
        assertTrue(duplicate.getCause() instanceof java.sql.SQLException,
                "next payment fails database uniqueness, not a second commit");

        // Atomic rollback retains exact previously committed balance and count.
        try (Connection c = db.openConnection(); Statement statement = c.createStatement()) {
            try (ResultSet rows = statement.executeQuery(
                    "SELECT COUNT(*) FROM payment_transactions")) {
                assertTrue(rows.next());
                assertEquals(2, rows.getInt(1));
            }
            try (ResultSet plan = statement.executeQuery(
                    "SELECT amount_paid,balance FROM payment_plans")) {
                assertTrue(plan.next());
                assertEquals(2, plan.getInt(1));
                assertEquals(23998, plan.getInt(2));
                assertFalse(plan.next());
            }
        }
    }

    @Test
    @Tag("POSTGRESQL")
    @Timeout(60)
    void concurrentFirstPaymentLookupsFindNoRowToLock() throws Exception {
        requireDisposablePg16();
        try (Fixture fixture = new Fixture()) {
            assertEquals(0, fixture.paymentCount());
            ExecutorService workers = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch fire = new CountDownLatch(1);
                java.util.concurrent.Callable<Boolean> lookup = () -> {
                    try (Connection connection = fixture.scoped.openConnection()) {
                        connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                        connection.setAutoCommit(false);
                        ready.countDown();
                        if (!fire.await(15, TimeUnit.SECONDS))
                            throw new AssertionError("empty-lock rendezvous timed out");
                        try (Statement statement = connection.createStatement();
                                ResultSet latest = statement.executeQuery(
                                        "SELECT transaction_id FROM payment_transactions "
                                        + "ORDER BY transaction_id DESC LIMIT 1 FOR UPDATE")) {
                            boolean empty = !latest.next();
                            connection.rollback();
                            return empty;
                        }
                    }
                };
                Future<Boolean> first = workers.submit(lookup);
                Future<Boolean> second = workers.submit(lookup);
                assertTrue(ready.await(15, TimeUnit.SECONDS));
                fire.countDown();
                assertTrue(first.get(30, TimeUnit.SECONDS));
                assertTrue(second.get(30, TimeUnit.SECONDS));
            } finally {
                workers.shutdownNow();
            }
            assertEquals(0, fixture.paymentCount(),
                    "SELECT FOR UPDATE on an empty ledger cannot lock a missing ID row");
        }
    }

    @Test
    @Tag("POSTGRESQL")
    @Timeout(60)
    void disposableDatabaseSequenceAllocatesDistinctIdsBeforeAnyLedgerRow() throws Exception {
        requireDisposablePg16();
        try (Fixture fixture = new Fixture()) {
            try (Connection c = fixture.scoped.openConnection();
                    Statement statement = c.createStatement()) {
                statement.execute("CREATE SEQUENCE wave34_4_review_only_seq START WITH 1");
            }
            Set<Long> allocated = Collections.synchronizedSet(new HashSet<>());
            ExecutorService workers = Executors.newFixedThreadPool(4);
            try {
                java.util.List<Future<?>> tasks = new java.util.ArrayList<>();
                for (int worker = 0; worker < 4; worker++) {
                    tasks.add(workers.submit(() -> {
                        try (Connection c = fixture.scoped.openConnection();
                                Statement statement = c.createStatement()) {
                            for (int i = 0; i < 8; i++) {
                                try (ResultSet next = statement.executeQuery(
                                        "SELECT nextval('wave34_4_review_only_seq')")) {
                                    if (!next.next() || !allocated.add(next.getLong(1)))
                                        throw new AssertionError("duplicate/unavailable sequence identity");
                                }
                            }
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }));
                }
                for (Future<?> result : tasks) result.get(35, TimeUnit.SECONDS);
            } finally {
                workers.shutdownNow();
            }
            assertEquals(32, allocated.size());
            assertTrue(allocated.contains(1L));
            assertTrue(allocated.contains(32L));
            assertEquals(0, fixture.paymentCount(),
                    "sequence identity allocation alone does not create a payment");
        }
    }

    private static long seedBuyer(JdbcDatabase db, int amountPaid, int balance)
            throws Exception {
        try (Connection c = db.openConnection(); Statement statement = c.createStatement()) {
            statement.executeUpdate("INSERT INTO accounts(username,encoded_credential,role)"
                    + " VALUES('buyer','ci-only','CUSTOMER')");
        }
        long ownership;
        try (Connection c = db.openConnection();
                PreparedStatement statement = c.prepareStatement(
                        "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year,contract_id)"
                        + " VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                        Statement.RETURN_GENERATED_KEYS)) {
            assertEquals(1, statement.executeUpdate());
            try (ResultSet result = statement.getGeneratedKeys()) {
                assertTrue(result.next());
                ownership = result.getLong(1);
            }
        }
        try (Connection c = db.openConnection();
                PreparedStatement statement = c.prepareStatement(
                        "INSERT INTO payment_plans(ownership_id,purchase_price,"
                        + "payment_months,monthly_payment,amount_paid,balance)"
                        + " VALUES(?,24000,24,1000,?,?)")) {
            statement.setLong(1, ownership);
            statement.setInt(2, amountPaid);
            statement.setInt(3, balance);
            assertEquals(1, statement.executeUpdate());
        }
        return ownership;
    }

    private static void requireDisposablePg16() {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && PG_URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && PG_USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PG_PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD")),
                "Only the explicitly pinned disposable PostgreSQL 16 Actions fixture is authorized");
    }

    private static final class Fixture implements AutoCloseable {
        private final String schema =
                "w344_" + UUID.randomUUID().toString().replace("-", "");
        private final JdbcDatabase owner = new JdbcDatabase(PG_URL, PG_USER, PG_PASSWORD);
        private final JdbcDatabase scoped =
                new JdbcDatabase(PG_URL + "?currentSchema=" + schema, PG_USER, PG_PASSWORD);

        Fixture() throws Exception {
            try (Connection c = owner.openConnection();
                    Statement statement = c.createStatement()) {
                try (ResultSet version = statement.executeQuery("SHOW server_version_num")) {
                    assertTrue(version.next());
                    int value = Integer.parseInt(version.getString(1));
                    assertTrue(value >= 160000 && value < 170000,
                            "real PostgreSQL 16 is required");
                }
                statement.execute("CREATE SCHEMA " + schema);
            }
            scoped.migrate(); // migration v5 only, disposable per-test schema
        }

        int paymentCount() throws Exception {
            try (Connection c = scoped.openConnection();
                    Statement statement = c.createStatement();
                    ResultSet result = statement.executeQuery(
                            "SELECT COUNT(*) FROM payment_transactions")) {
                assertTrue(result.next());
                return result.getInt(1);
            }
        }

        @Override public void close() throws Exception {
            try (Connection c = owner.openConnection();
                    Statement statement = c.createStatement()) {
                statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }
}
