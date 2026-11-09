-- v12 PostgreSQL RLS policies (docs/23 section 2). Applied only by the Docker / k8s
-- profiles (Flyway location db/migration/postgresql); H2 dev/test stops at V11.
-- The application connects as a non-superuser table owner, so FORCE is required for RLS to
-- apply to the owner as well.

ALTER TABLE org_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE org_units FORCE ROW LEVEL SECURITY;
CREATE POLICY org_units_tenant ON org_units
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts FORCE ROW LEVEL SECURITY;
CREATE POLICY accounts_tenant ON accounts
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE tenant_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_ratings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_ratings_tenant ON tenant_ratings
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE tenant_audits ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_audits FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_audits_tenant ON tenant_audits
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE account_login_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_login_events FORCE ROW LEVEL SECURITY;
CREATE POLICY account_login_events_tenant ON account_login_events
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));

ALTER TABLE account_mfa ENABLE ROW LEVEL SECURITY;
ALTER TABLE account_mfa FORCE ROW LEVEL SECURITY;
CREATE POLICY account_mfa_tenant ON account_mfa
  USING (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true))
  WITH CHECK (current_setting('app.platform', true) = 'true'
         OR tenant_id = current_setting('app.tenant_id', true));
