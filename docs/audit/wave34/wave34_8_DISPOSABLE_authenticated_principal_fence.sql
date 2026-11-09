-- W34.8 DISPOSABLE POSTGRESQL16 ROLE-ISOLATION FIXTURE ONLY.
-- __SCHEMA__ substitution is performed only with a randomly generated
-- lowercase w348_[0-9a-f]+ schema in a CI-only test. NOT A PRODUCTION MIGRATION.
-- An independently operated external epoch issuer, quorum leader fencing and
-- hardened production DBA/security-definer ownership are NOT implemented.
--
-- The guard binds a connection to PostgreSQL-authenticated SESSION_USER, not
-- the spoofable W34.7 caller-provided GUC. The CI owner is superuser, so this
-- demonstrates only limited-principal privilege separation in one database.
CREATE TABLE __SCHEMA__.w348_fence (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id=1),
    epoch BIGINT NOT NULL CHECK (epoch>0),
    mode VARCHAR(10) NOT NULL CHECK(mode IN ('OPEN','FROZEN')),
    frozen_at TIMESTAMPTZ,
    CHECK ((mode='OPEN' AND frozen_at IS NULL)
         OR (mode='FROZEN' AND frozen_at IS NOT NULL))
);
-- W348_STATEMENT_BREAK
INSERT INTO __SCHEMA__.w348_fence(singleton_id,epoch,mode,frozen_at)
VALUES (1,1,'OPEN',NULL);
-- W348_STATEMENT_BREAK
CREATE TABLE __SCHEMA__.w348_principal_epoch (
    principal TEXT PRIMARY KEY,
    permitted_epoch BIGINT NOT NULL CHECK (permitted_epoch>0),
    enabled BOOLEAN NOT NULL
);
-- W348_STATEMENT_BREAK
CREATE FUNCTION __SCHEMA__.w348_authenticated_writer_guard()
RETURNS trigger
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $w348_guard$
DECLARE
    fence_epoch BIGINT;
    fence_mode VARCHAR(10);
    allowed_epoch BIGINT;
    enabled_role BOOLEAN;
BEGIN
    SELECT epoch,mode INTO fence_epoch,fence_mode
    FROM __SCHEMA__.w348_fence
    WHERE singleton_id=1
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'W348 fence row missing' USING ERRCODE='55000';
    END IF;

    SELECT permitted_epoch,enabled INTO allowed_epoch,enabled_role
    FROM __SCHEMA__.w348_principal_epoch
    WHERE principal = session_user;
    IF NOT FOUND OR enabled_role IS DISTINCT FROM TRUE
       OR fence_mode <> 'OPEN' OR allowed_epoch IS DISTINCT FROM fence_epoch THEN
        RAISE EXCEPTION 'W348 authenticated writer not authorized for current epoch'
            USING ERRCODE='55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END;
$w348_guard$;
-- W348_STATEMENT_BREAK
REVOKE ALL ON FUNCTION __SCHEMA__.w348_authenticated_writer_guard() FROM PUBLIC;
-- W348_STATEMENT_BREAK
CREATE TRIGGER w348_guard_ledger
BEFORE INSERT OR UPDATE OR DELETE ON __SCHEMA__.payment_transactions
FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.w348_authenticated_writer_guard();
-- W348_STATEMENT_BREAK
CREATE TRIGGER w348_guard_plan
BEFORE INSERT OR UPDATE OR DELETE ON __SCHEMA__.payment_plans
FOR EACH ROW EXECUTE FUNCTION __SCHEMA__.w348_authenticated_writer_guard();
