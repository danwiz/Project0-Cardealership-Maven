-- W34.5 DISPOSABLE POSTGRESQL16 TEST FIXTURE ONLY.
-- Explicitly NOT an approved v6 migration. Do not execute on customer,
-- development, staging, production, persistent or shared environments.
-- JUnit alone applies it to a per-test random w345_* schema after v5 bootstrap.
-- Designed to rehearse one-to-one historical legacy transaction ID mapping.
-- A database-assigned identity is NOT a contiguous accounting reference.
CREATE TABLE w345_ledger_identity_map (
    ledger_entry_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    legacy_transaction_id VARCHAR(40) NOT NULL UNIQUE
        REFERENCES payment_transactions(transaction_id) ON DELETE RESTRICT,
    mapped_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
