-- W34.13 DISPOSABLE PostgreSQL16 fake-provider-state recovery fixture ONLY.
-- NOT an authorized migration, NOT an external merchant integration, NOT live money.
-- JUnit applies this DDL to a fresh random w3413_* schema after v5 bootstrap.
-- Verified receipts are checked by in-process test keys, NOT an independent
-- merchant trust authority. CI connection owns database and can bypass checks.
CREATE TABLE w3413_provider_captures (
    provider_name VARCHAR(64) NOT NULL,
    merchant_id VARCHAR(64) NOT NULL,
    provider_transaction_id VARCHAR(128) NOT NULL,
    client_request_id VARCHAR(128) NOT NULL,
    receipt_digest CHAR(64) NOT NULL,
    customer_name VARCHAR(128) NOT NULL,
    ownership_id BIGINT NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor > 0),
    currency CHAR(3) NOT NULL,
    status VARCHAR(16) NOT NULL CHECK(status='CAPTURED'),
    local_transaction_id VARCHAR(40),
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE(provider_name,merchant_id,provider_transaction_id),
    UNIQUE(provider_name,merchant_id,client_request_id)
);
-- W34.13 synthetic ledger plan mutation is contained in the same JDBC
-- transaction as the receipt insert and local ledger entry. The database
-- schema version remains v5, and external capture is NOT executed here.
