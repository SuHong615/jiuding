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

-- agent_db schema (H2 2.x / PostgreSQL 16 compatible, ANSI subset).
-- Skill registry cache, agent run traces and rule hit audit records.

CREATE TABLE IF NOT EXISTS skill_registry (
  id           VARCHAR(64) PRIMARY KEY,
  name         VARCHAR(128) NOT NULL,
  version      VARCHAR(32) NOT NULL,
  type         VARCHAR(24) NOT NULL,
  description  VARCHAR(1024),
  manifest_raw TEXT,
  enabled      BOOLEAN NOT NULL DEFAULT TRUE,
  path         VARCHAR(512),
  loaded_at    TIMESTAMP NOT NULL
);

CREATE TABLE IF NOT EXISTS agent_runs (
  run_id       VARCHAR(36) PRIMARY KEY,
  agent_type   VARCHAR(32) NOT NULL,
  tenant_id    VARCHAR(36),
  status       VARCHAR(16) NOT NULL,
  model        VARCHAR(64),
  input_json   TEXT,
  output_json  TEXT,
  steps_json   TEXT,
  tokens_used  INT NOT NULL DEFAULT 0,
  duration_ms  BIGINT NOT NULL DEFAULT 0,
  created_at   TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_agent_runs_type ON agent_runs(agent_type);

CREATE TABLE IF NOT EXISTS rule_hits (
  id         VARCHAR(36) PRIMARY KEY,
  run_id     VARCHAR(36) NOT NULL,
  skill_id   VARCHAR(64) NOT NULL,
  rule_id    VARCHAR(64) NOT NULL,
  severity   VARCHAR(8),
  message    VARCHAR(512),
  matched    BOOLEAN NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_rule_hits_run ON rule_hits(run_id);
