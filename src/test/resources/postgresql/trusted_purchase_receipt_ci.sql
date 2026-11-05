-- Disposable PostgreSQL 16 CI only; NOT a production migration.
-- Owner provisions this function. __SCHEMA__ and __RECONCILER__ are generated CI identifiers.
-- This is a read-only reconciliation *observation*, never a new purchase authorization.
CREATE FUNCTION __SCHEMA__.reconcile_purchase_permit(
    p_permit uuid, p_request_id integer, p_months integer)
RETURNS TABLE(outcome text, acquisition_contract varchar(20), authorized_price integer)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $trusted_receipt$
DECLARE
    v_request integer;
    v_months integer;
    v_buyer varchar(100);
    v_consumed_at timestamptz;
    v_contract varchar(20);
    v_price integer;
    v_status varchar(30);
    v_request_contract varchar(20);
    v_request_months integer;
    v_owner varchar(100);
    v_plan_price integer;
    v_plan_months integer;
BEGIN
    IF session_user <> '__RECONCILER__' THEN
        RAISE EXCEPTION 'dedicated reconciliation identity required'
            USING ERRCODE = '42501';
    END IF;
    IF p_permit IS NULL OR p_request_id IS NULL OR p_request_id < 0
       OR p_months IS NULL OR p_months <= 0 THEN
        RAISE EXCEPTION 'valid scoped receipt lookup required'
            USING ERRCODE = '42501';
    END IF;

    -- SHARE waits for an in-flight UPDATE of the permit to complete. A
    -- successful writer consumes this same permit within its purchase commit.
    SELECT p.request_id, p.payment_months, p.buyer_username,
           p.consumed_at, p.contract_id, p.purchase_price
      INTO v_request, v_months, v_buyer, v_consumed_at, v_contract, v_price
      FROM __SCHEMA__.purchase_approval_permits AS p
     WHERE p.permit_id = p_permit FOR SHARE;
    IF NOT FOUND OR v_request <> p_request_id OR v_months <> p_months THEN
        RAISE EXCEPTION 'unknown or mismatched purchase receipt'
            USING ERRCODE = '42501';
    END IF;

    SELECT r.status, r.contract_id, r.payment_months,
           ov.owner_username, pp.purchase_price, pp.payment_months
      INTO v_status, v_request_contract, v_request_months,
           v_owner, v_plan_price, v_plan_months
      FROM __SCHEMA__.purchase_requests AS r
      LEFT JOIN __SCHEMA__.owned_vehicles AS ov
        ON ov.contract_id = v_contract AND ov.owner_username = v_buyer
      LEFT JOIN __SCHEMA__.payment_plans AS pp
        ON pp.ownership_id = ov.ownership_id
     WHERE r.request_id = p_request_id;

    -- NOT_CONFIRMED is deliberately not a "safe to retry" decision.
    -- The observer may have queried before another approval began; clients
    -- must never interpret it as an authorization to re-submit a write.
    IF v_consumed_at IS NULL AND v_status = 'PENDING' THEN
        outcome := 'NOT_CONFIRMED';
        acquisition_contract := NULL;
        authorized_price := NULL;
    ELSIF v_consumed_at IS NOT NULL AND v_contract IS NOT NULL
       AND v_price IS NOT NULL AND v_status = 'ACCEPTED'
       AND v_request_contract = v_contract
       AND v_request_months = p_months
       AND v_owner = v_buyer
       AND v_plan_price = v_price AND v_plan_months = p_months THEN
        outcome := 'COMMITTED';
        acquisition_contract := v_contract;
        authorized_price := v_price;
    ELSE
        outcome := 'INCONSISTENT';
        acquisition_contract := NULL;
        authorized_price := NULL;
    END IF;
    RETURN NEXT;
END
$trusted_receipt$;
