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

-- v4 account security / compliance (docs/14-v4-security-compliance-production.md section 2).
-- H2 / PostgreSQL compatible ANSI subset: VARCHAR(36) UUID primary keys, no sequences.

CREATE TABLE account_login_events (
  id         VARCHAR(36) PRIMARY KEY,
  tenant_id  VARCHAR(36) NOT NULL,
  account_id VARCHAR(36),
  username   VARCHAR(64) NOT NULL,
  at         TIMESTAMP   NOT NULL,
  ip         VARCHAR(64),
  user_agent VARCHAR(256),
  success    BOOLEAN     NOT NULL,
  reason     VARCHAR(64)
);

CREATE INDEX idx_login_events_tenant_time ON account_login_events(tenant_id, at);
CREATE INDEX idx_login_events_account_time ON account_login_events(account_id, at);
