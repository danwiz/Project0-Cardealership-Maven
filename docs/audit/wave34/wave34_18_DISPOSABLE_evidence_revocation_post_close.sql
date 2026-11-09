-- W34.18 DISPOSABLE PostgreSQL16 evidence-chain, revocation and prior-period correction ONLY.
-- NOT an approved production migration, signing authority, WORM ledger, GL adjustment
-- or real financial period close. All signer keys are generated inside one JUnit process.
-- The CI database owner can bypass triggers. Random w3418_* schema, version 5 unchanged.
CREATE TABLE w3418_periods (
    period_id VARCHAR(16) PRIMARY KEY,
    state VARCHAR(8) NOT NULL CHECK (state IN ('OPEN','CLOSED')),
    close_digest CHAR(64)
);
CREATE TABLE w3418_principals (
    principal_id VARCHAR(64) PRIMARY KEY,
    authority_role VARCHAR(8) NOT NULL CHECK(authority_role IN ('MAKER','CHECKER')),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    max_minor BIGINT NOT NULL CHECK(max_minor>0),
    revoked_at TIMESTAMPTZ,
    CHECK ((active AND revoked_at IS NULL) OR (NOT active AND revoked_at IS NOT NULL))
);
CREATE TABLE w3418_chain_cursor (
    singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
    last_sequence BIGINT NOT NULL CHECK(last_sequence>=0),
    last_hash CHAR(64) NOT NULL
);
INSERT INTO w3418_chain_cursor VALUES
    (1,0,'0000000000000000000000000000000000000000000000000000000000000000');
CREATE TABLE w3418_adjustments (
    adjustment_id VARCHAR(80) PRIMARY KEY,
    source_reference VARCHAR(128) NOT NULL,
    previous_period VARCHAR(16) NOT NULL REFERENCES w3418_periods(period_id),
    posting_period VARCHAR(16) NOT NULL REFERENCES w3418_periods(period_id),
    reason VARCHAR(64) NOT NULL CHECK(reason='VERIFIED_PREVIOUS_PERIOD_VARIANCE'),
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    currency CHAR(3) NOT NULL CHECK(currency='USD'),
    source_digest CHAR(64) NOT NULL,
    maker_id VARCHAR(64) NOT NULL REFERENCES w3418_principals(principal_id),
    checker_id VARCHAR(64) NOT NULL REFERENCES w3418_principals(principal_id),
    maker_approval_id UUID NOT NULL UNIQUE,
    checker_approval_id UUID NOT NULL UNIQUE,
    evidence_hash CHAR(64) NOT NULL UNIQUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    CHECK(maker_id<>checker_id AND maker_approval_id<>checker_approval_id)
);
CREATE TABLE w3418_evidence_chain (
    chain_sequence BIGINT PRIMARY KEY CHECK(chain_sequence>0),
    adjustment_id VARCHAR(80) NOT NULL UNIQUE
        REFERENCES w3418_adjustments(adjustment_id),
    previous_hash CHAR(64) NOT NULL,
    event_hash CHAR(64) NOT NULL UNIQUE,
    event_digest CHAR(64) NOT NULL,
    source_reference VARCHAR(128) NOT NULL,
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    previous_period VARCHAR(16) NOT NULL,
    posting_period VARCHAR(16) NOT NULL,
    reason VARCHAR(64) NOT NULL,
    maker_id VARCHAR(64) NOT NULL,
    checker_id VARCHAR(64) NOT NULL,
    inserted_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION w3418_validate_adjustment()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3418_gate$
DECLARE
  prior_state VARCHAR(8);
  current_state VARCHAR(8);
  maker_ok BOOLEAN;
  checker_ok BOOLEAN;
BEGIN
  SELECT state INTO prior_state FROM __SCHEMA__.w3418_periods
     WHERE period_id=NEW.previous_period FOR UPDATE;
  SELECT state INTO current_state FROM __SCHEMA__.w3418_periods
     WHERE period_id=NEW.posting_period FOR UPDATE;
  SELECT active AND authority_role='MAKER' AND max_minor>=NEW.amount_minor
    INTO maker_ok FROM __SCHEMA__.w3418_principals
    WHERE principal_id=NEW.maker_id FOR UPDATE;
  SELECT active AND authority_role='CHECKER' AND max_minor>=NEW.amount_minor
    INTO checker_ok FROM __SCHEMA__.w3418_principals
    WHERE principal_id=NEW.checker_id FOR UPDATE;
  IF prior_state IS DISTINCT FROM 'CLOSED'
      OR current_state IS DISTINCT FROM 'OPEN'
      OR NEW.previous_period=NEW.posting_period
      OR maker_ok IS DISTINCT FROM TRUE
      OR checker_ok IS DISTINCT FROM TRUE THEN
    RAISE EXCEPTION 'W3418 closed period, revoked principal or approval limit HOLD'
      USING ERRCODE='42501';
  END IF;
  RETURN NEW;
END;
$w3418_gate$;
CREATE TRIGGER w3418_adjustment_authorize
BEFORE INSERT ON w3418_adjustments
FOR EACH ROW EXECUTE FUNCTION w3418_validate_adjustment();
CREATE FUNCTION w3418_block_rewrite()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3418_immutable$
BEGIN
  RAISE EXCEPTION 'W3418 synthetic evidence append-only'
    USING ERRCODE='55000';
END;
$w3418_immutable$;
CREATE TRIGGER w3418_adjustment_immutable
BEFORE UPDATE OR DELETE ON w3418_adjustments
FOR EACH ROW EXECUTE FUNCTION w3418_block_rewrite();
CREATE TRIGGER w3418_chain_immutable
BEFORE UPDATE OR DELETE ON w3418_evidence_chain
FOR EACH ROW EXECUTE FUNCTION w3418_block_rewrite();
CREATE FUNCTION w3418_block_period_reopen()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3418_period$
BEGIN
  IF OLD.state='CLOSED' THEN
    RAISE EXCEPTION 'W3418 historical closed period must not be edited'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3418_period$;
CREATE TRIGGER w3418_period_reopen_denied
BEFORE UPDATE OR DELETE ON w3418_periods
FOR EACH ROW EXECUTE FUNCTION w3418_block_period_reopen();
CREATE FUNCTION w3418_no_resurrection()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3418_revocation$
BEGIN
  IF NOT OLD.active AND NEW.active THEN
    RAISE EXCEPTION 'W3418 revoked principal cannot be resurrected in lab'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3418_revocation$;
CREATE TRIGGER w3418_principal_no_resurrection
BEFORE UPDATE ON w3418_principals
FOR EACH ROW EXECUTE FUNCTION w3418_no_resurrection();
