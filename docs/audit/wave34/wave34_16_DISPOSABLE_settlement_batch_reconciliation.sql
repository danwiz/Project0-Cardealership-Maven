-- W34.16 DISPOSABLE PostgreSQL16 three-source settlement reconciliation ONLY.
-- NOT an approved production migration, financial account, merchant/bank input,
-- production maker-checker authorization, or independent source custody.
-- Applied only to a random w3416_* schema by CI tests after existing schema v5.
-- Source, maker and checker signing keys are all ephemeral in one test JVM.
-- CI superuser can bypass triggers; NOT production immutability.
CREATE TABLE w3416_batch_reviews (
  batch_id VARCHAR(80) PRIMARY KEY,
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  provider_gross_minor BIGINT NOT NULL CHECK(provider_gross_minor>0),
  provider_fee_minor BIGINT NOT NULL CHECK(provider_fee_minor>=0),
  expected_net_minor BIGINT NOT NULL CHECK(expected_net_minor>0),
  maker_id VARCHAR(64) NOT NULL,
  checker_id VARCHAR(64) NOT NULL,
  maker_approval_id UUID NOT NULL UNIQUE,
  checker_approval_id UUID NOT NULL UNIQUE,
  review_digest CHAR(64) NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
  CHECK (maker_id<>checker_id),
  CHECK(provider_gross_minor-provider_fee_minor=expected_net_minor),
  CHECK(maker_approval_id<>checker_approval_id)
);
CREATE TABLE w3416_batch_sources (
  batch_id VARCHAR(80) NOT NULL REFERENCES w3416_batch_reviews(batch_id),
  source_kind VARCHAR(12) NOT NULL CHECK(source_kind IN ('PROVIDER','BANK','GL')),
  reference_id VARCHAR(128) NOT NULL,
  currency CHAR(3) NOT NULL CHECK(currency='USD'),
  gross_minor BIGINT NOT NULL CHECK(gross_minor>=0),
  fee_minor BIGINT NOT NULL CHECK(fee_minor>=0),
  net_minor BIGINT NOT NULL CHECK(net_minor>=0),
  signed_digest CHAR(64) NOT NULL,
  PRIMARY KEY(batch_id,source_kind),
  UNIQUE(source_kind,reference_id),
  CHECK((source_kind='PROVIDER' AND gross_minor>0 AND gross_minor>=fee_minor)
     OR (source_kind IN ('BANK','GL') AND gross_minor=0 AND fee_minor=0))
);
CREATE TABLE w3416_batch_audit (
  audit_id UUID PRIMARY KEY,
  batch_id VARCHAR(80) NOT NULL UNIQUE REFERENCES w3416_batch_reviews(batch_id),
  reconciliation_digest CHAR(64) NOT NULL,
  recorded_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp()
);
CREATE FUNCTION w3416_verify_three_source_batch()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3416_three_sources$
DECLARE
  source_count INTEGER;
  pgross BIGINT;
  pfee BIGINT;
  pnet BIGINT;
  bnet BIGINT;
  gnet BIGINT;
  currencies INTEGER;
  approvals INTEGER;
BEGIN
  SELECT COUNT(*),
    MAX(s.gross_minor) FILTER(WHERE s.source_kind='PROVIDER'),
    MAX(s.fee_minor) FILTER(WHERE s.source_kind='PROVIDER'),
    MAX(s.net_minor) FILTER(WHERE s.source_kind='PROVIDER'),
    MAX(s.net_minor) FILTER(WHERE s.source_kind='BANK'),
    MAX(s.net_minor) FILTER(WHERE s.source_kind='GL'),
    COUNT(DISTINCT s.currency)
  INTO source_count,pgross,pfee,pnet,bnet,gnet,currencies
  FROM __SCHEMA__.w3416_batch_sources s
  WHERE s.batch_id=NEW.batch_id;
  SELECT COUNT(*) INTO approvals FROM __SCHEMA__.w3416_batch_audit a
  WHERE a.batch_id=NEW.batch_id;
  IF source_count<>3 OR approvals<>1 OR currencies<>1
     OR pgross IS DISTINCT FROM NEW.provider_gross_minor
     OR pfee IS DISTINCT FROM NEW.provider_fee_minor
     OR pnet IS DISTINCT FROM NEW.expected_net_minor
     OR bnet IS DISTINCT FROM NEW.expected_net_minor
     OR gnet IS DISTINCT FROM NEW.expected_net_minor THEN
    RAISE EXCEPTION 'W3416 provider/bank/GL reconciliation variance or incomplete custody'
      USING ERRCODE='23514';
  END IF;
  RETURN NULL;
END;
$w3416_three_sources$;
CREATE CONSTRAINT TRIGGER w3416_batch_reconcile_at_commit
AFTER INSERT ON w3416_batch_reviews DEFERRABLE INITIALLY DEFERRED
FOR EACH ROW EXECUTE FUNCTION w3416_verify_three_source_batch();
CREATE FUNCTION w3416_reject_rewrites()
RETURNS trigger LANGUAGE plpgsql SET search_path=pg_catalog
AS $w3416_append_only$
BEGIN
  RAISE EXCEPTION 'W3416 synthetic settlement evidence is append-only'
    USING ERRCODE='55000';
END;
$w3416_append_only$;
CREATE TRIGGER w3416_reviews_no_rewrite
BEFORE UPDATE OR DELETE ON w3416_batch_reviews
FOR EACH ROW EXECUTE FUNCTION w3416_reject_rewrites();
CREATE TRIGGER w3416_sources_no_rewrite
BEFORE UPDATE OR DELETE ON w3416_batch_sources
FOR EACH ROW EXECUTE FUNCTION w3416_reject_rewrites();
CREATE TRIGGER w3416_audit_no_rewrite
BEFORE UPDATE OR DELETE ON w3416_batch_audit
FOR EACH ROW EXECUTE FUNCTION w3416_reject_rewrites();
