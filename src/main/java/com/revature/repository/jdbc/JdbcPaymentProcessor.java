package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.revature.application.PaymentProcessor;
import com.revature.cardealer.PaymentTransaction;

public final class JdbcPaymentProcessor implements PaymentProcessor {
    private final JdbcDatabase database;

    public JdbcPaymentProcessor(JdbcDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.ensureSchemaReady();
    }

    @Override
    public PaymentTransaction record(String customerName, long ownershipId, int amount) {
        String customer = requireText(customerName, "customerName");
        if (ownershipId <= 0) throw new IllegalArgumentException("ownership id must be positive");
        if (amount <= 0) throw new IllegalArgumentException("payment amount must be positive");
        return inTransaction(connection -> record(connection, customer, ownershipId, amount));
    }

    private PaymentTransaction record(Connection connection, String customer, long ownershipId, int amount) throws SQLException {
        List<PlanRow> plans = lockPlans(connection, customer);
        PlanRow selected = null;
        for (PlanRow plan : plans) if (plan.ownershipId == ownershipId) selected = plan;
        if (selected == null) throw new IllegalArgumentException("unknown ownership id: " + ownershipId);
        if (amount > selected.balance) throw new IllegalArgumentException("payment amount exceeds remaining balance");

        int originalObligation = 0;
        for (PlanRow plan : plans) originalObligation += plan.purchasePrice;
        LedgerState ledger = lockLedger(connection, customer, originalObligation);
        if (amount > ledger.remainingBalance) throw new IllegalArgumentException("payment amount exceeds customer remaining balance");

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE payment_plans SET amount_paid = ?, balance = ? WHERE ownership_id = ?")) {
            update.setInt(1, selected.amountPaid + amount);
            update.setInt(2, selected.balance - amount);
            update.setLong(3, selected.ownershipId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("payment plan update failed");
        }

        int cumulativePaid = ledger.totalPaid + amount;
        int resultingBalance = ledger.remainingBalance - amount;
        String transactionId = String.format("PAY-%06d", nextGlobalSequence(connection));
        Instant recordedAt = Instant.now();
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payment_transactions(transaction_id, customer_name, ownership_id, contract_id, amount, cumulative_paid, resulting_balance, recorded_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            insert.setString(1, transactionId); insert.setString(2, customer); insert.setLong(3, selected.ownershipId);
            insert.setString(4, selected.contractId); insert.setInt(5, amount); insert.setInt(6, cumulativePaid);
            insert.setInt(7, resultingBalance); insert.setTimestamp(8, Timestamp.from(recordedAt)); insert.executeUpdate();
        }
        return new PaymentTransaction(transactionId, customer, selected.ownershipId, selected.contractId,
                amount, cumulativePaid, resultingBalance, recordedAt);
    }

    private List<PlanRow> lockPlans(Connection connection, String customer) throws SQLException {
        String sql = "SELECT ov.ownership_id, ov.contract_id, pp.purchase_price, pp.amount_paid, pp.balance "
                + "FROM owned_vehicles ov JOIN payment_plans pp ON pp.ownership_id = ov.ownership_id "
                + "WHERE ov.owner_username = ? ORDER BY ov.ownership_id FOR UPDATE";
        List<PlanRow> plans = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customer);
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) plans.add(new PlanRow(result.getLong("ownership_id"), result.getString("contract_id"),
                        result.getInt("purchase_price"), result.getInt("amount_paid"), result.getInt("balance")));
            }
        }
        if (plans.isEmpty()) throw new IllegalArgumentException("customer has no owned vehicles");
        return plans;
    }

    private LedgerState lockLedger(Connection connection, String customer, int originalObligation) throws SQLException {
        String sql = "SELECT cumulative_paid, resulting_balance FROM payment_transactions WHERE customer_name = ? ORDER BY recorded_at DESC, transaction_id DESC LIMIT 1 FOR UPDATE";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, customer);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) return new LedgerState(0, originalObligation);
                int totalPaid = result.getInt("cumulative_paid");
                int remaining = result.getInt("resulting_balance");
                if (totalPaid + remaining != originalObligation) throw new IllegalStateException("payment ledger does not match current ownership obligation");
                return new LedgerState(totalPaid, remaining);
            }
        }
    }

    private long nextGlobalSequence(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT transaction_id FROM payment_transactions ORDER BY transaction_id DESC LIMIT 1 FOR UPDATE");
                ResultSet result = statement.executeQuery()) {
            return result.next() ? parseSequence(result.getString("transaction_id")) + 1 : 1;
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try { T result = work.execute(connection); connection.commit(); return result; }
            catch (SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new IllegalStateException("payment workflow failed", exception); }
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

    private interface SqlWork<T> { T execute(Connection connection) throws SQLException; }
    private static final class PlanRow {
        private final long ownershipId; private final String contractId; private final int purchasePrice; private final int amountPaid; private final int balance;
        private PlanRow(long ownershipId, String contractId, int purchasePrice, int amountPaid, int balance) {
            this.ownershipId = ownershipId; this.contractId = contractId; this.purchasePrice = purchasePrice; this.amountPaid = amountPaid; this.balance = balance;
        }
    }
    private static final class LedgerState {
        private final int totalPaid; private final int remainingBalance;
        private LedgerState(int totalPaid, int remainingBalance) { this.totalPaid = totalPaid; this.remainingBalance = remainingBalance; }
    }
}
