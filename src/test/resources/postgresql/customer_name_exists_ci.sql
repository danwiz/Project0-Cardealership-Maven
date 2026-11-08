-- WAVE23 TEST ONLY: no persistent migration or production GRANT statements.
-- The caller must create this as schema owner in one transaction and REVOKE
-- default PUBLIC EXECUTE before COMMIT. __SCHEMA__ is replaced with a
-- generated ASCII identifier by the isolated disposable PG16 fixture.
CREATE FUNCTION __SCHEMA__.customer_username_exists_trusted(
    p_username text
) RETURNS boolean
LANGUAGE sql
SECURITY DEFINER
SET search_path = pg_catalog
AS $body$
    SELECT EXISTS (
        SELECT 1 FROM __SCHEMA__.accounts WHERE username = p_username
    );
$body$;
