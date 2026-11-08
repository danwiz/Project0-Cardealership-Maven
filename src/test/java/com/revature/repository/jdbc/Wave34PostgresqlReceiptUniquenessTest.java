package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
 * W34.1 proves a real PostgreSQL16 request/transaction uniqueness boundary.
 * NO application ledger posting, external merchant API, or signature
 * verification is claimed; the SQL is applied only in a disposable schema.
 */
@Tag("WAVE34")
@Tag("POSTGRESQL")
@Tag("SECURITY")
class Wave34PostgresqlReceiptUniquenessTest {
    private static final String URL = "jdbc:postgresql://localhost:5432/dealership_ci";
    private static final String USER = "dealership_ci_owner";
    private static final String PASSWORD = "ci-only-postgres-fixture";

    @Test
    @Timeout(100)
    void concurrentDuplicateReceiptsAreUniqueAndConflictingClaimsCannotOverwrite()
            throws Exception {
        Assumptions.assumeTrue("true".equalsIgnoreCase(System.getenv("CI"))
                && "true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                && "true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                && URL.equals(System.getenv("DEALERSHIP_PG_TEST_URL"))
                && USER.equals(System.getenv("DEALERSHIP_PG_TEST_USER"))
                && PASSWORD.equals(System.getenv("DEALERSHIP_PG_TEST_PASSWORD")),
                "W34 fixture must use explicitly configured disposable Actions PG16");

        String schema = "w34_" + UUID.randomUUID().toString().replace("-", "");
        JdbcDatabase operator = new JdbcDatabase(URL, USER, PASSWORD);
        try {
            try (Connection connection = operator.openConnection();
                    Statement statement = connection.createStatement()) {
                try (ResultSet server = statement.executeQuery("SHOW server_version_num")) {
                    assertTrue(server.next());
                    int version = Integer.parseInt(server.getString(1));
                    assertTrue(version >= 160000 && version < 170000, "real PostgreSQL16 required");
                }
                statement.execute("CREATE SCHEMA " + schema);
            }

            JdbcDatabase scoped = new JdbcDatabase(URL + "?currentSchema=" + schema, USER, PASSWORD);
            scoped.migrate(); // original v5 only, applied to disposable schema
            long ownership = seedOwner(scoped);
            String ddl = new String(Files.readAllBytes(Paths.get(
                    "docs/audit/wave34/wave34_candidate_additive_receipt.sql")),
                    StandardCharsets.UTF_8);
            try (Connection connection = scoped.openConnection();
                    Statement statement = connection.createStatement()) {
                statement.execute(ddl);
            }

            ProviderPaymentIdentity same = receipt("req-0001", "ptx-0001", ownership, 1250);
            ExecutorService threads = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch fire = new CountDownLatch(1);
                java.util.concurrent.Callable<Integer> run = () -> {
                    ready.countDown();
                    if (!fire.await(12, TimeUnit.SECONDS))
                        throw new AssertionError("replay test synchronization failed");
                    return insert(scoped, same);
                };
                Future<Integer> first = threads.submit(run);
                Future<Integer> second = threads.submit(run);
                assertTrue(ready.await(12, TimeUnit.SECONDS));
                fire.countDown();
                assertEquals(1, first.get(35, TimeUnit.SECONDS)
                        + second.get(35, TimeUnit.SECONDS),
                        "exactly one unique request may be inserted");
            } finally {
                threads.shutdownNow();
            }
            assertCounts(scoped, 1);
            assertEquals(0, insert(scoped, same), "exact duplicate is database no-op");

            ProviderPaymentIdentity conflict = receipt("req-0001", "ptx-0001",
                    ownership, 1251);
            assertEquals(ProviderPaymentIdentity.DuplicateDisposition.CONFLICT,
                    same.compareSameRequest(conflict));
            assertEquals(0, insert(scoped, conflict),
                    "same request is unique, but application MUST reject conflicting digest");
            assertCounts(scoped, 1);
            try (Connection connection = scoped.openConnection();
                    PreparedStatement lookup = connection.prepareStatement(
                            "SELECT verified_receipt_digest FROM provider_payment_postings "
                            + "WHERE provider_code=? AND provider_request_id=?")) {
                lookup.setString(1, same.getProvider());
                lookup.setString(2, same.getRequestId());
                try (ResultSet row = lookup.executeQuery()) {
                    assertTrue(row.next());
                    assertEquals(same.getDigest(), row.getString(1),
                            "a conflicting replay must not replace previous data");
                    assertFalse(row.next());
                }
            }

            // Same provider transaction with a different request is NOT allowed.
            ProviderPaymentIdentity transactionCollision = receipt("req-0002",
                    "ptx-0001", ownership, 1250);
            assertEquals(0, insert(scoped, transactionCollision),
                    "the second unique key must block a reused provider transaction");
            assertCounts(scoped, 1);

            // A separate provider request and transaction can represent a second
            // legitimate same-amount installment (this test does NOT post it).
            ProviderPaymentIdentity installment = receipt("req-0002",
                    "ptx-0002", ownership, 1250);
            assertEquals(1, insert(scoped, installment));
            assertCounts(scoped, 2);
            System.out.println("W34_REAL_PG16_PROVIDER_IDENTITY_BOUNDARY_ACCEPTED_NOT_LEDGER_POSTING");
        } finally {
            try (Connection connection = operator.openConnection();
                    Statement cleanup = connection.createStatement()) {
                cleanup.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
            }
        }
    }

    private static long seedOwner(JdbcDatabase db) throws Exception {
        try (Connection connection = db.openConnection()) {
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("INSERT INTO accounts(username,encoded_credential,role) "
                        + "VALUES('buyer','synthetic-fixture-only','CUSTOMER')");
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username,make,model,vehicle_year,contract_id) "
                    + "VALUES('buyer','Toyota','Corolla',2024,'ACQ-000001')",
                    Statement.RETURN_GENERATED_KEYS)) {
                assertEquals(1, stmt.executeUpdate());
                try (ResultSet generated = stmt.getGeneratedKeys()) {
                    assertTrue(generated.next());
                    return generated.getLong(1);
                }
            }
        }
    }

    private static ProviderPaymentIdentity receipt(String request, String tx,
            long ownership, int amount) {
        // Synthetic-looking bytes are NOT a verified merchant signature.
        return new ProviderPaymentIdentity("synthetic", request, tx, "buyer",
                ownership, "ACQ-000001", amount, "USD", "COMMITTED",
                "synthetic-ci-key", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
                        + "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
    }

    private static int insert(JdbcDatabase database, ProviderPaymentIdentity claim)
            throws SQLException {
        try (Connection connection = database.openConnection();
                PreparedStatement put = connection.prepareStatement(
                        "INSERT INTO provider_payment_postings "
                        + "(provider_code,provider_request_id,provider_transaction_id,"
                        + "customer_name,ownership_id,contract_id,amount,currency_code,"
                        + "verified_receipt_digest,signing_key_id) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?) "
                        + "ON CONFLICT DO NOTHING")) {
            put.setString(1, claim.getProvider());
            put.setString(2, claim.getRequestId());
            put.setString(3, claim.getTransactionId());
            put.setString(4, claim.getCustomer());
            put.setLong(5, claim.getOwnershipId());
            put.setString(6, claim.getContractId());
            put.setInt(7, claim.getAmount());
            put.setString(8, claim.getCurrency());
            put.setString(9, claim.getDigest());
            put.setString(10, claim.getKeyId());
            return put.executeUpdate();
        }
    }

    private static void assertCounts(JdbcDatabase db, int receipts) throws Exception {
        try (Connection connection = db.openConnection(); Statement stmt = connection.createStatement()) {
            try (ResultSet receiptCount = stmt.executeQuery(
                    "SELECT count(*) FROM provider_payment_postings")) {
                assertTrue(receiptCount.next());
                assertEquals(receipts, receiptCount.getInt(1));
            }
            try (ResultSet paymentCount = stmt.executeQuery(
                    "SELECT count(*) FROM payment_transactions")) {
                assertTrue(paymentCount.next());
                assertEquals(0, paymentCount.getInt(1),
                        "W34.1 must not alter real application payment ledger");
            }
        }
    }
}
