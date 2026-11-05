package com.revature.repository.jdbc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.revature.cardealer.PaymentTransaction;

class OwnershipLinkedPaymentTransactionTest {

    @Test
    void migrationAddsOwnershipReferenceAndNewPaymentsPersistIt() throws Exception {
        JdbcDatabase database = JdbcDatabase.inMemory("ownership_link_" + System.nanoTime());
        database.migrate();
        assertEquals(JdbcDatabase.CURRENT_SCHEMA_VERSION, database.schemaVersion());

        long ownershipId;
        try (Connection connection = database.openConnection()) {
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("INSERT INTO accounts(username, encoded_credential, role) VALUES ('alice', 'encoded', 'CUSTOMER')");
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO owned_vehicles(owner_username, make, model, vehicle_year) VALUES ('alice', 'Honda', 'Accord', 2020)",
                    Statement.RETURN_GENERATED_KEYS)) {
                insert.executeUpdate();
                try (ResultSet keys = insert.getGeneratedKeys()) { keys.next(); ownershipId = keys.getLong(1); }
            }
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO payment_plans(ownership_id, purchase_price, payment_months, monthly_payment, amount_paid, balance) VALUES (?, 10000, 10, 1000, 0, 10000)")) {
                insert.setLong(1, ownershipId); insert.executeUpdate();
            }
        }

        PaymentTransaction transaction = new JdbcPaymentProcessor(database).record("alice", ownershipId, 750);
        assertTrue(transaction.getOwnershipId().isPresent());
        assertEquals(ownershipId, transaction.getOwnershipId().getAsLong());

        List<PaymentTransaction> restored = new JdbcPaymentTransactionRepository(database, "alice").findAll();
        assertEquals(1, restored.size());
        assertEquals(ownershipId, restored.get(0).getOwnershipId().getAsLong());

        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT ownership_id FROM payment_transactions WHERE transaction_id = ?")) {
            statement.setString(1, transaction.getTransactionId());
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                assertEquals(ownershipId, result.getLong(1));
            }
        }
    }

    @Test
    void migratedHistoricalTransactionWithoutOwnershipRemainsReadable() throws Exception {
        JdbcDatabase database = JdbcDatabase.inMemory("historical_payment_" + System.nanoTime());
        database.migrate();
        try (Connection connection = database.openConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("INSERT INTO accounts(username, encoded_credential, role) VALUES ('alice', 'encoded', 'CUSTOMER')");
        }
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payment_transactions(transaction_id, customer_name, ownership_id, amount, cumulative_paid, resulting_balance, recorded_at) VALUES (?, ?, NULL, ?, ?, ?, ?)")) {
            statement.setString(1, "PAY-000001"); statement.setString(2, "alice"); statement.setInt(3, 100);
            statement.setInt(4, 100); statement.setInt(5, 900); statement.setTimestamp(6, Timestamp.from(Instant.now()));
            statement.executeUpdate();
        }

        PaymentTransaction restored = new JdbcPaymentTransactionRepository(database, "alice").findAll().get(0);
        assertFalse(restored.getOwnershipId().isPresent());
        assertFalse(restored.getContractId().isPresent());
    }
}
