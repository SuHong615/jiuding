-- v7 quota (docs/17-v7-quota-usage-backup.md section 2).
-- H2 / PostgreSQL compatible ANSI subset.

ALTER TABLE tenants ADD COLUMN quota_org_units INT;

-- Backfill historical rows with the default organization quota (NULL means unlimited
-- for tenants created after this migration and for explicitly unlimited configurations).
UPDATE tenants SET quota_org_units = 50 WHERE quota_org_units IS NULL;
