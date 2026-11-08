-- W34.1 review-only additive PostgreSQL16 schema candidate.
-- DO NOT APPLY TO PRODUCTION: no approved v6 migration, currency policy,
-- authorization, role grants, ledger/outbox linkage, or rollback plan.
-- Disposable test schemas only, never master or customer data.
CREATE TABLE provider_payment_postings (
    provider_code VARCHAR(40) NOT NULL,
    provider_request_id VARCHAR(100) NOT NULL,
    provider_transaction_id VARCHAR(100) NOT NULL,
    customer_name VARCHAR(100) NOT NULL REFERENCES accounts(username),
    ownership_id BIGINT NOT NULL REFERENCES owned_vehicles(ownership_id),
    contract_id VARCHAR(20) NOT NULL,
    amount INTEGER NOT NULL CHECK (amount > 0),
    currency_code CHAR(3) NOT NULL,
    verified_receipt_digest CHAR(64) NOT NULL,
    signing_key_id VARCHAR(100) NOT NULL,
    local_transaction_id VARCHAR(40) UNIQUE REFERENCES payment_transactions(transaction_id),
    posted_at TIMESTAMP,
    PRIMARY KEY (provider_code, provider_request_id),
    UNIQUE (provider_code, provider_transaction_id),
    CONSTRAINT provider_receipt_posting_both_or_none CHECK (
        (local_transaction_id IS NULL AND posted_at IS NULL)
        OR (local_transaction_id IS NOT NULL AND posted_at IS NOT NULL)
    )
);
