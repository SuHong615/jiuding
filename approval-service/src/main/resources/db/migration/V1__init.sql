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

-- approval_db schema (docs/03-data-model.md). ANSI subset: H2 2.x (MODE=PostgreSQL) + PostgreSQL 16.

CREATE TABLE approvals (
  id            VARCHAR(36) PRIMARY KEY,
  tenant_id     VARCHAR(36) NOT NULL,
  tenant_code   VARCHAR(64) NOT NULL,
  plan          VARCHAR(16) NOT NULL,
  state         VARCHAR(32) NOT NULL,
  current_node  VARCHAR(32),
  risk_level    VARCHAR(8),
  risk_score    INT,
  auto_decided  BOOLEAN NOT NULL DEFAULT FALSE,
  decided_by    VARCHAR(64),
  decision_note VARCHAR(512),
  sla_due_at    TIMESTAMP,
  escalated     BOOLEAN NOT NULL DEFAULT FALSE,
  reasons_json  TEXT,
  created_at    TIMESTAMP NOT NULL,
  updated_at    TIMESTAMP NOT NULL,
  version       BIGINT NOT NULL DEFAULT 0
);

CREATE INDEX idx_approvals_state ON approvals (state);
CREATE INDEX idx_approvals_tenant ON approvals (tenant_id);

CREATE TABLE approval_nodes (
  id            VARCHAR(36) PRIMARY KEY,
  approval_id   VARCHAR(36) NOT NULL,
  seq           INT NOT NULL,
  node          VARCHAR(32) NOT NULL,
  node_type     VARCHAR(8) NOT NULL,
  state         VARCHAR(16) NOT NULL,
  assignee_role VARCHAR(32),
  decided_by    VARCHAR(64),
  decided_at    TIMESTAMP,
  comment       VARCHAR(512),
  sla_hours     INT
);

CREATE INDEX idx_approval_nodes_approval ON approval_nodes (approval_id);

CREATE TABLE approval_timeline (
  id          VARCHAR(36) PRIMARY KEY,
  approval_id VARCHAR(36) NOT NULL,
  at          TIMESTAMP NOT NULL,
  actor       VARCHAR(64) NOT NULL,
  action      VARCHAR(64) NOT NULL,
  detail      VARCHAR(1024)
);

CREATE INDEX idx_approval_timeline_approval ON approval_timeline (approval_id);
