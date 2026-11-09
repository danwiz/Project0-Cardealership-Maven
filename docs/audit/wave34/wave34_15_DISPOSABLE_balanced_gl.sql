-- W34.15 DISPOSABLE PostgreSQL16 general-ledger proof ONLY.
-- NOT an approved production migration, COA, GAAP policy or merchant posting.
-- Test class applies this solely in a random w3415_* schema after v5.
-- No production payment tables, chart of accounts or funds are changed.
-- The CI database owner can bypass all triggers: NOT production WORM custody.
CREATE TABLE w3415_gl_accounts (
    account_code CHAR(4) PRIMARY KEY,
    label VARCHAR(80) NOT NULL,
    account_type VARCHAR(20) NOT NULL CHECK(account_type='ASSET'),
    currency CHAR(3) NOT NULL CHECK(currency='USD')
);
INSERT INTO w3415_gl_accounts (account_code,label,account_type,currency) VALUES
    ('1000','SYNTHETIC bank cash','ASSET','USD'),
    ('1100','SYNTHETIC processor clearing','ASSET','USD'),
    ('1200','SYNTHETIC customer receivable','ASSET','USD');
CREATE TABLE w3415_gl_events (
    event_id VARCHAR(128) PRIMARY KEY,
    provider_event_id VARCHAR(128) NOT NULL UNIQUE,
    sequence_no BIGINT NOT NULL UNIQUE CHECK(sequence_no>0),
    event_kind VARCHAR(20) NOT NULL CHECK(event_kind IN
        ('CAPTURED','SETTLED','REFUNDED','CHARGEBACK')),
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    currency CHAR(3) NOT NULL CHECK(currency='USD'),
    canonical_digest CHAR(64) NOT NULL,
    approval_id UUID UNIQUE,
    CHECK ((event_kind IN ('REFUNDED','CHARGEBACK') AND approval_id IS NOT NULL)
      OR (event_kind IN ('CAPTURED','SETTLED') AND approval_id IS NULL))
);
CREATE TABLE w3415_gl_lines (
    event_id VARCHAR(128) NOT NULL
        REFERENCES w3415_gl_events(event_id),
    line_no INTEGER NOT NULL CHECK (line_no IN (1,2)),
    account_code CHAR(4) NOT NULL
        REFERENCES w3415_gl_accounts(account_code),
    side CHAR(1) NOT NULL CHECK(side IN ('D','C')),
    amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
    currency CHAR(3) NOT NULL CHECK(currency='USD'),
    PRIMARY KEY(event_id,line_no),
    UNIQUE(event_id,account_code)
);
CREATE FUNCTION w3415_verify_balance_at_commit()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3415_balance$
DECLARE
    n INTEGER; debits BIGINT; credits BIGINT; mismatches INTEGER;
BEGIN
    SELECT COUNT(*),
           COALESCE(SUM(CASE WHEN l.side='D' THEN l.amount_minor ELSE 0 END),0),
           COALESCE(SUM(CASE WHEN l.side='C' THEN l.amount_minor ELSE 0 END),0),
           COUNT(*) FILTER (WHERE l.currency <> e.currency OR a.currency <> e.currency)
    INTO n,debits,credits,mismatches
    FROM __SCHEMA__.w3415_gl_lines l
    JOIN __SCHEMA__.w3415_gl_events e ON e.event_id=l.event_id
    JOIN __SCHEMA__.w3415_gl_accounts a ON a.account_code=l.account_code
    WHERE l.event_id=NEW.event_id;
    IF n <> 2 OR debits <> credits OR debits <> NEW.amount_minor
       OR mismatches <> 0 THEN
        RAISE EXCEPTION 'W3415 unbalanced, incomplete or currency-mismatched GL event'
          USING ERRCODE='23514';
    END IF;
    RETURN NULL;
END;
$w3415_balance$;
CREATE CONSTRAINT TRIGGER w3415_balance_gate
AFTER INSERT ON w3415_gl_events DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION w3415_verify_balance_at_commit();
CREATE FUNCTION w3415_forbid_gl_rewrite()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3415_immutability$
BEGIN
    RAISE EXCEPTION 'W3415 synthetic GL journal is append-only'
        USING ERRCODE='55000';
END;
$w3415_immutability$;
CREATE TRIGGER w3415_events_no_rewrite
BEFORE UPDATE OR DELETE ON w3415_gl_events
FOR EACH ROW EXECUTE FUNCTION w3415_forbid_gl_rewrite();
CREATE TRIGGER w3415_lines_no_rewrite
BEFORE UPDATE OR DELETE ON w3415_gl_lines
FOR EACH ROW EXECUTE FUNCTION w3415_forbid_gl_rewrite();
