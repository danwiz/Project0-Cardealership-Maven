package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import com.revature.cardealer.Car;
import com.revature.cardealer.OwnedVehicle;
import com.revature.cardealer.PaymentPlan;
import com.revature.repository.OwnershipRepository;

public final class JdbcOwnershipRepository implements OwnershipRepository {
    private final JdbcDatabase database;
    private final String ownerUsername;

    public JdbcOwnershipRepository(JdbcDatabase database, String ownerUsername) {
        this.database = Objects.requireNonNull(database, "database");
        this.ownerUsername = requireText(ownerUsername, "owner username");
        this.database.ensureSchemaReady();
        requireOwnerExists();
    }

    @Override public List<OwnedVehicle> findAll() { return read(null); }
    @Override public Optional<OwnedVehicle> findById(long ownershipId) {
        if (ownershipId <= 0) return Optional.empty();
        List<OwnedVehicle> found = read(Long.valueOf(ownershipId));
        return found.isEmpty() ? Optional.empty() : Optional.of(found.get(0));
    }

    private List<OwnedVehicle> read(Long ownershipId) {
        String sql = "SELECT ov.ownership_id, ov.contract_id, ov.make, ov.model, ov.vehicle_year, pp.purchase_price, pp.payment_months, pp.amount_paid "
                + "FROM owned_vehicles ov JOIN payment_plans pp ON pp.ownership_id = ov.ownership_id WHERE ov.owner_username = ?"
                + (ownershipId == null ? " ORDER BY ov.ownership_id" : " AND ov.ownership_id = ?");
        List<OwnedVehicle> vehicles = new ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, ownerUsername);
            if (ownershipId != null) statement.setLong(2, ownershipId.longValue());
            try (ResultSet result = statement.executeQuery()) {
                while (result.next()) {
                    PaymentPlan plan = new PaymentPlan(result.getInt("purchase_price"), result.getInt("payment_months"));
                    int paid = result.getInt("amount_paid");
                    if (paid > 0) plan.recordPayment(paid);
                    vehicles.add(new OwnedVehicle(result.getLong("ownership_id"), result.getString("contract_id"),
                            new Car(result.getString("make"), result.getString("model"), result.getInt("vehicle_year")), plan));
                }
            }
            return Collections.unmodifiableList(vehicles);
        } catch (SQLException exception) { throw new IllegalStateException("could not read ownership", exception); }
    }

    @Override public void add(Car car, int purchasePrice, int paymentMonths) { add(null, car, purchasePrice, paymentMonths); }

    @Override
    public void add(String contractId, Car car, int purchasePrice, int paymentMonths) {
        Objects.requireNonNull(car, "car");
        PaymentPlan plan = new PaymentPlan(purchasePrice, paymentMonths);
        inTransaction(connection -> { insert(connection, 0, contractId, car, plan); return null; });
    }

    @Override
    public void replaceAll(List<OwnedVehicle> vehicles) {
        List<OwnedVehicle> replacement = new ArrayList<>(Objects.requireNonNull(vehicles, "vehicles"));
        inTransaction(connection -> {
            deleteCurrent(connection);
            for (OwnedVehicle vehicle : replacement) {
                insert(connection, vehicle.getOwnershipId(), vehicle.getContractId().orElse(null),
                        vehicle.getVehicle(), vehicle.getPaymentPlan());
            }
            return null;
        });
    }

    private void insert(Connection connection, long requestedId, String contractId, Car car, PaymentPlan plan) throws SQLException {
        long id;
        String sql = requestedId > 0
                ? "INSERT INTO owned_vehicles(ownership_id, owner_username, contract_id, make, model, vehicle_year) VALUES (?, ?, ?, ?, ?, ?)"
                : "INSERT INTO owned_vehicles(owner_username, contract_id, make, model, vehicle_year) VALUES (?, ?, ?, ?, ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql,
                requestedId > 0 ? Statement.NO_GENERATED_KEYS : Statement.RETURN_GENERATED_KEYS)) {
            int i = 1;
            if (requestedId > 0) statement.setLong(i++, requestedId);
            statement.setString(i++, ownerUsername);
            statement.setString(i++, contractId);
            statement.setString(i++, requireText(car.getCarMake(), "make"));
            statement.setString(i++, requireText(car.getCarModel(), "model"));
            statement.setInt(i, car.getCarYear());
            statement.executeUpdate();
            if (requestedId > 0) id = requestedId;
            else try (ResultSet keys = statement.getGeneratedKeys()) { keys.next(); id = keys.getLong(1); }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payment_plans(ownership_id, purchase_price, payment_months, monthly_payment, amount_paid, balance) VALUES (?, ?, ?, ?, ?, ?)")) {
            statement.setLong(1, id); statement.setInt(2, plan.getPurchasePrice()); statement.setInt(3, plan.getTermMonths());
            statement.setInt(4, plan.getMonthlyPayment()); statement.setInt(5, plan.getAmountPaid()); statement.setInt(6, plan.getRemainingBalance());
            statement.executeUpdate();
        }
    }

    private void deleteCurrent(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "DELETE FROM payment_plans WHERE ownership_id IN (SELECT ownership_id FROM owned_vehicles WHERE owner_username = ?)")) {
            statement.setString(1, ownerUsername); statement.executeUpdate();
        }
        try (PreparedStatement statement = connection.prepareStatement("DELETE FROM owned_vehicles WHERE owner_username = ?")) {
            statement.setString(1, ownerUsername); statement.executeUpdate();
        }
    }

    private void requireOwnerExists() {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement("SELECT role FROM accounts WHERE username = ?")) {
            statement.setString(1, ownerUsername);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("unknown owner account: " + ownerUsername);
                if (!"CUSTOMER".equals(result.getString("role"))) throw new IllegalArgumentException("owner account must have CUSTOMER role");
            }
        } catch (SQLException exception) { throw new IllegalStateException("could not validate owner account", exception); }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try { T value = work.execute(connection); connection.commit(); return value; }
            catch (SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw new IllegalStateException("ownership transaction failed", exception); }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private interface SqlWork<T> { T execute(Connection connection) throws SQLException; }
}
