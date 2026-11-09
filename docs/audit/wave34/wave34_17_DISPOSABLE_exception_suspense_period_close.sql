-- W34.17 DISPOSABLE PostgreSQL16 exception, synthetic suspense and period close ONLY.
-- NOT an approved production migration, financial journal, WORM record or finance authority.
-- Keys and source evidence are generated in a single JUnit process; CI DBA can bypass SQL guards.
-- Each fixture exists only in a random w3417_* schema after existing schema v5.
CREATE TABLE w3417_periods (
  period_id VARCHAR(20) PRIMARY KEY,
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  state VARCHAR(8) NOT NULL DEFAULT 'OPEN' CHECK(state IN ('OPEN','CLOSED')),
  close_digest CHAR(64),
  closed_at TIMESTAMPTZ,
  CHECK ((state='OPEN' AND close_digest IS NULL AND closed_at IS NULL)
      OR (state='CLOSED' AND close_digest IS NOT NULL AND closed_at IS NOT NULL))
);
CREATE TABLE w3417_exceptions (
  exception_id VARCHAR(64) PRIMARY KEY,
  period_id VARCHAR(20) NOT NULL REFERENCES w3417_periods(period_id),
  source_ref VARCHAR(128) NOT NULL UNIQUE,
  source_digest CHAR(64) NOT NULL,
  amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  status VARCHAR(16) NOT NULL DEFAULT 'OPEN' CHECK(status IN ('OPEN','RESOLVED')),
  resolve_digest CHAR(64),
  maker_id VARCHAR(64),
  checker_id VARCHAR(64),
  maker_approval_id UUID UNIQUE,
  checker_approval_id UUID UNIQUE,
  CHECK ((status='OPEN' AND resolve_digest IS NULL
    AND maker_id IS NULL AND checker_id IS NULL
    AND maker_approval_id IS NULL AND checker_approval_id IS NULL)
   OR (status='RESOLVED' AND resolve_digest IS NOT NULL
    AND maker_id IS NOT NULL AND checker_id IS NOT NULL
    AND maker_id <> checker_id
    AND maker_approval_id IS NOT NULL AND checker_approval_id IS NOT NULL
    AND maker_approval_id <> checker_approval_id))
);
CREATE TABLE w3417_suspense_events (
  event_id VARCHAR(128) PRIMARY KEY,
  exception_id VARCHAR(64) NOT NULL REFERENCES w3417_exceptions(exception_id),
  kind VARCHAR(12) NOT NULL CHECK(kind IN ('HOLD','RELEASE')),
  amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  approval_id UUID UNIQUE,
  UNIQUE(exception_id,kind),
  CHECK ((kind='HOLD' AND approval_id IS NULL)
     OR (kind='RELEASE' AND approval_id IS NOT NULL))
);
CREATE TABLE w3417_suspense_lines (
  event_id VARCHAR(128) NOT NULL REFERENCES w3417_suspense_events(event_id),
  line_no INTEGER NOT NULL CHECK(line_no IN(1,2)),
  account_code CHAR(4) NOT NULL CHECK(account_code IN('1390','1990')),
  side CHAR(1) NOT NULL CHECK(side IN('D','C')),
  amount_minor BIGINT NOT NULL CHECK(amount_minor>0),
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  PRIMARY KEY(event_id,line_no),
  UNIQUE(event_id,account_code)
);
CREATE TABLE w3417_close_reviews (
  period_id VARCHAR(20) PRIMARY KEY REFERENCES w3417_periods(period_id),
  review_digest CHAR(64) NOT NULL,
  maker_id VARCHAR(64) NOT NULL,
  checker_id VARCHAR(64) NOT NULL,
  maker_approval_id UUID NOT NULL UNIQUE,
  checker_approval_id UUID NOT NULL UNIQUE,
  CHECK(maker_id<>checker_id AND maker_approval_id<>checker_approval_id)
);
CREATE FUNCTION w3417_guard_period_writes()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3417_guard$
DECLARE
  pstate VARCHAR(8);
BEGIN
  IF TG_TABLE_NAME='w3417_exceptions' THEN
    SELECT state INTO pstate FROM __SCHEMA__.w3417_periods
       WHERE period_id=NEW.period_id FOR UPDATE;
  ELSE
    SELECT p.state INTO pstate FROM __SCHEMA__.w3417_periods p
      JOIN __SCHEMA__.w3417_exceptions e ON e.period_id=p.period_id
      WHERE e.exception_id=NEW.exception_id FOR UPDATE OF p;
  END IF;
  IF pstate IS DISTINCT FROM 'OPEN' THEN
    RAISE EXCEPTION 'W3417 closed or unknown period rejects writes'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3417_guard$;
