-- Wave31 disposable PostgreSQL16 only. No production DDL or credentials.
-- Function is schema-owner SECURITY DEFINER; the separate receipt-issuer
-- principal gets EXECUTE on THIS function only (not on customer update).
-- IMPORTANT: an issuer role alone cannot sign, and an old signer plus SQL
-- executor cannot register pre-cutover intents after key was disabled.
CREATE FUNCTION __SCHEMA__.register_credential_issuance_v31(
  p_issuer text, p_purpose text, p_key_id integer, p_username text,
  p_revision uuid, p_prior_hash text, p_next_hash text, p_nonce uuid,
  p_issued_epoch bigint, p_expires_epoch bigint, p_signature text
) RETURNS boolean
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $w31$
DECLARE
  v_key record;
  v_account record;
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
     OR p_prior_hash NOT LIKE 'pbkdf2$%' OR p_next_hash NOT LIKE 'pbkdf2$%'
     OR length(p_prior_hash)>500 OR length(p_next_hash)>500
     OR p_prior_hash=p_next_hash
     OR position('|' in p_prior_hash)>0 OR position('|' in p_next_hash)>0
     OR p_issued_epoch IS NULL OR p_expires_epoch IS NULL
     OR p_issued_epoch>v_now+5 OR p_issued_epoch<v_now-120
     OR p_expires_epoch<=v_now OR p_expires_epoch<=p_issued_epoch
     OR p_expires_epoch-p_issued_epoch>120
     OR p_signature IS NULL OR p_signature !~ '^[0-9a-f]{64}$' THEN
    RETURN false;
  END IF;

  -- FOR SHARE serializes with owner's UPDATE to VERIFY_ONLY/REVOKED.
  -- The independent database registration timestamp comes from this DB
  -- transaction, never from user-supplied issued_at.
  SELECT state,key_bytes,not_before_epoch,not_after_epoch
    INTO v_key FROM __SCHEMA__.w29_signing_keys
   WHERE key_id=p_key_id FOR SHARE;
  IF NOT FOUND OR v_key.state <> 'SIGNING_ACTIVE'
     OR octet_length(v_key.key_bytes)<32
     OR p_issued_epoch<v_key.not_before_epoch
     OR p_expires_epoch>v_key.not_after_epoch
     OR v_now>=v_key.not_after_epoch THEN
    RETURN false;
  END IF;
  SELECT role,encoded_credential,session_revision
    INTO v_account FROM __SCHEMA__.accounts
   WHERE username=p_username FOR SHARE;
  IF NOT FOUND OR v_account.role<>'CUSTOMER'
     OR v_account.encoded_credential IS DISTINCT FROM p_prior_hash
     OR v_account.session_revision IS DISTINCT FROM p_revision THEN
    RETURN false;
  END IF;
  v_message:='W29v1|'||p_issuer||'|'||p_purpose||'|'||p_key_id::text||'|'||
      p_username||'|'||p_revision::text||'|'||p_prior_hash||'|'||
      p_next_hash||'|'||p_nonce::text||'|'||
      p_issued_epoch::text||'|'||p_expires_epoch::text;
  v_expected:=encode(public.hmac(convert_to(v_message,'UTF8'),
                                 v_key.key_bytes,'sha256'),'hex');
  IF v_expected<>p_signature THEN RETURN false; END IF;

  INSERT INTO __SCHEMA__.w30_issued_intents
     (nonce,key_id,username,issued_epoch,signed_hmac)
     VALUES(p_nonce,p_key_id,p_username,p_issued_epoch,p_signature)
     ON CONFLICT(nonce) DO NOTHING;
  RETURN FOUND;
END;
$w31$;
