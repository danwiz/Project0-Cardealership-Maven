-- W34.9 DISPOSABLE PG16 BEARER LEASE LAB; NOT A PRODUCTION MIGRATION.
-- __SCHEMA__ is substituted only after validating random w349_[0-9a-f]{32}.
-- Writer token is a caller-held bearer secret, exposed to its process;
-- the privileged CI test owner is issuer-of-last-resort and can bypass.
-- This is NOT a cryptographically independent/quorum issuer or HA fence.
CREATE TABLE __SCHEMA__.w349_epoch_fence (
    singleton_id INTEGER PRIMARY KEY CHECK(singleton_id=1),
    epoch BIGINT NOT NULL CHECK(epoch>0),
    mode VARCHAR(10) NOT NULL CHECK(mode IN ('OPEN','FROZEN')),
    frozen_at TIMESTAMPTZ,
    CHECK ((mode='OPEN' AND frozen_at IS NULL)
       OR (mode='FROZEN' AND frozen_at IS NOT NULL))
);
-- W349_STATEMENT_BREAK
INSERT INTO __SCHEMA__.w349_epoch_fence(singleton_id,epoch,mode,frozen_at)
VALUES(1,1,'OPEN',NULL);
-- W349_STATEMENT_BREAK
CREATE TABLE __SCHEMA__.w349_issued_lease (
    lease_token UUID PRIMARY KEY,
    principal TEXT NOT NULL,
    epoch BIGINT NOT NULL CHECK(epoch>0),
    issued_at TIMESTAMPTZ NOT NULL DEFAULT clock_timestamp(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ,
    CHECK(expires_at > issued_at)
);
-- W349_STATEMENT_BREAK
CREATE FUNCTION __SCHEMA__.w349_reject_unleased_writer()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $w349_guard$
DECLARE
    current_epoch BIGINT;
    current_mode VARCHAR(10);
    supplied TEXT;
    leased_principal TEXT;
    leased_epoch BIGINT;
    expiration TIMESTAMPTZ;
    revoked TIMESTAMPTZ;
BEGIN
    SELECT epoch,mode INTO current_epoch,current_mode
    FROM __SCHEMA__.w349_epoch_fence WHERE singleton_id=1 FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'W349 fence row absent' USING ERRCODE='55000';
    END IF;
    supplied := current_setting('w349.lease_token',true);
    IF supplied IS NULL OR supplied = '' THEN
        RAISE EXCEPTION 'W349 no lease supplied' USING ERRCODE='55000';
    END IF;
    -- Lock the identified lease row for the duration of this writer SQL txn:
    -- revocation UPDATE waits for an in-flight protected write to drain.
    SELECT principal,epoch,expires_at,revoked_at
    INTO leased_principal,leased_epoch,expiration,revoked
    FROM __SCHEMA__.w349_issued_lease WHERE lease_token=supplied::uuid FOR SHARE;
    IF NOT FOUND OR current_mode <> 'OPEN'
        OR leased_principal IS DISTINCT FROM session_user
        OR leased_epoch IS DISTINCT FROM current_epoch
        OR revoked IS NOT NULL OR expiration <= clock_timestamp() THEN
        RAISE EXCEPTION 'W349 stale, expired, revoked or alien lease'
            USING ERRCODE='55000';
    END IF;
    IF TG_OP='DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$w349_guard$;
-- W349_STATEMENT_BREAK
REVOKE ALL ON FUNCTION __SCHEMA__.w349_reject_unleased_writer() FROM PUBLIC;
-- W349_STATEMENT_BREAK
CREATE TRIGGER w349_guard_ledger
BEFORE INSERT OR UPDATE OR DELETE ON __SCHEMA__.payment_transactions
FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.w349_reject_unleased_writer();
-- W349_STATEMENT_BREAK
CREATE TRIGGER w349_guard_plan
BEFORE INSERT OR UPDATE OR DELETE ON __SCHEMA__.payment_plans
FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.w349_reject_unleased_writer();
