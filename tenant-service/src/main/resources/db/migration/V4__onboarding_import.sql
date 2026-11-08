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

-- v5 self-service onboarding (docs/15-v5-skill-mcp-onboarding-rbac.md section 6.1).
-- H2 / PostgreSQL compatible ANSI subset: application generated VARCHAR(36) ids, no sequences.

CREATE TABLE onboarding_applications (
  id               VARCHAR(36) PRIMARY KEY,
  application_no   VARCHAR(32) NOT NULL UNIQUE,
  company_name     VARCHAR(128) NOT NULL,
  tenant_code      VARCHAR(64) NOT NULL,
  contact_name     VARCHAR(64),
  contact_email    VARCHAR(128),
  contact_phone    VARCHAR(32),
  plan             VARCHAR(16),
  region           VARCHAR(32),
  industry         VARCHAR(64),
  notes            VARCHAR(512),
  status           VARCHAR(24) NOT NULL,
  tenant_id        VARCHAR(36),
  agent_decision   VARCHAR(24),
  agent_risk_level VARCHAR(8),
  agent_score      INT,
  agent_reasons    TEXT,
  reviewed_by      VARCHAR(64),
  reviewed_at      TIMESTAMP,
  created_at       TIMESTAMP NOT NULL,
  updated_at       TIMESTAMP NOT NULL
);

CREATE INDEX idx_onboarding_tenant ON onboarding_applications(tenant_id);
CREATE INDEX idx_onboarding_created ON onboarding_applications(created_at);
