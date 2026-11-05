-- CI-ONLY disposable PostgreSQL schema fixture.
-- Substitutions __SCHEMA__ and __WRITER__ are generated safe identifier tokens
-- by the Java integration test; never apply to a persistent or production DB.
-- Created and granted by the independent schema OWNER in one SQL transaction.
CREATE FUNCTION __SCHEMA__.approve_purchase_trusted(p_request_id integer, p_months integer)
RETURNS integer
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $trusted_purchase$
DECLARE
    v_listing_id integer;
    v_customer varchar(100);
    v_status varchar(30);
    v_existing_months integer;
    v_existing_contract varchar(20);
    v_customer_role varchar(30);
    v_price integer;
    v_stock integer;
    v_active boolean;
    v_make varchar(100);
    v_model varchar(100);
    v_year integer;
    v_contract varchar(20);
    v_ownership_id integer;
BEGIN
    -- SECURITY DEFINER changes CURRENT_USER to function owner;
    -- SESSION_USER remains the authenticated database login.
    IF session_user <> '__WRITER__' THEN
        RAISE EXCEPTION 'untrusted SQL session cannot finalize a purchase'
            USING ERRCODE = '42501';
    END IF;
    IF p_request_id IS NULL OR p_request_id < 0
       OR p_months IS NULL OR p_months <= 0 THEN
        RAISE EXCEPTION 'invalid purchase request or payment terms'
            USING ERRCODE = '22023';
    END IF;

    -- CI-only proof binds writer to a known owner-provisioned reviewer.
    -- Production requires an independently authenticated approval envelope,
    -- not caller-controlled reviewer fields or a static reviewer name.
    PERFORM 1 FROM __SCHEMA__.accounts
        WHERE username = 'reviewer' AND role = 'EMPLOYEE' FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'reviewer is no longer eligible'
            USING ERRCODE = '42501';
    END IF;

    SELECT listing_id, customer_name, status, payment_months, contract_id
      INTO v_listing_id, v_customer, v_status, v_existing_months, v_existing_contract
      FROM __SCHEMA__.purchase_requests
     WHERE request_id = p_request_id
     FOR UPDATE;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'unknown request' USING ERRCODE = '22023';
    END IF;

    v_contract := 'ACQ-' || lpad(p_request_id::text, 6, '0');
    IF v_status = 'ACCEPTED' THEN
        IF v_existing_months <> p_months OR v_existing_contract <> v_contract THEN
            RAISE EXCEPTION 'conflicting request replay' USING ERRCODE = '23505';
        END IF;
        SELECT pp.purchase_price INTO v_price
          FROM __SCHEMA__.owned_vehicles AS ov
          JOIN __SCHEMA__.payment_plans AS pp ON pp.ownership_id = ov.ownership_id
         WHERE ov.contract_id = v_contract AND ov.owner_username = v_customer
           AND pp.payment_months = p_months;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'accepted request is missing an ownership/payment plan'
                USING ERRCODE = '23514';
        END IF;
        RETURN v_price;
    END IF;
    IF v_status <> 'PENDING' THEN
        RAISE EXCEPTION 'request is not pending' USING ERRCODE = '23514';
    END IF;

    SELECT role INTO v_customer_role
      FROM __SCHEMA__.accounts
     WHERE username = v_customer FOR UPDATE;
    IF NOT FOUND OR v_customer_role <> 'CUSTOMER' THEN
        RAISE EXCEPTION 'buyer is no longer an eligible customer'
            USING ERRCODE = '42501';
    END IF;

    SELECT price, stock_quantity, active, make, model, vehicle_year
      INTO v_price, v_stock, v_active, v_make, v_model, v_year
      FROM __SCHEMA__.inventory_listings
     WHERE listing_id = v_listing_id FOR UPDATE;
    IF NOT FOUND OR NOT v_active OR v_stock <= 0 THEN
        RAISE EXCEPTION 'listing is unavailable'
            USING ERRCODE = '23514';
    END IF;

    UPDATE __SCHEMA__.inventory_listings
       SET stock_quantity = v_stock - 1, active = (v_stock > 1)
     WHERE listing_id = v_listing_id;
    UPDATE __SCHEMA__.purchase_requests
       SET status = 'ACCEPTED', payment_months = p_months,
           monthly_payment = v_price / p_months, contract_id = v_contract
     WHERE request_id = p_request_id AND status = 'PENDING';
    IF NOT FOUND THEN
        RAISE EXCEPTION 'request decision conflict' USING ERRCODE = '40001';
    END IF;

    INSERT INTO __SCHEMA__.owned_vehicles
       (owner_username, contract_id, make, model, vehicle_year)
    VALUES (v_customer, v_contract, v_make, v_model, v_year)
    RETURNING ownership_id INTO v_ownership_id;
    INSERT INTO __SCHEMA__.payment_plans
       (ownership_id, purchase_price, payment_months, monthly_payment, amount_paid, balance)
    VALUES (v_ownership_id, v_price, p_months, v_price / p_months, 0, v_price);
    RETURN v_price;
END
$trusted_purchase$;
