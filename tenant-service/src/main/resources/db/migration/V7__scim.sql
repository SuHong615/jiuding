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

-- v6 SCIM 2.0 tokens (docs/16-v6-sso-mfa-scim.md section 7.1).
-- H2 / PostgreSQL compatible ANSI subset.

CREATE TABLE scim_tokens (
  id           VARCHAR(36) PRIMARY KEY,
  tenant_id    VARCHAR(36) NOT NULL,
  name         VARCHAR(64),
  token_prefix VARCHAR(16) NOT NULL,
  token_hash   VARCHAR(128) NOT NULL,
  status       VARCHAR(16) NOT NULL,
  created_by   VARCHAR(64),
  created_at   TIMESTAMP NOT NULL,
  last_used_at TIMESTAMP,
  revoked_at   TIMESTAMP
);

CREATE INDEX idx_scim_tokens_tenant ON scim_tokens(tenant_id, status);
CREATE INDEX idx_scim_tokens_hash ON scim_tokens(token_hash);
