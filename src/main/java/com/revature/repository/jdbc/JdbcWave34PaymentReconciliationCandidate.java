package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Objects;

import com.revature.application.ProviderPaymentIdentity;
import com.revature.cardealer.PaymentTransaction;

/**
 * Wave34.2 REVIEW-ONLY local reconciliation candidate. It performs a real
 * atomic receipt/plan/ledger posting ONLY in isolated GitHub Actions PG16.
 *
 * The supplied ReceiptVerifier is an unapproved trust boundary: production
 * MUST replace it with independently operated provider authentication and
 * verify merchant account, event lifecycle, signature, key rotation and
 * authorizations before enabling the feature. No application entry point uses
 * this class. No production migration, payment network or real funds.
 */
public final class JdbcWave34PaymentReconciliationCandidate {
    @FunctionalInterface
    public interface ReceiptVerifier {
        void verify(ProviderPaymentIdentity untrustedReceipt) throws SecurityException;
    }

    public static final class Outcome {
        private final String localTransactionId;
        private final boolean replay;

        private Outcome(String localTransactionId, boolean replay) {
            this.localTransactionId = Objects.requireNonNull(localTransactionId);
            this.replay = replay;
        }

        public String getLocalTransactionId() { return localTransactionId; }
        public boolean isReplay() { return replay; }
    }

    private final JdbcDatabase database;
    private final ReceiptVerifier verifier;
    private final JdbcPaymentProcessor localPayments;
    private final String allowedCurrency;

    public JdbcWave34PaymentReconciliationCandidate(JdbcDatabase database,
            ReceiptVerifier verifier, String allowedCurrency) {
        requireDisposableExecution();
        this.database = Objects.requireNonNull(database, "database");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        if (allowedCurrency == null || !allowedCurrency.matches("[A-Z]{3}")) {
            throw new IllegalArgumentException("explicit synthetic currency required");
        }
        this.allowedCurrency = allowedCurrency;
        this.localPayments = new JdbcPaymentProcessor(database);
        this.database.requireCurrentSchema(); // deliberately not a privileged migration
    }

    /**
     * Retry *local* transactional work only; never resubmit an external charge.
     * Safe replay returns the first local payment ID after checking full digest.
     */
    public Outcome reconcile(ProviderPaymentIdentity untrustedReceipt) {
        requireDisposableExecution();
        ProviderPaymentIdentity receipt =
                Objects.requireNonNull(untrustedReceipt, "receipt");
        verifier.verify(receipt); // no local SQL until synthetic provider proof succeeds
        if (!allowedCurrency.equals(receipt.getCurrency())) {
            throw new SecurityException("unapproved currency mapping");
        }

        for (int attempt = 1; attempt <= 6; attempt++) {
            try (Connection connection = database.openConnection()) {
                requireDisposablePostgresql(connection);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                connection.setAutoCommit(false);
                try {
                    Outcome result = postOnce(connection, receipt);
                    connection.commit();
                    return result;
                } catch (SQLException exception) {
                    rollback(connection, exception);
                    if (retryable(exception) && attempt < 6) continue;
                    throw new IllegalStateException("local synthetic posting failed", exception);
                } catch (RuntimeException exception) {
                    rollback(connection, exception);
                    throw exception;
                }
            } catch (SQLException exception) {
                if (retryable(exception) && attempt < 6) continue;
                throw new IllegalStateException("disposable database unavailable", exception);
            }
        }
        throw new IllegalStateException("bounded local SQL retry budget exhausted");
    }

