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

-- tenant_db schema (H2 / PostgreSQL compatible ANSI subset).
-- All primary keys are application generated VARCHAR(36) UUIDs: no sequences, no JSONB.

CREATE TABLE tenants (
  id               VARCHAR(36) PRIMARY KEY,
  tenant_code      VARCHAR(64)  NOT NULL UNIQUE,
  name             VARCHAR(128) NOT NULL,
  contact_name     VARCHAR(64),
  contact_email    VARCHAR(128),
  contact_phone    VARCHAR(32),
  plan             VARCHAR(16)  NOT NULL,
  region           VARCHAR(32)  NOT NULL,
  isolation_mode   VARCHAR(24)  NOT NULL,
  status           VARCHAR(24)  NOT NULL,
  risk_level       VARCHAR(8),
  risk_score       INT,
  quota_seats      INT,
  quota_storage_gb INT,
  quota_api_rpm    INT,
  source           VARCHAR(16)  NOT NULL,
  attributes_json  TEXT,
  reject_reason    VARCHAR(512),
  created_at       TIMESTAMP    NOT NULL,
  updated_at       TIMESTAMP    NOT NULL,
  submitted_at     TIMESTAMP,
  decided_at       TIMESTAMP,
  activated_at     TIMESTAMP,
  version          BIGINT       NOT NULL DEFAULT 0
);

CREATE INDEX idx_tenants_status ON tenants(status);
CREATE INDEX idx_tenants_code   ON tenants(tenant_code);

CREATE TABLE tenant_audits (
  id         VARCHAR(36) PRIMARY KEY,
  tenant_id  VARCHAR(36) NOT NULL,
  at         TIMESTAMP   NOT NULL,
  actor      VARCHAR(64) NOT NULL,
  action     VARCHAR(64) NOT NULL,
  detail     VARCHAR(1024)
);

CREATE INDEX idx_audits_tenant ON tenant_audits(tenant_id);

CREATE TABLE collect_jobs (
  id          VARCHAR(36) PRIMARY KEY,
  mode        VARCHAR(24) NOT NULL,
  source_name VARCHAR(128),
  status      VARCHAR(16) NOT NULL,
  total       INT NOT NULL DEFAULT 0,
  imported    INT NOT NULL DEFAULT 0,
  duplicated  INT NOT NULL DEFAULT 0,
  failed      INT NOT NULL DEFAULT 0,
  errors_json TEXT,
  created_at  TIMESTAMP NOT NULL,
  finished_at TIMESTAMP
);

CREATE INDEX idx_collect_jobs_created ON collect_jobs(created_at);
