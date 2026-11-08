-- Copyright 2026 suhong
--
-- Licensed under the Apache License, Version 2.0 (the "License");
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--     http://www.apache.org/licenses/LICENSE-2.0
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- @author suhong

-- Tenant identity domain (docs/11-tenant-identity-org-rating.md section 3).
-- H2 / PostgreSQL compatible ANSI subset: VARCHAR(36) UUID primary keys, no sequences,
-- no JSONB, no database generated timestamps (the application always supplies them).

ALTER TABLE tenants ADD COLUMN rating_grade VARCHAR(4);
ALTER TABLE tenants ADD COLUMN rating_score INT;
ALTER TABLE tenants ADD COLUMN rating_assessed_at TIMESTAMP;
ALTER TABLE tenants ADD COLUMN deleted_at TIMESTAMP;

CREATE TABLE org_units (
  id                 VARCHAR(36) PRIMARY KEY,
  tenant_id          VARCHAR(36) NOT NULL,
  parent_id          VARCHAR(36),
  code               VARCHAR(64) NOT NULL,
  name               VARCHAR(128) NOT NULL,
  type               VARCHAR(16) NOT NULL,
  path               VARCHAR(512) NOT NULL,
  depth              INT NOT NULL,
  sort_order         INT NOT NULL DEFAULT 0,
  manager_account_id VARCHAR(36),
  status             VARCHAR(16) NOT NULL,
  created_at         TIMESTAMP NOT NULL,
  updated_at         TIMESTAMP NOT NULL,
  version            BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_org_code UNIQUE (tenant_id, code)
);

CREATE INDEX idx_org_tenant_parent ON org_units(tenant_id, parent_id);
CREATE INDEX idx_org_tenant_path ON org_units(tenant_id, path);

CREATE TABLE accounts (
  id                   VARCHAR(36) PRIMARY KEY,
  tenant_id            VARCHAR(36) NOT NULL,
  org_unit_id          VARCHAR(36),
  username             VARCHAR(64) NOT NULL,
  display_name         VARCHAR(128) NOT NULL,
  email                VARCHAR(128),
  phone                VARCHAR(32),
  role                 VARCHAR(24) NOT NULL,
  status               VARCHAR(16) NOT NULL,
  password_hash        VARCHAR(256),
  password_salt        VARCHAR(64),
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts      INT NOT NULL DEFAULT 0,
  last_login_at        TIMESTAMP,
  created_by           VARCHAR(64),
  created_at           TIMESTAMP NOT NULL,
  updated_at           TIMESTAMP NOT NULL,
  deleted_at           TIMESTAMP,
  version              BIGINT NOT NULL DEFAULT 0,
  CONSTRAINT uk_account_username UNIQUE (tenant_id, username)
);

CREATE INDEX idx_account_tenant_org ON accounts(tenant_id, org_unit_id);

CREATE TABLE tenant_ratings (
  id             VARCHAR(36) PRIMARY KEY,
  tenant_id      VARCHAR(36) NOT NULL,
  grade          VARCHAR(4) NOT NULL,
  score          INT NOT NULL,
  dim_business   INT NOT NULL,
  dim_compliance INT NOT NULL,
  dim_financial  INT NOT NULL,
  dim_security   INT NOT NULL,
  dim_service    INT NOT NULL,
  source         VARCHAR(16) NOT NULL,
  assessed_by    VARCHAR(64) NOT NULL,
  note           VARCHAR(512),
  created_at     TIMESTAMP NOT NULL
);

CREATE INDEX idx_rating_tenant_time ON tenant_ratings(tenant_id, created_at);
