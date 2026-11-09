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

-- v12 workflow orchestration (docs/23 section 3).
-- One row per definition version: a code may have several versions, only one of
-- them can be PUBLISHED at a time (enforced by WorkflowDefinitionService); older
-- published versions are moved to ARCHIVED. (code, version) is unique because a
-- globally unique code would make version archiving impossible.

CREATE TABLE workflow_definitions (
  id              VARCHAR(36) PRIMARY KEY,
  code            VARCHAR(64) NOT NULL,
  name            VARCHAR(128),
  version         INT NOT NULL,
  status          VARCHAR(16) NOT NULL,
  definition_json TEXT,
  created_by      VARCHAR(64),
  created_at      TIMESTAMP NOT NULL,
  updated_at      TIMESTAMP NOT NULL,
  published_at    TIMESTAMP
);

CREATE UNIQUE INDEX uk_workflow_definitions_code_version ON workflow_definitions (code, version);
CREATE INDEX idx_workflow_definitions_code ON workflow_definitions (code);
CREATE INDEX idx_workflow_definitions_status ON workflow_definitions (status);