CREATE TRIGGER w3417_exception_open_only
BEFORE INSERT ON w3417_exceptions
FOR EACH ROW EXECUTE FUNCTION w3417_guard_period_writes();
CREATE TRIGGER w3417_event_open_only
BEFORE INSERT ON w3417_suspense_events
FOR EACH ROW EXECUTE FUNCTION w3417_guard_period_writes();
CREATE FUNCTION w3417_validate_suspense_lines()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3417_lines$
DECLARE
  n INTEGER; debits BIGINT; credits BIGINT; invalid INTEGER;
BEGIN
  SELECT COUNT(*),
    COALESCE(SUM(CASE WHEN l.side='D' THEN l.amount_minor ELSE 0 END),0),
    COALESCE(SUM(CASE WHEN l.side='C' THEN l.amount_minor ELSE 0 END),0),
    COUNT(*) FILTER (WHERE l.currency<>e.currency
       OR NOT ((e.kind='HOLD'
            AND ((l.line_no=1 AND l.account_code='1390' AND l.side='D')
              OR (l.line_no=2 AND l.account_code='1990' AND l.side='C')))
         OR (e.kind='RELEASE'
            AND ((l.line_no=1 AND l.account_code='1990' AND l.side='D')
              OR (l.line_no=2 AND l.account_code='1390' AND l.side='C')))))
  INTO n,debits,credits,invalid
  FROM __SCHEMA__.w3417_suspense_lines l
  JOIN __SCHEMA__.w3417_suspense_events e ON e.event_id=l.event_id
  WHERE l.event_id=NEW.event_id;
  IF n<>2 OR debits<>credits OR debits<>NEW.amount_minor OR invalid<>0 THEN
    RAISE EXCEPTION 'W3417 unbalanced or invalid synthetic suspense pair'
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$w3417_lines$;
CREATE CONSTRAINT TRIGGER w3417_suspense_balance_at_commit
AFTER INSERT ON w3417_suspense_events DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION w3417_validate_suspense_lines();
CREATE FUNCTION w3417_validate_close()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3417_close$
DECLARE
  unresolved INTEGER; suspense_net BIGINT; reviews INTEGER;
BEGIN
  IF NEW.state<>'CLOSED' THEN RETURN NULL; END IF;
  SELECT COUNT(*) FILTER (WHERE e.status<>'RESOLVED'),
    COALESCE(SUM(CASE WHEN s.kind='HOLD' THEN s.amount_minor
                      ELSE -s.amount_minor END),0)
  INTO unresolved,suspense_net
  FROM __SCHEMA__.w3417_exceptions e
  LEFT JOIN __SCHEMA__.w3417_suspense_events s
    ON s.exception_id=e.exception_id
  WHERE e.period_id=NEW.period_id;
  SELECT COUNT(*) INTO reviews FROM __SCHEMA__.w3417_close_reviews r
   WHERE r.period_id=NEW.period_id AND r.review_digest=NEW.close_digest
     AND r.maker_id<>r.checker_id
     AND r.maker_approval_id<>r.checker_approval_id;
  IF unresolved<>0 OR suspense_net<>0 OR reviews<>1 THEN
    RAISE EXCEPTION 'W3417 unresolved exception, suspense variance or missing close approval'
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$w3417_close$;
CREATE CONSTRAINT TRIGGER w3417_close_gate
AFTER UPDATE OF state ON w3417_periods DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION w3417_validate_close();
CREATE FUNCTION w3417_reject_rewrite()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3417_append_only$
BEGIN
  RAISE EXCEPTION 'W3417 synthetic evidence is append-only'
     USING ERRCODE='55000';
END;
$w3417_append_only$;
CREATE TRIGGER w3417_events_immutable
BEFORE UPDATE OR DELETE ON w3417_suspense_events
FOR EACH ROW EXECUTE FUNCTION w3417_reject_rewrite();
CREATE TRIGGER w3417_lines_immutable
BEFORE UPDATE OR DELETE ON w3417_suspense_lines
FOR EACH ROW EXECUTE FUNCTION w3417_reject_rewrite();
CREATE TRIGGER w3417_reviews_immutable
BEFORE UPDATE OR DELETE ON w3417_close_reviews
FOR EACH ROW EXECUTE FUNCTION w3417_reject_rewrite();
CREATE FUNCTION w3417_reject_period_reopen()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3417_no_reopen$
BEGIN
  IF OLD.state='CLOSED' THEN
    RAISE EXCEPTION 'W3417 closed period cannot be reopened in lab'
      USING ERRCODE='55000';
  END IF;
  RETURN NEW;
END;
$w3417_no_reopen$;
CREATE TRIGGER w3417_period_no_reopen
BEFORE UPDATE OR DELETE ON w3417_periods
FOR EACH ROW EXECUTE FUNCTION w3417_reject_period_reopen();
