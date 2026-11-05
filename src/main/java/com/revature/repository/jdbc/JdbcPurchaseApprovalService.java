package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

import com.revature.application.ApplicationPorts;
import com.revature.cardealer.AccountRole;
import com.revature.cardealer.PurchaseRequest;
import com.revature.cardealer.User;
import com.revature.service.Permission;
import com.revature.service.RoleAuthorizationService;

/**
 * A single serializable JDBC transaction for purchase decision, stock,
 * ownership and payment-plan creation. The application command must first
 * establish verified-session authorization; this layer also rechecks
 * persisted reviewer and customer identity while holding database locks.
 */
public final class JdbcPurchaseApprovalService implements ApplicationPorts.PurchaseApproval {
    private final JdbcDatabase database;
    private final RoleAuthorizationService authorization;
    private final FailureHook failureHook;
    // One initial attempt plus two conflict-only retries; never retry arbitrary SQL errors.
    static final int MAX_TRANSACTION_ATTEMPTS = 3;

    enum Stage { AFTER_STOCK, AFTER_REQUEST, AFTER_OWNERSHIP, AFTER_PLAN, AFTER_REJECTIONS }
    @FunctionalInterface interface FailureHook { void after(Stage stage) throws SQLException; }

    public JdbcPurchaseApprovalService(JdbcDatabase database, RoleAuthorizationService authorization) {
        this(database, authorization, stage -> { });
    }

    /** Test-only fault injection contract; never expose to the console. */
    JdbcPurchaseApprovalService(JdbcDatabase database,
            RoleAuthorizationService authorization, FailureHook failureHook) {
        this.database = Objects.requireNonNull(database, "database");
        this.authorization = Objects.requireNonNull(authorization, "authorization");
        if (!this.authorization.enforcesVerifiedSessions()) {
            throw new SecurityException("JDBC purchase approval requires a verified-session policy");
        }
        this.failureHook = Objects.requireNonNull(failureHook, "failureHook");
        database.ensureSchemaReady();
    }

    @Override
    public int approve(User reviewer, int requestId, int paymentMonths, boolean rejectOtherPending) {
        if (requestId < 0) throw new IllegalArgumentException("request id must not be negative");
        if (paymentMonths <= 0) throw new IllegalArgumentException("payment months must be positive");
        if (reviewer == null || reviewer.getUsername() == null
                || reviewer.getRole() == null || reviewer.getPassword() == null
                || reviewer.getSessionRevision() == null) {
            throw new SecurityException("verified reviewer identity required");
        }

        for (int attempt = 1; attempt <= MAX_TRANSACTION_ATTEMPTS; attempt++) {
            // Each attempt must independently revalidate the live verified session.
            // Do not retain an authorization decision through a transaction retry.
            authorization.requireAuthorized(reviewer, Permission.REVIEW_PURCHASE_REQUESTS);
            try (Connection connection = database.openConnection()) {
                connection.setAutoCommit(false);
                connection.setTransactionIsolation(Connection.TRANSACTION_SERIALIZABLE);
                try {
                    int price = approveInTransaction(
                            connection, reviewer, requestId, paymentMonths, rejectOtherPending);
                    connection.commit();
                    return price;
                } catch (SQLException | RuntimeException failure) {
                    try {
                        connection.rollback();
                    } catch (SQLException rollbackFailure) {
                        failure.addSuppressed(rollbackFailure);
                    }
                    throw failure;
                }
            } catch (SQLException failure) {
                // 40001 and 40P01 denote server-aborted serialization/deadlock
                // transactions. Reexecute the *whole* unit of work, with the
                // exact same durable request ID and terms, at most twice more.
                if (isRetryableTransactionConflict(failure)) {
                    // A failed rollback or connection close makes the result
                    // ambiguous even if the *original* SQLSTATE was retryable.
                    // Never start a second attempt on an uncertain connection.
                    if (failure.getSuppressed().length != 0) {
                        throw new IllegalStateException(
                                "JDBC transaction rollback or close uncertain; reconcile same request ID",
                                failure);
                    }
                    if (attempt < MAX_TRANSACTION_ATTEMPTS) continue;
                    throw new IllegalStateException(
                            "atomic JDBC purchase conflict after bounded retries", failure);
                }
                // A broken connection/unknown SQL state may have lost the reply
                // *after* COMMIT; never automatically assume rollback or create
                // another request. Operators must reconcile by request/contract ID.
                if (isUncertainConnectionFailure(failure)) {
                    throw new IllegalStateException(
                            "JDBC purchase outcome uncertain; reconcile the same request ID before retry",
                            failure);
                }
                throw new IllegalStateException("atomic JDBC purchase approval failed", failure);
            }
        }
        throw new AssertionError("unreachable JDBC transaction retry state");
    }

    private static boolean isRetryableTransactionConflict(SQLException failure) {
        return "40001".equals(failure.getSQLState()) || "40P01".equals(failure.getSQLState());
    }

    private static boolean isUncertainConnectionFailure(SQLException failure) {
        String state = failure.getSQLState();
        // 40003 is SQL standard/PostgreSQL "statement_completion_unknown".
        return state == null || state.startsWith("08") || "40003".equals(state);
    }

