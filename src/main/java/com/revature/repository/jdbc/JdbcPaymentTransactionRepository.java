package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PaymentTransaction;
import com.revature.cardealer.Payments;
import com.revature.repository.PaymentTransactionRepository;

/** Durable, customer-scoped payment transaction repository. */
public final class JdbcPaymentTransactionRepository implements PaymentTransactionRepository {
    private final JdbcDatabase database;
    private final String customerName;

    public JdbcPaymentTransactionRepository(JdbcDatabase database, String customerName) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.ensureSchemaReady();
        this.customerName = requireText(customerName, "customerName");
        validateCustomer();
    }

    @Override
    public List<PaymentTransaction> findAll() {
        String sql = "SELECT transaction_id, customer_name, ownership_id, contract_id, amount, cumulative_paid, resulting_balance, recorded_at "
                + "FROM payment_transactions WHERE customer_name = ? ORDER BY recorded_at, transaction_id";
        List<PaymentTransaction> transactions = new ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customerName);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    long ownershipId = result.getLong("ownership_id");
                    Long ownership = result.wasNull() ? null : ownershipId;
                    transactions.add(new PaymentTransaction(result.getString("transaction_id"),
                            result.getString("customer_name"), ownership, result.getString("contract_id"),
                            result.getInt("amount"), result.getInt("cumulative_paid"),
                            result.getInt("resulting_balance"), result.getTimestamp("recorded_at").toInstant()));
                }
            }
            return Collections.unmodifiableList(transactions);
        } catch (SQLException exception) { throw failure("could not read payment transactions", exception); }
    }

    @Override public Payments ledger() { return Payments.restore(findAll()); }

    public PaymentTransaction recordPayment(int amount, int amountOwed) {
        if (amount <= 0) throw new IllegalArgumentException("payment amount must be positive");
        if (amountOwed < 0) throw new IllegalArgumentException("amount owed must not be negative");
        return inTransaction(connection -> {
            LedgerState state = lockLedgerState(connection);
            int priorPaid = state.totalPaid;
            int priorBalance = state.hasTransactions ? state.remainingBalance : amountOwed;
            int effectiveOwed = priorPaid + priorBalance;
            if (state.hasTransactions && amountOwed != effectiveOwed) throw new IllegalArgumentException("amount owed does not match the existing ledger");
            if (amount > priorBalance) throw new IllegalArgumentException("payment amount exceeds remaining balance");
            int totalPaid = priorPaid + amount;
            int remainingBalance = priorBalance - amount;
            String transactionId = String.format("PAY-%06d", state.nextSequence);
            Instant recordedAt = Instant.now();
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO payment_transactions(transaction_id, customer_name, ownership_id, contract_id, amount, cumulative_paid, resulting_balance, recorded_at) VALUES (?, ?, NULL, NULL, ?, ?, ?, ?)")) {
                statement.setString(1, transactionId); statement.setString(2, customerName); statement.setInt(3, amount);
                statement.setInt(4, totalPaid); statement.setInt(5, remainingBalance); statement.setTimestamp(6, Timestamp.from(recordedAt));
                statement.executeUpdate();
            }
            return new PaymentTransaction(transactionId, customerName, amount, totalPaid, remainingBalance, recordedAt);
        });
    }

    public void replaceAll(List<PaymentTransaction> replacement) {
        List<PaymentTransaction> validated = validateReplacement(replacement);
        inTransaction(connection -> {
            try (PreparedStatement delete = connection.prepareStatement("DELETE FROM payment_transactions WHERE customer_name = ?")) {
                delete.setString(1, customerName); delete.executeUpdate();
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO payment_transactions(transaction_id, customer_name, ownership_id, contract_id, amount, cumulative_paid, resulting_balance, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                for (PaymentTransaction transaction : validated) {
                    insert.setString(1, transaction.getTransactionId()); insert.setString(2, customerName);
                    if (transaction.getOwnershipId().isPresent()) insert.setLong(3, transaction.getOwnershipId().getAsLong());
                    else insert.setNull(3, java.sql.Types.INTEGER);
                    insert.setString(4, transaction.getContractId().orElse(null));
                    insert.setInt(5, transaction.getAmount()); insert.setInt(6, transaction.getTotalPaid());
                    insert.setInt(7, transaction.getRemainingBalance()); insert.setTimestamp(8, Timestamp.from(transaction.getRecordedAt()));
                    insert.addBatch();
                }
                insert.executeBatch();
            }
            return null;
        });
    }

    private List<PaymentTransaction> validateReplacement(List<PaymentTransaction> replacement) {
        Objects.requireNonNull(replacement, "replacement");
        List<PaymentTransaction> copy = new ArrayList<>();
        int expectedTotal = 0;
        Integer expectedOwed = null;
        long previousSequence = 0;
        for (PaymentTransaction transaction : replacement) {
            Objects.requireNonNull(transaction, "transaction");
            if (!customerName.equals(transaction.getCustomerName())) throw new IllegalArgumentException("transaction customer does not match repository customer");
            long sequence = parseSequence(transaction.getTransactionId());
            if (sequence <= previousSequence) throw new IllegalArgumentException("transaction identifiers must be strictly increasing");
            previousSequence = sequence;
            expectedTotal += transaction.getAmount();
            if (transaction.getTotalPaid() != expectedTotal) throw new IllegalArgumentException("cumulative paid amount is inconsistent");
            int owed = transaction.getTotalPaid() + transaction.getRemainingBalance();
            if (expectedOwed == null) expectedOwed = owed;
            if (expectedOwed != owed) throw new IllegalArgumentException("transaction balances are inconsistent");
            copy.add(transaction);
        }
        return copy;
    }

    private LedgerState lockLedgerState(Connection connection) throws SQLException {
        boolean hasTransactions = false;
        int totalPaid = 0;
        int remainingBalance = 0;
        String customerSql = "SELECT cumulative_paid, resulting_balance FROM payment_transactions WHERE customer_name = ? ORDER BY recorded_at DESC, transaction_id DESC LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(customerSql)) {
            statement.setString(1, customerName);
            try (ResultSet result = statement.executeQuery()) {
                if (result.next()) {
                    hasTransactions = true;
                    totalPaid = result.getInt("cumulative_paid");
                    remainingBalance = result.getInt("resulting_balance");
                }
            }
        }
        return new LedgerState(hasTransactions, totalPaid, remainingBalance, nextGlobalSequence(connection));
    }

    private long nextGlobalSequence(Connection connection) throws SQLException {
        String sql = "SELECT transaction_id FROM payment_transactions ORDER BY transaction_id DESC LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            return result.next() ? parseSequence(result.getString("transaction_id")) + 1 : 1;
        }
    }

    private void validateCustomer() {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM accounts WHERE username = ?")) {
            statement.setString(1, customerName);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("unknown customer: " + customerName);
                if (AccountRole.valueOf(result.getString("role")) != AccountRole.CUSTOMER) throw new IllegalArgumentException("payment ledger owner must have CUSTOMER role");
            }
        } catch (SQLException exception) { throw failure("could not validate payment customer", exception); }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try { T value = work.execute(connection); connection.commit(); return value; }
            catch (SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw failure("payment transaction failed", exception); }
    }

    private static long parseSequence(String transactionId) {
        if (transactionId == null || !transactionId.matches("PAY-\\d{6}")) throw new IllegalArgumentException("invalid payment transaction identifier: " + transactionId);
        return Long.parseLong(transactionId.substring(4));
    }

    private static String requireText(String value, String field) {
        String normalized = Objects.requireNonNull(value, field).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(field + " must not be blank");
        return normalized;
    }
    private static IllegalStateException failure(String message, SQLException exception) { return new IllegalStateException(message, exception); }
    private interface SqlWork<T> { T execute(Connection connection) throws SQLException; }

    private static final class LedgerState {
        private final boolean hasTransactions; private final int totalPaid; private final int remainingBalance; private final long nextSequence;
        private LedgerState(boolean hasTransactions, int totalPaid, int remainingBalance, long nextSequence) {
            this.hasTransactions = hasTransactions; this.totalPaid = totalPaid; this.remainingBalance = remainingBalance; this.nextSequence = nextSequence;
        }
    }
}
