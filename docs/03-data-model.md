# 03 · 数据模型（PostgreSQL 16 / H2 2.x 兼容）

三库分治（微服务数据自治），每服务独立 Flyway 迁移，**所有主键为 VARCHAR(36) UUID**，不使用自增序列，保证 H2/Postgres 双兼容。
- `tenant_db`：租户与采集
- `approval_db`：审批工作流
- `agent_db`：Skill 注册缓存、Agent 运行轨迹

> 本地无 Docker 时用 H2 文件库（`dev` profile），SQL 保持 ANSI 子集；Docker 下用 Postgres（`docker` profile）。

## tenant_db

```sql
CREATE TABLE tenants (
  id               VARCHAR(36) PRIMARY KEY,
  tenant_code      VARCHAR(64)  NOT NULL UNIQUE,
  name             VARCHAR(128) NOT NULL,
  contact_name     VARCHAR(64),
  contact_email    VARCHAR(128),
  contact_phone    VARCHAR(32),
  plan             VARCHAR(16)  NOT NULL,
  region           VARCHAR(32)  NOT NULL,
  isolation_mode   VARCHAR(24)  NOT NULL,
  status           VARCHAR(24)  NOT NULL,
  risk_level       VARCHAR(8),
  risk_score       INT,
  quota_seats      INT,
  quota_storage_gb INT,
  quota_api_rpm    INT,
  source           VARCHAR(16)  NOT NULL,
  attributes_json  TEXT,
  reject_reason    VARCHAR(512),
  created_at       TIMESTAMP    NOT NULL,
  updated_at       TIMESTAMP    NOT NULL,
  submitted_at     TIMESTAMP,
  decided_at       TIMESTAMP,
  activated_at     TIMESTAMP,
  version          BIGINT       NOT NULL DEFAULT 0
);
CREATE INDEX idx_tenants_status ON tenants(status);
CREATE INDEX idx_tenants_code   ON tenants(tenant_code);

CREATE TABLE tenant_audits (
  id         VARCHAR(36) PRIMARY KEY,
  tenant_id  VARCHAR(36) NOT NULL,
  at         TIMESTAMP   NOT NULL,
  actor      VARCHAR(64) NOT NULL,
  action     VARCHAR(64) NOT NULL,
  detail     VARCHAR(1024)
);

CREATE TABLE collect_jobs (
  id          VARCHAR(36) PRIMARY KEY,
  mode        VARCHAR(24) NOT NULL,
  source_name VARCHAR(128),
  status      VARCHAR(16) NOT NULL,
  total       INT NOT NULL DEFAULT 0,
  imported    INT NOT NULL DEFAULT 0,
  duplicated  INT NOT NULL DEFAULT 0,
  failed      INT NOT NULL DEFAULT 0,
  errors_json TEXT,
  created_at  TIMESTAMP NOT NULL,
  finished_at TIMESTAMP
);
```

## approval_db

```sql
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
CREATE INDEX idx_approvals_state ON approvals(state);

CREATE TABLE approval_nodes (
  id             VARCHAR(36) PRIMARY KEY,
  approval_id    VARCHAR(36) NOT NULL,
  seq            INT NOT NULL,
  node           VARCHAR(32) NOT NULL,
  node_type      VARCHAR(8) NOT NULL,   -- AUTO|HUMAN
  state          VARCHAR(16) NOT NULL,
  assignee_role  VARCHAR(32),
  decided_by     VARCHAR(64),
  decided_at     TIMESTAMP,
  comment        VARCHAR(512),
  sla_hours      INT
);

CREATE TABLE approval_timeline (
  id          VARCHAR(36) PRIMARY KEY,
  approval_id VARCHAR(36) NOT NULL,
  at          TIMESTAMP NOT NULL,
  actor       VARCHAR(64) NOT NULL,
  action      VARCHAR(64) NOT NULL,
  detail      VARCHAR(1024)
);
```

## agent_db

```sql
CREATE TABLE skill_registry (
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

CREATE TABLE agent_runs (
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
CREATE INDEX idx_agent_runs_type ON agent_runs(agent_type);

CREATE TABLE rule_hits (
  id         VARCHAR(36) PRIMARY KEY,
  run_id     VARCHAR(36) NOT NULL,
  skill_id   VARCHAR(64) NOT NULL,
  rule_id    VARCHAR(64) NOT NULL,
  severity   VARCHAR(8),
  message    VARCHAR(512),
  matched    BOOLEAN NOT NULL
);
```

## 多租户隔离策略

| 模式 | 说明 | 实现 |
|---|---|---|
| SHARED_SCHEMA | 共享表 + `tenant_id` 行隔离 | 应用层强制 `TenantContext.require()`，SQL 必须带 tenant_id |
| DEDICATED_SCHEMA | 每租户独立 schema | 预留（`isolation_mode` 字段 + 命名规则），原型阶段仅记录 |
| DEDICATED_DB | 每租户独立库 | 预留，同上 |

平台管理员（PLATFORM_ADMIN）可跨租户查询；其余角色只能看到 `TenantContext.tenantId()` 的数据。
写路径一律先 `TenantContext.require()`，缺失即 401（fail-closed）。

## v2 增量表（组织 / 账号 / 评级）

自 2026-11-08 起 tenant-service 增加 `V2__org_account_rating.sql`：

| 表 | 用途 | 关键字段 |
|---|---|---|
| `tenants`（ALTER） | 评级与软删 | `rating_grade, rating_score, rating_assessed_at, deleted_at` |
| `org_units` | 组织/部门树 | `tenant_id, parent_id, code, name, type, path, depth, sort_order, manager_account_id, status`；`UNIQUE(tenant_id, code)` |
| `accounts` | 租户内账号目录 | `tenant_id, org_unit_id, username, display_name, email, role, status, password_hash, password_salt, must_change_password, failed_attempts, last_login_at`；`UNIQUE(tenant_id, username)` |
| `tenant_ratings` | 评级历史（append-only） | `tenant_id, grade, score, dim_business/compliance/financial/security/service, source, assessed_by, note` |

完整 DDL 见 `docs/11-tenant-identity-org-rating.md` §3。组织树使用「邻接表 + 物化路径」；账号密码 PBKDF2-HMAC-SHA256(210k)+随机盐，禁止明文/可逆存储。

