-- Wave27 TEST-ONLY PostgreSQL16 SQL function, never a production migration.
-- Replace __SCHEMA__ with a random safe ASCII schema identifier at test setup.
-- Owner creates/revokes/grants this function in one transaction. The SQL
-- principal that invokes this function has NO direct account SELECT or UPDATE.
--
-- Preconditions created separately by schema owner:
--  __SCHEMA__.credential_rotation_keys(key_id int, key_bytes bytea)
--  __SCHEMA__.credential_rotation_nonces(nonce uuid, username text)
--  pgcrypto installed as extension in public.
--
-- The signing key is held by a separate private broker and by this owner-only
-- table, NOT by the credential SQL login. A stolen DB login without the key
-- cannot forge the HMAC or update any credential hash.
CREATE FUNCTION __SCHEMA__.rotate_customer_credential_authorized(
    p_username text,
    p_revision uuid,
    p_expected_hash text,
    p_new_hash text,
    p_nonce uuid,
    p_hmac_hex text
) RETURNS boolean
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog, pg_temp
AS $authorized$
DECLARE
    current_account record;
    v_secret bytea;
    message text;
    calculated text;
    used_nonce boolean;
BEGIN
    IF p_username IS NULL OR length(p_username) = 0 OR length(p_username) > 100
       OR position(chr(10) in p_username) > 0
       OR position(chr(13) in p_username) > 0
       OR p_revision IS NULL OR p_expected_hash IS NULL
       OR p_new_hash IS NULL OR length(p_new_hash) > 512
       OR p_new_hash NOT LIKE 'pbkdf2$%'
       OR p_new_hash = p_expected_hash OR p_nonce IS NULL
       OR p_hmac_hex IS NULL OR length(p_hmac_hex) <> 64
       OR p_hmac_hex !~ '^[a-f0-9]{64}$' THEN
        RETURN false;
    END IF;

    SELECT role, encoded_credential, session_revision
      INTO current_account
      FROM __SCHEMA__.accounts
     WHERE username = p_username
     FOR UPDATE;
    IF NOT FOUND OR current_account.role <> 'CUSTOMER'
       OR current_account.encoded_credential IS DISTINCT FROM p_expected_hash
       OR current_account.session_revision IS DISTINCT FROM p_revision THEN
        RETURN false;
    END IF;

    SELECT key_bytes INTO v_secret
      FROM __SCHEMA__.credential_rotation_keys
     WHERE key_id = 1;
    IF v_secret IS NULL OR octet_length(v_secret) < 32 THEN
        RETURN false;
    END IF;

    message := p_username || chr(10) || p_revision::text || chr(10)
               || p_expected_hash || chr(10) || p_new_hash || chr(10)
               || p_nonce::text;
    calculated := encode(public.hmac(convert_to(message, 'UTF8'),v_secret,'sha256'),'hex');
    IF calculated <> p_hmac_hex THEN
        RETURN false;
    END IF;

    -- Nonce uniqueness is enforced under the same database transaction as
    -- the actual credential mutation. Replays with a valid MAC are rejected.
    INSERT INTO __SCHEMA__.credential_rotation_nonces(nonce, username)
         VALUES (p_nonce,p_username)
    ON CONFLICT(nonce) DO NOTHING;
    IF NOT FOUND THEN
        RETURN false;
    END IF;

    UPDATE __SCHEMA__.accounts
       SET encoded_credential = p_new_hash
     WHERE username = p_username AND role = 'CUSTOMER'
       AND encoded_credential = p_expected_hash AND session_revision = p_revision;
    RETURN FOUND;
END;
$authorized$;
