-- W34.7 DISPOSABLE PG16 WRITER-FENCING LAB ONLY; NOT A MIGRATION.
-- This is a cooperative epoch protocol with test DBA privileges. Any role
-- able to modify the fence, disable triggers, or set an arbitrary GUC defeats
-- this fixture. DO NOT deploy or reuse as production authorization.
-- Executed only in random w347_* schemas by a CI-guarded JUnit test.
CREATE TABLE w347_writer_fence (
    singleton_id INTEGER PRIMARY KEY CHECK (singleton_id = 1),
    epoch BIGINT NOT NULL CHECK (epoch > 0),
    mode VARCHAR(10) NOT NULL CHECK (mode IN ('OPEN','FROZEN')),
    frozen_at TIMESTAMPTZ,
    CHECK ((mode = 'OPEN' AND frozen_at IS NULL)
        OR (mode = 'FROZEN' AND frozen_at IS NOT NULL))
);
-- W347_STATEMENT_BREAK
INSERT INTO w347_writer_fence(singleton_id,epoch,mode,frozen_at)
VALUES (1,1,'OPEN',NULL);
-- W347_STATEMENT_BREAK
CREATE FUNCTION w347_check_writer_epoch()
RETURNS trigger
LANGUAGE plpgsql
AS $wave347$
DECLARE
    required_epoch BIGINT;
    current_mode VARCHAR(10);
    supplied_epoch TEXT;
BEGIN
    -- A SHARE row lock serializes the write with an operator UPDATE freeze.
    -- The operator fence cannot commit while an earlier ledger writer holds
    -- the shared lock; later writers must observe the committed new epoch.
    SELECT epoch,mode INTO required_epoch,current_mode
    FROM w347_writer_fence
    WHERE singleton_id=1
    FOR SHARE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'W347 writer fence state unavailable'
            USING ERRCODE='55000';
    END IF;
    supplied_epoch := current_setting('w347.writer_epoch', true);
    IF current_mode <> 'OPEN'
        OR COALESCE(supplied_epoch,'') <> required_epoch::text THEN
        RAISE EXCEPTION 'W347 stale/frozen simulated writer'
            USING ERRCODE='55000';
    END IF;
    IF TG_OP = 'DELETE' THEN RETURN OLD; END IF;
    RETURN NEW;
END
$wave347$;
-- W347_STATEMENT_BREAK
CREATE TRIGGER w347_gate_legacy_payments
BEFORE INSERT OR UPDATE OR DELETE ON payment_transactions
FOR EACH ROW EXECUTE FUNCTION w347_check_writer_epoch();
-- W347_STATEMENT_BREAK
CREATE TRIGGER w347_gate_payment_plan
BEFORE INSERT OR UPDATE OR DELETE ON payment_plans
FOR EACH ROW EXECUTE FUNCTION w347_check_writer_epoch();
