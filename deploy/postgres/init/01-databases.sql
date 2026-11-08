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

-- 01-databases.sql
-- Executed once by the postgres:16-alpine entrypoint when the data volume is empty.
-- One database per service (microservice data autonomy, see docs/03-data-model.md).
-- The owner is POSTGRES_USER (the connecting application user), so no extra GRANT is needed.

CREATE DATABASE tenant_db;
CREATE DATABASE approval_db;
CREATE DATABASE agent_db;
CREATE DATABASE mcp_db;

-- Sanity output visible in `docker compose logs postgres`.
SELECT datname FROM pg_database WHERE datname IN ('tenant_db', 'approval_db', 'agent_db', 'mcp_db');
