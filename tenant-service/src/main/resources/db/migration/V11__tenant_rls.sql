-- v12 PostgreSQL RLS on the tenant data plane (docs/23 section 2).
--
-- H2 (dev / test) does not implement PostgreSQL RLS, so this migration is a deliberate
-- no-op there; the real policies live in db/migration/postgresql/V11_1__tenant_rls.sql and
-- are applied by the Docker / k8s profiles which add that Flyway location.
--
-- References for the PostgreSQL statements:
--   ALTER TABLE org_units ENABLE ROW LEVEL SECURITY;
--   ALTER TABLE org_units FORCE ROW LEVEL SECURITY;
--   CREATE POLICY org_units_tenant ON org_units
--     USING (current_setting('app.platform', true) = 'true'
--            OR tenant_id = current_setting('app.tenant_id', true))
--     WITH CHECK (current_setting('app.platform', true) = 'true'
--            OR tenant_id = current_setting('app.tenant_id', true));
--   ... same for accounts, tenant_ratings, tenant_audits, account_login_events, account_mfa.
--
-- Keeping this version on H2 records a stable Flyway baseline (V11) for every environment.

SELECT 1;
