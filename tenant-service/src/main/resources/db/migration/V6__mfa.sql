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

-- v6 MFA / TOTP (docs/16-v6-sso-mfa-scim.md section 3).
-- H2 / PostgreSQL compatible ANSI subset: VARCHAR primary keys, BOOLEAN, TIMESTAMP.

CREATE TABLE account_mfa (
  account_id          VARCHAR(36) PRIMARY KEY,
  tenant_id           VARCHAR(36) NOT NULL,
  secret_base32       VARCHAR(64) NOT NULL,
  enabled             BOOLEAN NOT NULL DEFAULT FALSE,
  recovery_codes_hash VARCHAR(1024),
  created_at          TIMESTAMP NOT NULL,
  confirmed_at        TIMESTAMP,
  last_used_at        TIMESTAMP
);

CREATE TABLE mfa_challenges (
  id         VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  tenant_id  VARCHAR(36) NOT NULL,
  code_hash  VARCHAR(128) NOT NULL,
  expires_at TIMESTAMP NOT NULL,
  attempts   INT NOT NULL DEFAULT 0,
  consumed_at TIMESTAMP
);

CREATE INDEX idx_mfa_challenges_account ON mfa_challenges(account_id, expires_at);
