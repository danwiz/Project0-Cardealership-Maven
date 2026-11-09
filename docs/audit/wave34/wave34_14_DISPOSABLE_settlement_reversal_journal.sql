-- W34.14 DISPOSABLE PostgreSQL16 provider-event/reversal journal fixture ONLY.
-- NOT an approved migration; NOT real provider or financial accounting policy.
-- Applied after schema v5 only within random w3414_* ephemeral CI test schema.
-- All event and finance approver signing keys are generated in one JUnit JVM.
-- Privileged CI owner can drop constraints/triggers. NOT PRODUCTION IMMUTABILITY.
CREATE TABLE w3414_charge_state (
  provider_name VARCHAR(64) NOT NULL,
  merchant_id VARCHAR(64) NOT NULL,
  provider_charge_id VARCHAR(128) NOT NULL,
  customer_name VARCHAR(128) NOT NULL,
  ownership_id BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  captured_minor BIGINT NOT NULL CHECK(captured_minor > 0),
  reversed_minor BIGINT NOT NULL DEFAULT 0 CHECK(reversed_minor >= 0),
  settled BOOLEAN NOT NULL DEFAULT FALSE,
  last_sequence BIGINT NOT NULL DEFAULT 0 CHECK(last_sequence >= 0),
  state VARCHAR(24) NOT NULL DEFAULT 'NEW',
  PRIMARY KEY(provider_name,merchant_id,provider_charge_id),
  CHECK(reversed_minor <= captured_minor)
);
-- W3414_STATEMENT_BREAK
CREATE TABLE w3414_provider_events (
  provider_name VARCHAR(64) NOT NULL,
  merchant_id VARCHAR(64) NOT NULL,
  provider_charge_id VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  sequence_no BIGINT NOT NULL CHECK(sequence_no > 0),
  event_type VARCHAR(24) NOT NULL CHECK(event_type IN
    ('CAPTURED','SETTLED','REFUNDED','CHARGEBACK')),
  amount_minor BIGINT NOT NULL CHECK(amount_minor >= 0),
  currency CHAR(3) NOT NULL,
  event_digest CHAR(64) NOT NULL,
  approval_id UUID,
  received_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  PRIMARY KEY(provider_name,merchant_id,event_id),
  UNIQUE(provider_name,merchant_id,provider_charge_id,sequence_no),
  FOREIGN KEY(provider_name,merchant_id,provider_charge_id)
    REFERENCES w3414_charge_state(provider_name,merchant_id,provider_charge_id)
);
-- W3414_STATEMENT_BREAK
CREATE TABLE w3414_reversal_journal (
  journal_id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  provider_name VARCHAR(64) NOT NULL,
  merchant_id VARCHAR(64) NOT NULL,
  provider_charge_id VARCHAR(128) NOT NULL,
  event_id VARCHAR(128) NOT NULL,
  entry_kind VARCHAR(24) NOT NULL CHECK(entry_kind IN
    ('CAPTURE_CREDIT','SETTLEMENT_MARKER','REFUND_DEBIT','CHARGEBACK_DEBIT')),
  delta_minor BIGINT NOT NULL,
  currency CHAR(3) NOT NULL,
  approval_id UUID UNIQUE,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  UNIQUE(provider_name,merchant_id,event_id),
  FOREIGN KEY(provider_name,merchant_id,event_id)
    REFERENCES w3414_provider_events(provider_name,merchant_id,event_id),
  FOREIGN KEY(provider_name,merchant_id,provider_charge_id)
    REFERENCES w3414_charge_state(provider_name,merchant_id,provider_charge_id)
);
-- W3414_STATEMENT_BREAK
CREATE FUNCTION w3414_reject_journal_modification()
RETURNS trigger
LANGUAGE plpgsql
SET search_path = pg_catalog
AS $w3414_append_only$
BEGIN
  RAISE EXCEPTION 'W34.14 synthetic journal is append-only'
    USING ERRCODE='55000';
END;
$w3414_append_only$;
-- W3414_STATEMENT_BREAK
CREATE TRIGGER w3414_reject_update_or_delete
BEFORE UPDATE OR DELETE ON w3414_reversal_journal
FOR EACH ROW EXECUTE FUNCTION w3414_reject_journal_modification();