    private Outcome postOnce(Connection connection, ProviderPaymentIdentity receipt)
            throws SQLException {
        // Verify local ownership/contract BEFORE attempting receipt insertion;
        // missing principals must fail closed as security errors, not FK errors.
        // Bind external receipt to a pre-existing locally approved contract.
        try (PreparedStatement ownership = connection.prepareStatement(
                "SELECT owner_username,contract_id FROM owned_vehicles "
                + "WHERE ownership_id=? FOR UPDATE")) {
            ownership.setLong(1, receipt.getOwnershipId());
            try (ResultSet row = ownership.executeQuery()) {
                if (!row.next()
                        || !receipt.getCustomer().equals(row.getString(1))
                        || !receipt.getContractId().equals(row.getString(2))
                        || row.next()) {
                    throw new SecurityException("unmatched customer/ownership/contract");
                }
            }
        }

        int inserted;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO provider_payment_postings "
                + "(provider_code,provider_request_id,provider_transaction_id,"
                + "customer_name,ownership_id,contract_id,amount,currency_code,"
                + "verified_receipt_digest,signing_key_id) VALUES (?,?,?,?,?,?,?,?,?,?) "
                + "ON CONFLICT (provider_code,provider_request_id) DO NOTHING")) {
            insert.setString(1, receipt.getProvider());
            insert.setString(2, receipt.getRequestId());
            insert.setString(3, receipt.getTransactionId());
            insert.setString(4, receipt.getCustomer());
            insert.setLong(5, receipt.getOwnershipId());
            insert.setString(6, receipt.getContractId());
            insert.setInt(7, receipt.getAmount());
            insert.setString(8, receipt.getCurrency());
            insert.setString(9, receipt.getDigest());
            insert.setString(10, receipt.getKeyId());
            inserted = insert.executeUpdate();
        }
        if (inserted == 0) {
            try (PreparedStatement existing = connection.prepareStatement(
                    "SELECT provider_transaction_id,verified_receipt_digest,"
                    + "local_transaction_id,posted_at "
                    + "FROM provider_payment_postings "
                    + "WHERE provider_code=? AND provider_request_id=? FOR UPDATE")) {
                existing.setString(1, receipt.getProvider());
                existing.setString(2, receipt.getRequestId());
                try (ResultSet row = existing.executeQuery()) {
                    if (!row.next()
                            || !receipt.getTransactionId().equals(row.getString(1))
                            || !receipt.getDigest().equals(row.getString(2))
                            || row.getString(3) == null || row.getTimestamp(4) == null) {
                        throw new SecurityException("conflicting or incomplete provider replay");
                    }
                    String localId = row.getString(3);
                    if (row.next()) throw new SecurityException("multiple receipt bindings");
                    return new Outcome(localId, true);
                }
            }
        }

        // The existing ledger/plan update runs on the SAME Connection, with
        // zero internal commit; any subsequent failure rolls back ALL writes.
        PaymentTransaction payment = localPayments.recordWithinCandidateTransaction(
                connection, receipt.getCustomer(), receipt.getOwnershipId(),
                receipt.getAmount());

        try (PreparedStatement finish = connection.prepareStatement(
                "UPDATE provider_payment_postings SET local_transaction_id=?, "
                + "posted_at=? WHERE provider_code=? AND provider_request_id=? "
                + "AND local_transaction_id IS NULL")) {
            finish.setString(1, payment.getTransactionId());
            finish.setTimestamp(2, Timestamp.from(Instant.now()));
            finish.setString(3, receipt.getProvider());
            finish.setString(4, receipt.getRequestId());
            if (finish.executeUpdate() != 1) {
                throw new IllegalStateException("atomic receipt/ledger binding failed");
            }
        }
        return new Outcome(payment.getTransactionId(), false);
    }

    private static void requireDisposableExecution() {
        if (!"true".equalsIgnoreCase(System.getenv("CI"))
                || !"true".equalsIgnoreCase(System.getenv("GITHUB_ACTIONS"))
                || !"true".equalsIgnoreCase(System.getenv("W34_DISPOSABLE_ONLY"))
                || !"jdbc:postgresql://localhost:5432/dealership_ci".equals(
                        System.getenv("DEALERSHIP_PG_TEST_URL"))) {
            throw new SecurityException("Wave34.2 requires disposable GitHub Actions CI");
        }
    }

    private static void requireDisposablePostgresql(Connection connection)
            throws SQLException {
        String url = connection.getMetaData().getURL();
        if (!"PostgreSQL".equalsIgnoreCase(
                    connection.getMetaData().getDatabaseProductName())
                || url == null
                || !url.startsWith("jdbc:postgresql://localhost:5432/dealership_ci?currentSchema=w34_")) {
            throw new SecurityException("Wave34.2 must not access non-disposable database");
        }
    }

    private static void rollback(Connection connection, Exception root) {
        try {
            connection.rollback();
        } catch (SQLException failure) {
            root.addSuppressed(failure);
        }
    }

    private static boolean retryable(SQLException exception) {
        return "40001".equals(exception.getSQLState())
                || "40P01".equals(exception.getSQLState());
    }
}
