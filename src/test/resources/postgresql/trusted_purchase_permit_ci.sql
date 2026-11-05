-- Disposable PostgreSQL 16 CI fixture ONLY; not a production migration.
-- Owner creates permits and this function. The dedicated writer cannot mint permits.
-- __SCHEMA__ and __WRITER__ are UUID-derived test identifiers, never external input.
CREATE FUNCTION __SCHEMA__.approve_purchase_with_permit(
    p_permit uuid, p_request_id integer, p_payment_months integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $trusted_permit$
DECLARE
    v_reviewer varchar(100);
    v_buyer varchar(100);
    v_request_buyer varchar(100);
    v_permit_request integer;
    v_permit_months integer;
    v_expires_at timestamptz;
    v_revoked boolean;
    v_consumed_at timestamptz;
    v_listing_id integer;
    v_request_status varchar(30);
    v_price integer;
    v_stock integer;
    v_active boolean;
    v_make varchar(100);
    v_model varchar(100);
    v_year integer;
    v_owner_id bigint;
    v_contract varchar(20);
BEGIN
    IF session_user <> '__WRITER__' THEN
        RAISE EXCEPTION 'dedicated purchase writer required' USING ERRCODE = '42501';
    END IF;
    IF p_permit IS NULL OR p_request_id IS NULL OR p_request_id < 0
       OR p_payment_months IS NULL OR p_payment_months <= 0 THEN
        RAISE EXCEPTION 'valid permit, request and payment terms required'
          USING ERRCODE = '42501';
    END IF;

    -- The reviewer, buyer, exact request, terms and expiry are all
    -- provisioned by an independent trusted issuer, not caller-supplied.
    SELECT reviewer_username, buyer_username, request_id, payment_months,
           expires_at, revoked, consumed_at
      INTO v_reviewer, v_buyer, v_permit_request, v_permit_months,
           v_expires_at, v_revoked, v_consumed_at
      FROM __SCHEMA__.purchase_approval_permits
     WHERE permit_id = p_permit FOR UPDATE;

    IF NOT FOUND OR v_revoked OR v_consumed_at IS NOT NULL
       OR v_expires_at <= pg_catalog.clock_timestamp()
       OR v_permit_request <> p_request_id
       OR v_permit_months <> p_payment_months THEN
        RAISE EXCEPTION 'approval permit invalid, expired, revoked or consumed'
          USING ERRCODE = '42501';
    END IF;

    PERFORM 1 FROM __SCHEMA__.accounts
     WHERE username = v_reviewer AND role IN ('EMPLOYEE', 'ADMINISTRATOR')
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reviewer authority revoked' USING ERRCODE = '42501';
    END IF;
    PERFORM 1 FROM __SCHEMA__.accounts
     WHERE username = v_buyer AND role = 'CUSTOMER' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'buyer no longer eligible' USING ERRCODE = '42501';
    END IF;

    SELECT listing_id, customer_name, status
      INTO v_listing_id, v_request_buyer, v_request_status
      FROM __SCHEMA__.purchase_requests
     WHERE request_id = p_request_id FOR UPDATE;
    IF NOT FOUND OR v_request_status <> 'PENDING' OR v_request_buyer <> v_buyer THEN
        RAISE EXCEPTION 'pending request does not match approval permit'
          USING ERRCODE = '42501';
    END IF;

    SELECT price, stock_quantity, active, make, model, vehicle_year
      INTO v_price, v_stock, v_active, v_make, v_model, v_year
      FROM __SCHEMA__.inventory_listings
     WHERE listing_id = v_listing_id FOR UPDATE;
    IF NOT FOUND OR NOT v_active OR v_stock <= 0 THEN
        RAISE EXCEPTION 'listing unavailable' USING ERRCODE = '23514';
    END IF;

    v_contract := 'ACQ-' || pg_catalog.lpad(p_request_id::text, 6, '0');
    UPDATE __SCHEMA__.inventory_listings
       SET stock_quantity = v_stock - 1, active = (v_stock > 1)
     WHERE listing_id = v_listing_id;
    UPDATE __SCHEMA__.purchase_requests
       SET status = 'ACCEPTED', payment_months = p_payment_months,
           monthly_payment = v_price / p_payment_months, contract_id = v_contract
     WHERE request_id = p_request_id AND status = 'PENDING';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'purchase decision conflicted' USING ERRCODE = '40001';
    END IF;

    INSERT INTO __SCHEMA__.owned_vehicles
       (owner_username, contract_id, make, model, vehicle_year)
    VALUES (v_buyer, v_contract, v_make, v_model, v_year)
    RETURNING ownership_id INTO v_owner_id;
    INSERT INTO __SCHEMA__.payment_plans
       (ownership_id, purchase_price, payment_months, monthly_payment, amount_paid, balance)
    VALUES (v_owner_id, v_price, p_payment_months, v_price / p_payment_months, 0, v_price);

    -- Consuming the one-use permit happens in the SAME transaction.
    UPDATE __SCHEMA__.purchase_approval_permits
       SET consumed_at = pg_catalog.clock_timestamp(),
           contract_id = v_contract, purchase_price = v_price
     WHERE permit_id = p_permit AND consumed_at IS NULL AND NOT revoked;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'approval permit claim conflict' USING ERRCODE = '40001';
    END IF;
    RETURN v_price;
END
$trusted_permit$;
