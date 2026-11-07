-- Wave29 disposable PostgreSQL16 fixture. NOT a production migration.
-- The SQL executor receives EXECUTE only; no keys, accounts or nonce table grants.
-- Wave30 test-only owner issuance receipt strengthens VERIFY_ONLY boundary.\n-- Owner-created tables (separate fixture transaction):
-- __SCHEMA__.w29_signing_keys(key_id int, key_bytes bytea, state text,
--                            not_before_epoch bigint, not_after_epoch bigint,
--                            signing_disabled_epoch bigint)
-- __SCHEMA__.w29_used_nonces(nonce uuid primary key, key_id int, username text)
--
-- Canonical fields are strictly ASCII with pipe ('|') excluded from each.
-- Signed text is domain separated, with fixed field count and position.
CREATE FUNCTION __SCHEMA__.rotate_customer_credential_v29(
  p_issuer text, p_purpose text, p_key_id integer, p_username text,
  p_revision uuid, p_prior_hash text, p_next_hash text, p_nonce uuid,
  p_issued_epoch bigint, p_expires_epoch bigint, p_signature text
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $w29$
DECLARE
  v_account record;
  v_key record;
  v_now bigint;
  v_message text;
  v_expected text;
BEGIN
  v_now := floor(extract(epoch from clock_timestamp()))::bigint;
  IF p_issuer IS DISTINCT FROM 'credential-service'
     OR p_purpose IS DISTINCT FROM 'customer-password-rotation'
     OR p_key_id IS NULL OR p_key_id < 1
     OR p_username IS NULL OR p_username !~ '^[a-zA-Z0-9_.-]{1,100}$'
     OR p_revision IS NULL OR p_nonce IS NULL
     OR p_prior_hash IS NULL OR p_next_hash IS NULL
     OR p_prior_hash NOT LIKE 'pbkdf2$%'
     OR p_next_hash NOT LIKE 'pbkdf2$%'
     OR length(p_prior_hash) > 500 OR length(p_next_hash) > 500
     OR p_prior_hash = p_next_hash
     OR position('|' in p_prior_hash) > 0
     OR position('|' in p_next_hash) > 0
     OR p_issued_epoch IS NULL OR p_expires_epoch IS NULL
     OR p_issued_epoch > v_now + 5
     OR p_issued_epoch < v_now - 120
     OR p_expires_epoch <= v_now OR p_expires_epoch <= p_issued_epoch
     OR p_expires_epoch - p_issued_epoch > 120
     OR p_signature IS NULL OR p_signature !~ '^[0-9a-f]{64}$' THEN
    RETURN false;
  END IF;

  -- Explicit key state and signing cutover under lock. Emergency revocation
  -- serializes with an authorized UPDATE before any account mutation.
  SELECT state, key_bytes, not_before_epoch, not_after_epoch,
         signing_disabled_epoch
    INTO v_key FROM __SCHEMA__.w29_signing_keys
   WHERE key_id = p_key_id FOR SHARE;
  IF NOT FOUND OR octet_length(v_key.key_bytes) < 32
     OR v_key.state NOT IN ('SIGNING_ACTIVE','VERIFY_ONLY')
     OR p_issued_epoch < v_key.not_before_epoch
     OR v_now >= v_key.not_after_epoch
     OR p_expires_epoch > v_key.not_after_epoch THEN
    RETURN false;
  END IF;
  IF v_key.state = 'VERIFY_ONLY' AND
    (v_key.signing_disabled_epoch IS NULL
     OR p_issued_epoch >= v_key.signing_disabled_epoch) THEN
    RETURN false;
  END IF;

  SELECT role, encoded_credential, session_revision INTO v_account
    FROM __SCHEMA__.accounts WHERE username = p_username FOR UPDATE;
  IF NOT FOUND OR v_account.role <> 'CUSTOMER'
     OR v_account.encoded_credential IS DISTINCT FROM p_prior_hash
     OR v_account.session_revision IS DISTINCT FROM p_revision THEN
    RETURN false;
  END IF;

  v_message := 'W29v1|' || p_issuer || '|' || p_purpose || '|'
     || p_key_id::text || '|' || p_username || '|' || p_revision::text
     || '|' || p_prior_hash || '|' || p_next_hash || '|' || p_nonce::text
     || '|' || p_issued_epoch::text || '|' || p_expires_epoch::text;
  v_expected := encode(public.hmac(
     convert_to(v_message,'UTF8'), v_key.key_bytes,'sha256'), 'hex');
  IF v_expected <> p_signature THEN RETURN false; END IF;

  -- Wave30: a VERIFY_ONLY key is not evidence that an intent was truly
  -- issued before key cutover. A stolen old key could otherwise backdate
  -- issued_at and forge an apparently pre-cutover request. Require a receipt
  -- that an independently protected owner registered while signing was
  -- active, matching exact nonce, key, subject, issue time and MAC. The SQL
  -- executor has no SELECT/INSERT privilege on this table.
  IF v_key.state = 'VERIFY_ONLY' THEN
    PERFORM 1
      FROM __SCHEMA__.w30_issued_intents
     WHERE nonce = p_nonce
       AND key_id = p_key_id
       AND username = p_username
       AND issued_epoch = p_issued_epoch
       AND signed_hmac = p_signature
       AND registered_at < to_timestamp(v_key.signing_disabled_epoch);
    IF NOT FOUND THEN RETURN false; END IF;
  END IF;

  INSERT INTO __SCHEMA__.w29_used_nonces(nonce,key_id,username)
       VALUES(p_nonce,p_key_id,p_username)
  ON CONFLICT(nonce) DO NOTHING;
  IF NOT FOUND THEN RETURN false; END IF;

  UPDATE __SCHEMA__.accounts SET encoded_credential = p_next_hash
    WHERE username = p_username AND role = 'CUSTOMER'
      AND encoded_credential = p_prior_hash AND session_revision = p_revision;
  RETURN FOUND;
END;
$w29$;