    private int approveInTransaction(Connection connection, User reviewer,
            int requestId, int months, boolean rejectOthers) throws SQLException {
        // Revalidate persisted privilege under the same transaction that writes.
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role, encoded_credential, session_revision FROM accounts WHERE username = ? FOR UPDATE")) {
            statement.setString(1, reviewer.getUsername());
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !Objects.equals(reviewer.getPassword(), row.getString("encoded_credential"))
                        || !Objects.equals(reviewer.getRole().name(), row.getString("role"))
                        || !Objects.equals(reviewer.getSessionRevision(), row.getString("session_revision"))
                        || (reviewer.getRole() != AccountRole.EMPLOYEE
                            && reviewer.getRole() != AccountRole.ADMINISTRATOR)) {
                    throw new SecurityException("reviewer no longer has persisted approval authority");
                }
            }
        }

        final int listingId;
        final String customer;
        final String status;
        final int existingMonths;
        final String storedContract;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT listing_id, customer_name, status, payment_months, contract_id "
                + "FROM purchase_requests WHERE request_id = ? FOR UPDATE")) {
            statement.setInt(1, requestId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("unknown purchase request id");
                listingId = row.getInt("listing_id");
                customer = row.getString("customer_name");
                status = row.getString("status");
                existingMonths = row.getInt("payment_months");
                storedContract = row.getString("contract_id");
            }
        }
        String contractId = PurchaseRequest.contractIdFor(requestId);
        if ("ACCEPTED".equals(status)) {
            // Replaying a completed request is idempotent only if the ownership
            // and payment plan actually exist with matching immutable terms.
            if (existingMonths != months || !contractId.equals(storedContract)) {
                throw new IllegalStateException("approved purchase terms conflict");
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT ov.ownership_id, pp.purchase_price, pp.payment_months "
                    + "FROM owned_vehicles ov JOIN payment_plans pp ON pp.ownership_id = ov.ownership_id "
                    + "WHERE ov.contract_id = ? AND ov.owner_username = ?")) {
                statement.setString(1, contractId);
                statement.setString(2, customer);
                try (ResultSet row = statement.executeQuery()) {
                    if (!row.next() || row.getInt("payment_months") != months) {
                        throw new IllegalStateException("approved request lacks matching durable ownership");
                    }
                    return row.getInt("purchase_price");
                }
            }
        }
        if (!"PENDING".equals(status)) throw new IllegalStateException("request was already rejected");

        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT role FROM accounts WHERE username = ? FOR UPDATE")) {
            statement.setString(1, customer);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next() || !"CUSTOMER".equals(row.getString("role"))) {
                    throw new SecurityException("purchase requires a persisted customer");
                }
            }
        }

        final int price;
        final int stock;
        final String make;
        final String model;
        final int year;
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT price, stock_quantity, active, make, model, vehicle_year "
                + "FROM inventory_listings WHERE listing_id = ? FOR UPDATE")) {
            statement.setInt(1, listingId);
            try (ResultSet row = statement.executeQuery()) {
                if (!row.next()) throw new IllegalArgumentException("unknown listing id");
                if (!row.getBoolean("active") || row.getInt("stock_quantity") <= 0) {
                    throw new IllegalStateException("listing is no longer available");
                }
                price = row.getInt("price");
                stock = row.getInt("stock_quantity");
                make = row.getString("make");
                model = row.getString("model");
                year = row.getInt("vehicle_year");
            }
        }

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE inventory_listings SET stock_quantity = ?, active = ? WHERE listing_id = ?")) {
            update.setInt(1, stock - 1);
            update.setBoolean(2, stock - 1 > 0);
            update.setInt(3, listingId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("inventory update conflict");
        }
        failureHook.after(Stage.AFTER_STOCK);

        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE purchase_requests SET status = 'ACCEPTED', payment_months = ?, "
                + "monthly_payment = ?, contract_id = ? WHERE request_id = ? AND status = 'PENDING'")) {
            update.setInt(1, months);
            update.setInt(2, price / months);
            update.setString(3, contractId);
            update.setInt(4, requestId);
            if (update.executeUpdate() != 1) throw new IllegalStateException("request approval conflict");
        }
        failureHook.after(Stage.AFTER_REQUEST);

        final long ownershipId;
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO owned_vehicles(owner_username, contract_id, make, model, vehicle_year) "
                + "VALUES (?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS)) {
            insert.setString(1, customer);
            insert.setString(2, contractId);
            insert.setString(3, make);
            insert.setString(4, model);
            insert.setInt(5, year);
            if (insert.executeUpdate() != 1) throw new IllegalStateException("ownership insert failed");
            try (ResultSet keys = insert.getGeneratedKeys()) {
                if (!keys.next()) throw new IllegalStateException("ownership key unavailable");
                ownershipId = keys.getLong(1);
            }
        }
        failureHook.after(Stage.AFTER_OWNERSHIP);

        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO payment_plans(ownership_id, purchase_price, payment_months, "
                + "monthly_payment, amount_paid, balance) VALUES (?, ?, ?, ?, 0, ?)")) {
            insert.setLong(1, ownershipId);
            insert.setInt(2, price);
            insert.setInt(3, months);
            insert.setInt(4, price / months);
            insert.setInt(5, price);
            if (insert.executeUpdate() != 1) throw new IllegalStateException("payment-plan insert failed");
        }
        failureHook.after(Stage.AFTER_PLAN);

        if (rejectOthers) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE purchase_requests SET status = 'REJECTED' WHERE status = 'PENDING'")) {
                update.executeUpdate();
            }
            failureHook.after(Stage.AFTER_REJECTIONS);
        }
        return price;
    }
}
