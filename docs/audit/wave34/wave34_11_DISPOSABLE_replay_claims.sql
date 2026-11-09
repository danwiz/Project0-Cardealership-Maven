-- W34.11 DISPOSABLE POSTGRESQL16 TRANSACTIONAL REPLAY REHEARSAL ONLY.
-- This file is NOT an approved schema migration, does not create schema v6,
-- and is applied solely by CI-gated JUnit inside random w3411_* test schemas.
-- DB uniqueness is durable across client reconnects; issuer trust is NOT.
-- The CI database superuser can bypass the test protocol; no live funds.
CREATE TABLE w3411_verified_request_claims (
    claim_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    issuer_key_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64) NOT NULL,
    lease_jti UUID NOT NULL,
    challenge_nonce UUID NOT NULL,
    request_id VARCHAR(128) NOT NULL,
    request_digest CHAR(64) NOT NULL,
    accepted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    UNIQUE (challenge_nonce),
    UNIQUE (tenant_id, request_id),
    UNIQUE (lease_jti, challenge_nonce)
);
-- Existing payment_transactions and payment_plans are unmodified v5 tables.
-- This toy claim table does not implement provider event idempotency, finance
-- authorization, independent issuer identity, or hardened role-based access.
