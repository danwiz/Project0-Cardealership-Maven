-- W34.19 DISPOSABLE PostgreSQL16 simulated witness/revocation-at-commit/outbox ONLY.
-- NOT an approved production migration, independent custody, real financial
-- principal, merchant delivery, exactly-once external transport, or real GL.
-- All keys/data/witnesses remain in a single test JVM and single CI PG16 server.
-- The privileged CI DB owner can modify BOTH primary and witness tables.
-- CI creates a random schema w3419_*; existing application schema v5 is untouched.
CREATE TABLE w3419_periods (
    period_id VARCHAR(16) PRIMARY KEY,
    state VARCHAR(8) NOT NULL CHECK(state IN('OPEN','CLOSED'))
);
CREATE TABLE w3419_principals (
    principal_id VARCHAR(64) PRIMARY KEY,
    authority_role VARCHAR(8) NOT NULL CHECK(authority_role IN('MAKER','CHECKER')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    max_minor BIGINT NOT NULL CHECK(max_minor>0),
    revoked_at TIMESTAMPTZ,
    CHECK ((active AND revoked_at IS NULL)
       OR (NOT active AND revoked_at IS NOT NULL))
);
CREATE TABLE w3419_adjustments (
    adjustment_id VARCHAR(80) PRIMARY KEY,
    source_ref VARCHAR(128) NOT NULL UNIQUE,
    source_digest CHAR(64) NOT NULL,
    historical_period VARCHAR(16) NOT NULL REFERENCES w3419_periods(period_id),
    posting_period VARCHAR(16) NOT NULL REFERENCES w3419_periods(period_id),
    currency CHAR(3) NOT NULL CHECK(currency='USD'),
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    request_digest CHAR(64) NOT NULL,
    claim_digest CHAR(64) NOT NULL UNIQUE,
    maker_id VARCHAR(64) NOT NULL REFERENCES w3419_principals(principal_id),
    checker_id VARCHAR(64) NOT NULL REFERENCES w3419_principals(principal_id),
    maker_approval_id UUID NOT NULL UNIQUE,
    checker_approval_id UUID NOT NULL UNIQUE,
    CHECK (maker_id<>checker_id AND maker_approval_id<>checker_approval_id)
);
CREATE TABLE w3419_chain_cursor (
    singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
    last_sequence BIGINT NOT NULL DEFAULT 0 CHECK(last_sequence>=0),
    last_hash CHAR(64) NOT NULL
);
INSERT INTO w3419_chain_cursor VALUES
  (1,0,'0000000000000000000000000000000000000000000000000000000000000000');
CREATE TABLE w3419_chain (
    chain_sequence BIGINT PRIMARY KEY CHECK(chain_sequence>0),
    adjustment_id VARCHAR(80) NOT NULL UNIQUE
        REFERENCES w3419_adjustments(adjustment_id),
    previous_hash CHAR(64) NOT NULL,
    event_digest CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL UNIQUE
);
CREATE TABLE w3419_witness_receipts (
    anchor_sequence BIGINT PRIMARY KEY CHECK(anchor_sequence>0),
    tip_hash CHAR(64) NOT NULL,
    witness_id VARCHAR(64) NOT NULL CHECK(witness_id='simulated-witness'),
    signature_b64 VARCHAR(1024) NOT NULL,
    witnessed_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE w3419_outbox (
    message_id VARCHAR(100) PRIMARY KEY,
    adjustment_id VARCHAR(80) NOT NULL UNIQUE
        REFERENCES w3419_adjustments(adjustment_id),
    payload_digest CHAR(64) NOT NULL,
    state VARCHAR(8) NOT NULL DEFAULT 'PENDING'
        CHECK (state IN('PENDING','ACKED')),
    ack_ref VARCHAR(100) UNIQUE,
    acknowledged_at TIMESTAMPTZ,
    CHECK ((state='PENDING' AND ack_ref IS NULL AND acknowledged_at IS NULL)
        OR (state='ACKED' AND ack_ref IS NOT NULL AND acknowledged_at IS NOT NULL))
);
CREATE TABLE w3419_delivery_receipts (
    ack_ref VARCHAR(100) PRIMARY KEY,
    message_id VARCHAR(100) NOT NULL UNIQUE
        REFERENCES w3419_outbox(message_id),
    payload_digest CHAR(64) NOT NULL,
    recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION w3419_validate_adjustment()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3419_authority$
DECLARE
  historical_state VARCHAR(8);
  current_state VARCHAR(8);
  maker_allowed BOOLEAN;
  checker_allowed BOOLEAN;
BEGIN
  SELECT state INTO historical_state FROM __SCHEMA__.w3419_periods
      WHERE period_id=NEW.historical_period FOR UPDATE;
  SELECT state INTO current_state FROM __SCHEMA__.w3419_periods
      WHERE period_id=NEW.posting_period FOR UPDATE;
  SELECT active AND authority_role='MAKER' AND max_minor>=NEW.amount_minor
      INTO maker_allowed FROM __SCHEMA__.w3419_principals
      WHERE principal_id=NEW.maker_id FOR UPDATE;
  SELECT active AND authority_role='CHECKER' AND max_minor>=NEW.amount_minor
      INTO checker_allowed FROM __SCHEMA__.w3419_principals
      WHERE principal_id=NEW.checker_id FOR UPDATE;
  IF historical_state IS DISTINCT FROM 'CLOSED'
     OR current_state IS DISTINCT FROM 'OPEN'
     OR NEW.historical_period=NEW.posting_period
     OR maker_allowed IS DISTINCT FROM TRUE
     OR checker_allowed IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'W3419 revoked, over-limit, or improper period at SQL commit boundary'
      USING ERRCODE='42501';
  END IF;
  RETURN NEW;
END;
$w3419_authority$;
CREATE TRIGGER w3419_sql_principal_gate
BEFORE INSERT ON w3419_adjustments
FOR EACH ROW EXECUTE FUNCTION w3419_validate_adjustment();
CREATE FUNCTION w3419_reject_rewrite()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3419_no_rewrite$
BEGIN
  RAISE EXCEPTION 'W3419 test evidence cannot be rewritten'
      USING ERRCODE='55000';
END;
$w3419_no_rewrite$;
CREATE TRIGGER w3419_adjustments_immutable
BEFORE UPDATE OR DELETE ON w3419_adjustments
FOR EACH ROW EXECUTE FUNCTION w3419_reject_rewrite();
CREATE TRIGGER w3419_chain_immutable
BEFORE UPDATE OR DELETE ON w3419_chain
FOR EACH ROW EXECUTE FUNCTION w3419_reject_rewrite();
CREATE TRIGGER w3419_witness_immutable
BEFORE UPDATE OR DELETE ON w3419_witness_receipts
FOR EACH ROW EXECUTE FUNCTION w3419_reject_rewrite();
CREATE TRIGGER w3419_delivery_immutable
BEFORE UPDATE OR DELETE ON w3419_delivery_receipts
FOR EACH ROW EXECUTE FUNCTION w3419_reject_rewrite();
CREATE FUNCTION w3419_reject_reactivation()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3419_reactivation$
BEGIN
  IF OLD.active=FALSE AND NEW.active=TRUE THEN
    RAISE EXCEPTION 'W3419 lab revocation is final'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3419_reactivation$;
CREATE TRIGGER w3419_no_reactivation
BEFORE UPDATE ON w3419_principals
FOR EACH ROW EXECUTE FUNCTION w3419_reject_reactivation();
CREATE FUNCTION w3419_outbox_transition()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3419_delivery$
BEGIN
  IF TG_OP='DELETE' THEN
    RAISE EXCEPTION 'W3419 cannot delete a synthetic outbox'
      USING ERRCODE='55000';
  END IF;
  IF OLD.state <> 'PENDING' OR NEW.state <> 'ACKED'
      OR NEW.ack_ref IS NULL OR NEW.acknowledged_at IS NULL
      OR NEW.message_id IS DISTINCT FROM OLD.message_id
      OR NEW.adjustment_id IS DISTINCT FROM OLD.adjustment_id
      OR NEW.payload_digest IS DISTINCT FROM OLD.payload_digest THEN
    RAISE EXCEPTION 'W3419 only one matching PENDING to ACKED transition allowed'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3419_delivery$;
CREATE TRIGGER w3419_outbox_control
BEFORE UPDATE OR DELETE ON w3419_outbox
FOR EACH ROW EXECUTE FUNCTION w3419_outbox_transition();
