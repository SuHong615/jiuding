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

-- mcp_db V1: personal MCP API keys, call audits, external MCP servers (docs/15 §4.1)
-- ANSI subset, H2 (MODE=PostgreSQL) and PostgreSQL 16 compatible.

CREATE TABLE mcp_api_keys (
  id               VARCHAR(36) PRIMARY KEY,
  name             VARCHAR(64) NOT NULL,
  key_prefix       VARCHAR(12) NOT NULL,
  key_hash         VARCHAR(128) NOT NULL,
  owner_user_id    VARCHAR(64) NOT NULL,
  owner_username   VARCHAR(64) NOT NULL,
  tenant_id        VARCHAR(36),
  roles            VARCHAR(256),
  status           VARCHAR(16) NOT NULL,
  created_at       TIMESTAMP NOT NULL,
  expires_at       TIMESTAMP,
  last_used_at     TIMESTAMP,
  revoked_at       TIMESTAMP
);
CREATE INDEX idx_mcp_keys_hash ON mcp_api_keys(key_hash);
CREATE INDEX idx_mcp_keys_owner ON mcp_api_keys(owner_user_id);

CREATE TABLE mcp_call_audits (
  id               VARCHAR(36) PRIMARY KEY,
  api_key_id       VARCHAR(36),
  key_prefix       VARCHAR(12),
  owner_username   VARCHAR(64),
  tenant_id        VARCHAR(36),
  tool             VARCHAR(64) NOT NULL,
  request_id       VARCHAR(64),
  session_id       VARCHAR(64),
  success          BOOLEAN NOT NULL,
  error_code       INT,
  duration_ms      BIGINT,
  ip               VARCHAR(64),
  user_agent       VARCHAR(256),
  at               TIMESTAMP NOT NULL
);
CREATE INDEX idx_mcp_audits_at ON mcp_call_audits(at);
CREATE INDEX idx_mcp_audits_owner ON mcp_call_audits(owner_username);
CREATE INDEX idx_mcp_audits_tool ON mcp_call_audits(tool);

CREATE TABLE mcp_external_servers (
  id               VARCHAR(36) PRIMARY KEY,
  name             VARCHAR(64) NOT NULL,
  transport        VARCHAR(24) NOT NULL,
  url              VARCHAR(512) NOT NULL,
  headers_json     TEXT,
  status           VARCHAR(16) NOT NULL,
  created_by       VARCHAR(64),
  created_at       TIMESTAMP NOT NULL,
  last_check_at    TIMESTAMP,
  last_check_ok    BOOLEAN
);
