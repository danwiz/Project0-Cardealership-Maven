-- TEST-ONLY, DISPOSABLE schema owner-controlled customer registration prototype.
-- __SCHEMA__ placeholder replaced solely with a test-generated alphanumeric schema
-- from UUID. DO NOT deploy to persistent databases as an automatic migration.
--
-- CREATE and immediate REVOKE FROM PUBLIC must happen in the SAME transaction.
-- Function owner and database role grants require independent DBA/AppSec approval.
CREATE FUNCTION __SCHEMA__.register_customer_trusted(
    p_username text, p_encoded_credential text
) RETURNS text
LANGUAGE plpgsql
SECURITY DEFINER
SET search_path = pg_catalog
AS $customer_registration$
DECLARE
    inserted_username text;
BEGIN
    IF p_username IS NULL OR btrim(p_username) = '' OR
            length(p_username) > 100 OR btrim(p_username) <> p_username THEN
        RAISE EXCEPTION 'invalid customer username' USING ERRCODE = '22023';
    END IF;
    IF p_encoded_credential IS NULL OR
            left(p_encoded_credential, 7) <> 'pbkdf2$' OR
            length(p_encoded_credential) < 60 OR
            length(p_encoded_credential) > 500 THEN
        RAISE EXCEPTION 'encoded PBKDF2 credential required' USING ERRCODE = '22023';
    END IF;

    -- No role input, no session_revision input. Database generates its UUID.
    INSERT INTO __SCHEMA__.accounts (username, encoded_credential, role)
    VALUES (p_username, p_encoded_credential, 'CUSTOMER')
    RETURNING username INTO inserted_username;
    RETURN inserted_username;
END;
$customer_registration$;
