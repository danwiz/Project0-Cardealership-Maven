-- W34.12 DISPOSABLE PostgreSQL16 only; NOT an approved migration or application code.
-- Placeholders __SCHEMA__ and __EXECUTOR__ MUST be replaced solely from
-- generated test-local validated identifiers. No live provider/funds.
-- A privileged test superuser owns SECURITY DEFINER and can bypass this
-- fixture. Local issuer role does NOT authenticate any external merchant.
CREATE TABLE __SCHEMA__.w3412_authorized_intents (
  auth_id UUID PRIMARY KEY,
  tenant VARCHAR(64) NOT NULL,
  request_id VARCHAR(128) NOT NULL,
  subject VARCHAR(128) NOT NULL,
  ownership_id BIGINT NOT NULL,
  amount INTEGER NOT NULL CHECK (amount > 0),
  transaction_id VARCHAR(40) NOT NULL,
  expires_at TIMESTAMPTZ NOT NULL,
  revoked_at TIMESTAMPTZ,
  consumed_at TIMESTAMPTZ,
  UNIQUE (tenant, request_id)
);
-- W3412_STATEMENT_BREAK
CREATE FUNCTION __SCHEMA__.w3412_apply_intent(p_auth UUID)
RETURNS VARCHAR(40)
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $w3412$
DECLARE
  a RECORD;
  paid INTEGER;
  remaining INTEGER;
  owner_name VARCHAR(128);
  contract VARCHAR(40);
BEGIN
  -- SESSION_USER, not CURRENT_USER, retains the original authenticated login
  -- in a SECURITY DEFINER routine. The test DBA can still bypass this.
  IF session_user <> '__EXECUTOR__' THEN
    RAISE EXCEPTION 'W3412 untrusted caller' USING ERRCODE = '42501';
  END IF;
  SELECT * INTO a FROM __SCHEMA__.w3412_authorized_intents
  WHERE auth_id = p_auth FOR UPDATE;
  IF NOT FOUND OR a.revoked_at IS NOT NULL
      OR a.expires_at <= clock_timestamp() THEN
    RAISE EXCEPTION 'W3412 unknown, revoked or expired test authorization'
      USING ERRCODE = '55000';
  END IF;
  -- An uncertain local ACK may be resolved by retrying the exact same token.
  -- This returns the previous result without charging/posting a second time.
  IF a.consumed_at IS NOT NULL THEN
    RETURN a.transaction_id;
  END IF;
  SELECT owner_username, contract_id INTO owner_name, contract
  FROM __SCHEMA__.owned_vehicles
  WHERE ownership_id = a.ownership_id;
  IF NOT FOUND OR owner_name IS DISTINCT FROM a.subject THEN
    RAISE EXCEPTION 'W3412 owner mismatch'
      USING ERRCODE = '55000';
  END IF;
  UPDATE __SCHEMA__.payment_plans
    SET amount_paid = amount_paid + a.amount,
        balance = balance - a.amount
    WHERE ownership_id = a.ownership_id AND balance >= a.amount
    RETURNING amount_paid,balance INTO paid,remaining;
  IF NOT FOUND THEN
    RAISE EXCEPTION 'W3412 plan not payable'
      USING ERRCODE = '55000';
  END IF;
  INSERT INTO __SCHEMA__.payment_transactions
    (transaction_id,customer_name,ownership_id,contract_id,
     amount,cumulative_paid,resulting_balance,recorded_at)
    VALUES (a.transaction_id,a.subject,a.ownership_id,contract,
            a.amount,paid,remaining,clock_timestamp());
  UPDATE __SCHEMA__.w3412_authorized_intents
    SET consumed_at=clock_timestamp()
    WHERE auth_id=p_auth;
  RETURN a.transaction_id;
END;
$w3412$;
-- W3412_STATEMENT_BREAK
REVOKE ALL ON FUNCTION __SCHEMA__.w3412_apply_intent(UUID) FROM PUBLIC;
-- W3412_STATEMENT_BREAK
REVOKE ALL ON TABLE __SCHEMA__.w3412_authorized_intents FROM PUBLIC;
