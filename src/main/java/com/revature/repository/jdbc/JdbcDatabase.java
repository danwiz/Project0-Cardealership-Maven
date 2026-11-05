package com.revature.repository.jdbc;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;

/** Owns JDBC connection creation and applies ordered schema migrations. */
public final class JdbcDatabase {
    public static final int CURRENT_SCHEMA_VERSION = 5;

    private final String url;
    private final String username;
    private final String password;
    private final JdbcCredentialSource credentialSource;

    public JdbcDatabase(String url) { this(url, "sa", ""); }

    public JdbcDatabase(String url, String username, String password) {
        this.url = requireText(url, "url");
        this.username = Objects.requireNonNull(username, "username");
        this.password = Objects.requireNonNull(password, "password");
        this.credentialSource = null;
    }

    /**
     * For an isolated writer with externally delivered and rotated credentials.
     * No secret is retained by this database object or used as a fallback.
     */
    public JdbcDatabase(String url, JdbcCredentialSource credentialSource) {
        this.url = requireText(url, "url");
        this.username = null;
        this.password = null;
        this.credentialSource = Objects.requireNonNull(credentialSource, "credentialSource");
    }

    public static JdbcDatabase inMemory(String name) {
        return new JdbcDatabase("jdbc:h2:mem:" + requireText(name, "name")
                + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE");
    }

    /** H2 is used only for isolated test/local stores; persistent SQL uses owner-controlled triggers. */
    boolean isH2() { return url.regionMatches(true, 0, "jdbc:h2:", 0, "jdbc:h2:".length()); }

    public Connection openConnection() throws SQLException {
        if (credentialSource == null) {
            return DriverManager.getConnection(url, username, password);
        }
        JdbcCredentialSource.CredentialLease supplied = credentialSource.acquire();
        if (supplied == null) {
            throw new SecurityException("database credential source unavailable");
        }
        try (JdbcCredentialSource.CredentialLease lease = supplied) {
            return DriverManager.getConnection(url, lease.username(), lease.passwordForDriver());
        }
    }

    public void migrate() {
        try (Connection connection = openConnection()) {
            connection.setAutoCommit(false);
            try {
                createHistoryTable(connection);
                int version = currentVersion(connection);
                if (version < 1) {
                    applyVersionOne(connection);
                    recordVersion(connection, 1, "initial dealership schema");
                    version = 1;
                }
                if (version < 2) {
                    applyVersionTwo(connection);
                    recordVersion(connection, 2, "payment ownership attribution");
                    version = 2;
                }
                if (version < 3) {
                    applyVersionThree(connection);
                    recordVersion(connection, 3, "acquisition contract lifecycle");
                    version = 3;
                }
                if (version < 4) {
                    applyVersionFour(connection);
                    recordVersion(connection, 4, "one-time administrator bootstrap audit");
                    version = 4;
                }
                if (version < 5) {
                    applyVersionFive(connection);
                    recordVersion(connection, 5, "durable account session revocation generation");
                }
                if (currentVersion(connection) != CURRENT_SCHEMA_VERSION) throw new IllegalStateException("unsupported schema version");
                connection.commit();
            } catch (SQLException | RuntimeException exception) {
                connection.rollback();
                throw exception;
            }
        } catch (SQLException exception) {
            throw new IllegalStateException("database migration failed", exception);
        }
    }

    /**
     * Prepares only transient H2 test stores automatically. Persistent databases
     * must already be migrated by a separately authorized operator.
     */
    public void ensureSchemaReady() {
        if (url.regionMatches(true, 0, "jdbc:h2:mem:", 0, "jdbc:h2:mem:".length())) {
            migrate();
        } else {
            requireCurrentSchema();
        }
    }

    /**
     * Read-only startup gate: does not create schema history or execute DDL.
     * The runtime database principal needs only SELECT on schema_history here.
     */
    public void requireCurrentSchema() {
        int version = schemaVersion();
        if (version != CURRENT_SCHEMA_VERSION) {
            throw new IllegalStateException("database schema version " + version
                    + " is not current; run the privileged database migrator first");
        }
    }

    public int schemaVersion() {
        try (Connection connection = openConnection()) {
            return currentVersion(connection);
        } catch (SQLException exception) {
            throw new IllegalStateException(
                    "could not read schema version; persistent databases must be migrated before runtime startup",
                    exception);
        }
    }

    private static void createHistoryTable(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE IF NOT EXISTS schema_history (version INTEGER PRIMARY KEY, description VARCHAR(200) NOT NULL, installed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    private static int currentVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet result = statement.executeQuery("SELECT COALESCE(MAX(version), 0) FROM schema_history")) {
            result.next();
            return result.getInt(1);
        }
    }

    private static void recordVersion(Connection connection, int version, String description) throws SQLException {
        execute(connection, "INSERT INTO schema_history(version, description) VALUES (" + version + ", '" + description.replace("'", "''") + "')");
    }

    private static void applyVersionOne(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE accounts (username VARCHAR(100) PRIMARY KEY, encoded_credential VARCHAR(500) NOT NULL, role VARCHAR(30) NOT NULL)");
        execute(connection, "CREATE TABLE inventory_listings (listing_id INTEGER PRIMARY KEY, make VARCHAR(100) NOT NULL, model VARCHAR(100) NOT NULL, vehicle_year INTEGER NOT NULL, price INTEGER NOT NULL CHECK (price >= 0), stock_quantity INTEGER NOT NULL CHECK (stock_quantity >= 0), active BOOLEAN NOT NULL)");
        execute(connection, "CREATE TABLE purchase_requests (request_id INTEGER PRIMARY KEY, listing_id INTEGER NOT NULL REFERENCES inventory_listings(listing_id), customer_name VARCHAR(100) NOT NULL REFERENCES accounts(username), status VARCHAR(30) NOT NULL, payment_months INTEGER NOT NULL DEFAULT 0, monthly_payment INTEGER NOT NULL DEFAULT 0)");
        execute(connection, "CREATE TABLE owned_vehicles (ownership_id INTEGER GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY, owner_username VARCHAR(100) NOT NULL REFERENCES accounts(username), make VARCHAR(100) NOT NULL, model VARCHAR(100) NOT NULL, vehicle_year INTEGER NOT NULL)");
        execute(connection, "CREATE TABLE payment_plans (ownership_id INTEGER PRIMARY KEY REFERENCES owned_vehicles(ownership_id), purchase_price INTEGER NOT NULL CHECK (purchase_price >= 0), payment_months INTEGER NOT NULL CHECK (payment_months > 0), monthly_payment INTEGER NOT NULL CHECK (monthly_payment >= 0), amount_paid INTEGER NOT NULL DEFAULT 0, balance INTEGER NOT NULL CHECK (balance >= 0))");
        execute(connection, "CREATE TABLE payment_transactions (transaction_id VARCHAR(40) PRIMARY KEY, customer_name VARCHAR(100) NOT NULL REFERENCES accounts(username), amount INTEGER NOT NULL CHECK (amount > 0), cumulative_paid INTEGER NOT NULL CHECK (cumulative_paid >= 0), resulting_balance INTEGER NOT NULL CHECK (resulting_balance >= 0), recorded_at TIMESTAMP NOT NULL)");
        execute(connection, "CREATE INDEX idx_purchase_requests_listing ON purchase_requests(listing_id)");
        execute(connection, "CREATE INDEX idx_owned_vehicles_owner ON owned_vehicles(owner_username)");
        execute(connection, "CREATE INDEX idx_payment_transactions_customer ON payment_transactions(customer_name)");
    }

    private static void applyVersionTwo(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE payment_transactions ADD COLUMN ownership_id INTEGER");
        execute(connection, "ALTER TABLE payment_transactions ADD CONSTRAINT fk_payment_ownership FOREIGN KEY (ownership_id) REFERENCES owned_vehicles(ownership_id)");
        execute(connection, "CREATE INDEX idx_payment_transactions_ownership ON payment_transactions(ownership_id)");
    }

    private static void applyVersionThree(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE purchase_requests ADD COLUMN contract_id VARCHAR(20)");
        execute(connection, "ALTER TABLE owned_vehicles ADD COLUMN contract_id VARCHAR(20)");
        execute(connection, "ALTER TABLE payment_transactions ADD COLUMN contract_id VARCHAR(20)");
        execute(connection, "ALTER TABLE purchase_requests ADD CONSTRAINT uq_purchase_contract UNIQUE (contract_id)");
        execute(connection, "ALTER TABLE owned_vehicles ADD CONSTRAINT uq_ownership_contract UNIQUE (contract_id)");
        execute(connection, "CREATE INDEX idx_payment_transactions_contract ON payment_transactions(contract_id)");
    }

    private static void applyVersionFour(Connection connection) throws SQLException {
        execute(connection, "CREATE TABLE administrator_bootstrap_state (" +
                "bootstrap_id INTEGER PRIMARY KEY CHECK (bootstrap_id = 1), " +
                "initial_admin_username VARCHAR(100) NOT NULL, " +
                "completed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP)");
    }

    /** Each SQL-created account gets a fresh durable generation, including bootstrap/legacy inserts. */
    private static void applyVersionFive(Connection connection) throws SQLException {
        String database = connection.getMetaData().getDatabaseProductName();
        if ("H2".equalsIgnoreCase(database)) {
            execute(connection, "ALTER TABLE accounts ADD COLUMN session_revision UUID NOT NULL DEFAULT RANDOM_UUID()");
        } else if ("PostgreSQL".equalsIgnoreCase(database)) {
            execute(connection, "ALTER TABLE accounts ADD COLUMN session_revision UUID NOT NULL DEFAULT gen_random_uuid()");
            // Owner-held BEFORE trigger advances revocation even for existing
            // restricted writers that can update ONLY encoded_credential.
            execute(connection, "CREATE FUNCTION rotate_account_session_revision() RETURNS trigger "
                    + "LANGUAGE plpgsql SECURITY DEFINER SET search_path=pg_catalog AS $revision$ "
                    + "BEGIN IF OLD.role IS DISTINCT FROM NEW.role "
                    + "OR OLD.encoded_credential IS DISTINCT FROM NEW.encoded_credential "
                    + "THEN NEW.session_revision := pg_catalog.gen_random_uuid(); END IF; "
                    + "RETURN NEW; END; $revision$");
            execute(connection, "REVOKE ALL ON FUNCTION rotate_account_session_revision() FROM PUBLIC");
            execute(connection, "CREATE TRIGGER account_session_revision_rotation "
                    + "BEFORE UPDATE OF role, encoded_credential ON accounts "
                    + "FOR EACH ROW EXECUTE FUNCTION rotate_account_session_revision()");
        } else {
            throw new SQLException("session generation migration supports only H2 and PostgreSQL");
        }
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) { statement.execute(sql); }
    }

    private static String requireText(String value, String name) {
        String normalized = Objects.requireNonNull(value, name).trim();
        if (normalized.isEmpty()) throw new IllegalArgumentException(name + " must not be blank");
        return normalized;
    }
}
