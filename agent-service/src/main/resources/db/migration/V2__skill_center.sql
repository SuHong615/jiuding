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

-- agent_db V2: skill center (packages + review audit), docs/15 §2.1.
-- H2 2.x / PostgreSQL 16 compatible (ANSI subset, no sequences).

CREATE TABLE IF NOT EXISTS skill_packages (
  id             VARCHAR(64)  NOT NULL,
  version        VARCHAR(32)  NOT NULL,
  source         VARCHAR(16)  NOT NULL,
  status         VARCHAR(24)  NOT NULL,
  manifest_json  TEXT         NOT NULL,
  instructions   TEXT,
  rules_yaml     TEXT,
  review_comment VARCHAR(512),
  reviewed_by    VARCHAR(64),
  reviewed_at    TIMESTAMP,
  created_by     VARCHAR(64),
  created_at     TIMESTAMP    NOT NULL,
  updated_at     TIMESTAMP    NOT NULL,
  enabled        BOOLEAN      NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id, version)
);

CREATE INDEX IF NOT EXISTS idx_skill_packages_status ON skill_packages(status);
CREATE INDEX IF NOT EXISTS idx_skill_packages_source ON skill_packages(source);

CREATE TABLE IF NOT EXISTS skill_reviews (
  id            VARCHAR(36) PRIMARY KEY,
  skill_id      VARCHAR(64) NOT NULL,
  skill_version VARCHAR(32) NOT NULL,
  action        VARCHAR(24) NOT NULL,
  actor         VARCHAR(64) NOT NULL,
  comment       VARCHAR(512),
  at            TIMESTAMP   NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_skill_reviews_skill ON skill_reviews(skill_id);
