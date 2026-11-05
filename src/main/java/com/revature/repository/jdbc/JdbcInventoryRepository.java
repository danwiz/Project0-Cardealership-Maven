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

import com.revature.cardealer.Car;
import com.revature.cardealer.InventoryListing;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.PurchaseRequestStatus;
import com.revature.repository.InventoryRepository;

/** JDBC implementation of inventory listings and purchase-request state transitions. */
public final class JdbcInventoryRepository implements InventoryRepository {
    private final JdbcDatabase database;

    public JdbcInventoryRepository(JdbcDatabase database) {
        this.database = Objects.requireNonNull(database, "database");
        this.database.ensureSchemaReady();
    }

    @Override public int listingCount() { return listings().size(); }

    @Override
    public List<InventoryListing> listings() {
        String sql = "SELECT listing_id, make, model, vehicle_year, price, stock_quantity, active FROM inventory_listings ORDER BY listing_id";
        List<InventoryListing> listings = new ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                listings.add(new InventoryListing(result.getInt("listing_id"),
                        new Car(result.getString("make"), result.getString("model"), result.getInt("vehicle_year")),
                        result.getInt("price"), result.getInt("stock_quantity"), result.getBoolean("active")));
            }
            return Collections.unmodifiableList(listings);
        } catch (SQLException exception) { throw databaseFailure("could not read inventory listings", exception); }
    }

    @Override
    public List<PurchaseRequest> purchaseRequests() {
        String sql = "SELECT pr.request_id, pr.listing_id, pr.customer_name, pr.status, pr.payment_months, pr.contract_id, il.price "
                + "FROM purchase_requests pr JOIN inventory_listings il ON il.listing_id = pr.listing_id ORDER BY pr.request_id";
        List<PurchaseRequest> requests = new ArrayList<>();
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery()) {
            while (result.next()) {
                PurchaseRequest request = new PurchaseRequest(result.getInt("request_id"), result.getInt("listing_id"), result.getString("customer_name"));
                PurchaseRequestStatus status = PurchaseRequestStatus.valueOf(result.getString("status"));
                if (status == PurchaseRequestStatus.ACCEPTED) {
                    String contractId = result.getString("contract_id");
                    request.accept(result.getInt("price"), result.getInt("payment_months"),
                            contractId == null ? PurchaseRequest.contractIdFor(request.getId()) : contractId);
                } else if (status == PurchaseRequestStatus.REJECTED) request.reject();
                requests.add(request);
            }
            return Collections.unmodifiableList(requests);
        } catch (SQLException exception) { throw databaseFailure("could not read purchase requests", exception); }
    }

    @Override
    public void addListing(String make, String model, int year, int price, int stockQuantity) {
        String normalizedMake = requireText(make, "make");
        String normalizedModel = requireText(model, "model");
        if (price < 0) throw new IllegalArgumentException("price must not be negative");
        if (stockQuantity < 0) throw new IllegalArgumentException("stock quantity must not be negative");
        inTransaction(connection -> {
            int id = nextIdentifier(connection, "inventory_listings", "listing_id");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO inventory_listings(listing_id, make, model, vehicle_year, price, stock_quantity, active) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
                statement.setInt(1, id); statement.setString(2, normalizedMake); statement.setString(3, normalizedModel);
                statement.setInt(4, year); statement.setInt(5, price); statement.setInt(6, stockQuantity); statement.setBoolean(7, stockQuantity > 0);
                statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public void removeListing(int listingId) {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE inventory_listings SET active = FALSE WHERE listing_id = ?")) {
            statement.setInt(1, listingId);
            if (statement.executeUpdate() == 0) throw new IllegalArgumentException("unknown listing number: " + listingId);
        } catch (SQLException exception) { throw databaseFailure("could not remove inventory listing", exception); }
    }

    @Override
    public void requestPurchase(String customerName, int listingId) {
        String customer = requireText(customerName, "customer name");
        inTransaction(connection -> {
            ListingRow listing = lockListing(connection, listingId);
            if (!listing.active || listing.stockQuantity <= 0) throw new IllegalStateException("listing is not available");
            int requestId = nextIdentifier(connection, "purchase_requests", "request_id");
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO purchase_requests(request_id, listing_id, customer_name, status, payment_months, monthly_payment, contract_id) VALUES (?, ?, ?, 'PENDING', 0, 0, NULL)")) {
                statement.setInt(1, requestId); statement.setInt(2, listingId); statement.setString(3, customer); statement.executeUpdate();
            }
            return null;
        });
    }

    @Override
    public int decideRequest(int requestId, int paymentMonths, boolean accepted) {
        if (accepted && paymentMonths <= 0) throw new IllegalArgumentException("payment months must be positive");
        return inTransaction(connection -> {
            RequestRow request = lockRequest(connection, requestId);
            if (!"PENDING".equals(request.status)) throw new IllegalStateException("purchase request has already been decided");
            ListingRow listing = lockListing(connection, request.listingId);
            if (accepted) {
                if (!listing.active || listing.stockQuantity <= 0) throw new IllegalStateException("listing is not available");
                int remaining = listing.stockQuantity - 1;
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE inventory_listings SET stock_quantity = ?, active = ? WHERE listing_id = ?")) {
                    statement.setInt(1, remaining); statement.setBoolean(2, remaining > 0); statement.setInt(3, request.listingId); statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE purchase_requests SET status = 'ACCEPTED', payment_months = ?, monthly_payment = ?, contract_id = ? WHERE request_id = ? AND status = 'PENDING'")) {
                    statement.setInt(1, paymentMonths); statement.setInt(2, listing.price / paymentMonths);
                    statement.setString(3, PurchaseRequest.contractIdFor(requestId)); statement.setInt(4, requestId);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("purchase request decision conflict");
                }
            } else {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE purchase_requests SET status = 'REJECTED' WHERE request_id = ? AND status = 'PENDING'")) {
                    statement.setInt(1, requestId);
                    if (statement.executeUpdate() != 1) throw new IllegalStateException("purchase request decision conflict");
                }
            }
            return listing.price;
        });
    }

    @Override
    public void rejectAllPendingRequests() {
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE purchase_requests SET status = 'REJECTED' WHERE status = 'PENDING'")) {
            statement.executeUpdate();
        } catch (SQLException exception) { throw databaseFailure("could not reject pending purchase requests", exception); }
    }

    @Override
    public Car carForListing(int listingId) {
        String sql = "SELECT make, model, vehicle_year FROM inventory_listings WHERE listing_id = ?";
        try (Connection connection = database.openConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("unknown listing number: " + listingId);
                return new Car(result.getString("make"), result.getString("model"), result.getInt("vehicle_year"));
            }
        } catch (SQLException exception) { throw databaseFailure("could not read listing vehicle", exception); }
    }

    private ListingRow lockListing(Connection connection, int listingId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT listing_id, price, stock_quantity, active FROM inventory_listings WHERE listing_id = ? FOR UPDATE")) {
            statement.setInt(1, listingId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("unknown listing number: " + listingId);
                return new ListingRow(result.getInt("price"), result.getInt("stock_quantity"), result.getBoolean("active"));
            }
        }
    }

    private RequestRow lockRequest(Connection connection, int requestId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT listing_id, status FROM purchase_requests WHERE request_id = ? FOR UPDATE")) {
            statement.setInt(1, requestId);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) throw new IllegalArgumentException("unknown purchase request number: " + requestId);
                return new RequestRow(result.getInt("listing_id"), result.getString("status"));
            }
        }
    }

    private static int nextIdentifier(Connection connection, String table, String column) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery(
                "SELECT COALESCE(MAX(" + column + "), -1) + 1 FROM " + table)) {
            result.next(); return result.getInt(1);
        }
    }

    private <T> T inTransaction(SqlWork<T> work) {
        try (Connection connection = database.openConnection()) {
            connection.setAutoCommit(false); connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
            try { T value = work.execute(connection); connection.commit(); return value; }
            catch (SQLException | RuntimeException exception) { connection.rollback(); throw exception; }
        } catch (SQLException exception) { throw databaseFailure("inventory transaction failed", exception); }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }

    private static IllegalStateException databaseFailure(String message, SQLException exception) { return new IllegalStateException(message, exception); }
    private interface SqlWork<T> { T execute(Connection connection) throws SQLException; }

    private static final class ListingRow {
        private final int price; private final int stockQuantity; private final boolean active;
        private ListingRow(int price, int stockQuantity, boolean active) { this.price = price; this.stockQuantity = stockQuantity; this.active = active; }
    }
    private static final class RequestRow {
        private final int listingId; private final String status;
        private RequestRow(int listingId, String status) { this.listingId = listingId; this.status = status; }
    }
}
