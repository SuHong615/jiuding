# 15 · v5：技能中心、MCP 凭据与审计、自助入驻、角色权限、数据生命周期

> 状态：**已完成并验收**（2026-11-08）｜Owner：Lead｜关联：docs/02、docs/11、docs/13、docs/14
> 验收：**410 自动化测试全绿**；`tests/e2e-v5.ps1` **46/46 PASS**；`scripts/verify.ps1` **14/14 PASS**；k3s 生产化（2 副本/PDB/HPA/2 个 CronJob）实测；滚动重启 **0 失败**。

## 1. 目标

| # | 需求 | 验收 |
|---|---|---|
| G1 | **技能中心**：上传/修正/更新/审核/启停/版本与审计 | 上传 zip→DRAFT→提交审核→审核通过→热加载生效 |
| G2 | **Agent 运行可读**：说明在做什么、中文步骤标题、输入/输出摘要 | 运行详情可读；catalog 展示各 Agent 职责 |
| G3 | **MCP 控制台**：一键获取 MCP 配置、按人 API Key、调用审计、外部 MCP 配置 | Key CRUD + 明文仅一次 + 审计表 + 外部服务器注册 |
| G4 | **组织/账号导入**：模板下载、CSV/JSON 导入、配置 | 导入结果（成功/跳过/失败）+ 账号临时密码一次性返回 |
| G5 | **租户自助入驻**：登录页入口 → 填表 → Agent 合规审核 → 自动通过/人工决策 | 公开 API + 状态查询 + 应用记录 |
| G6 | **角色与权限管理**：后台配置角色与权限矩阵，租户管理员/平台管理员分权 | RBAC API + 权限矩阵 UI + 导航/操作按权限收敛 |
| G7 | **数据生命周期**：登录事件/审计/采集任务/软删租户保留策略 | 保留配置 + 每日清理 + 审计 |

## 2. 技能中心（agent-service）

### 2.1 数据（agent_db V2）
```sql
CREATE TABLE skill_packages (
  id VARCHAR(64) NOT NULL,
  version VARCHAR(32) NOT NULL,
  source VARCHAR(16) NOT NULL,       -- BUILTIN | UPLOADED
  status VARCHAR(24) NOT NULL,       -- DRAFT | PENDING_REVIEW | APPROVED | REJECTED | DISABLED
  manifest_json TEXT NOT NULL,
  instructions TEXT,
  rules_yaml TEXT,
  review_comment VARCHAR(512),
  reviewed_by VARCHAR(64),
  reviewed_at TIMESTAMP,
  created_by VARCHAR(64),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id, version)
);
CREATE TABLE skill_reviews (
  id VARCHAR(36) PRIMARY KEY,
  skill_id VARCHAR(64) NOT NULL,
  skill_version VARCHAR(32) NOT NULL,
  action VARCHAR(24) NOT NULL,       -- UPLOAD|UPDATE|SUBMIT|APPROVE|REJECT|ENABLE|DISABLE
  actor VARCHAR(64) NOT NULL,
  comment VARCHAR(512),
  at TIMESTAMP NOT NULL
);
```
- 内置技能（skills/ 目录）只读加载，source=BUILTIN；上传技能与内置 id 冲突返回 409。
- 注册表加载 = 内置 + `status=APPROVED && enabled=true` 的上传技能；审核/启停后自动 reload。

### 2.2 API
```
POST /api/skills/upload            multipart zip（含 SKILL.md、可选 rules.yaml） + submitForReview?
     → SkillView（DRAFT/PENDING_REVIEW）；校验 frontmatter/type/id/规则语法/大小 ≤1MB
PUT  /api/skills/{id}              {instructions?, rulesYaml?, description?, version} → 新版本 DRAFT（或更新 DRAFT）
POST /api/skills/{id}/submit-review
POST /api/skills/{id}/review       {decision: APPROVE|REJECT, comment}（平台管理员）
POST /api/skills/{id}/enable|disable
DELETE /api/skills/{id}            （仅 UPLOADED；逻辑删除 DISABLED）
GET  /api/skills?status=&source=&enabled=
GET  /api/skills/{id}/reviews
GET  /api/skills/template          → 下载技能包模板（zip：SKILL.md + rules.yaml + 说明）
```
`SkillView` 增加 `source/status/reviewComment/reviewedBy/reviewedAt/enabled`。

## 3. Agent 运行可读性（agent-service）

- `GET /api/agents/catalog` → 4 类 Agent：`{agentType, displayName, description, inputs, outputs, exampleInput}`
  - tenant-intake：租户准入校验（校验资料完整性并给出准入建议）
  - risk-review：租户风险审查（输出风险等级/评分/发现）
  - approval：审批策略（决定自动通过/人工节点/SLA）
  - provisioning：资源开通（生成命名空间/配额计划）
- `AgentRunView` 增加 `displayName/purpose/summary`；`Step` 增加 `title/actionLabel`（中文可读）。
- 运行摘要：`summary` 一句话（如"风险审查：LOW/12 分 → 建议自动通过"）。
- 前端 Agent 页面：运行列表显示"Agent 名称 + 目的 + 状态"；详情页顶部"本次运行在做什么"卡片（输入摘要/结论）；步骤时间线用中文标题，原始 JSON 折叠。

## 4. MCP 凭据、审计与外部 MCP（mcp-service + gateway）

### 4.1 数据（mcp_db V1）
```sql
CREATE TABLE mcp_api_keys (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL,
  key_prefix VARCHAR(12) NOT NULL, key_hash VARCHAR(128) NOT NULL,
  owner_user_id VARCHAR(64) NOT NULL, owner_username VARCHAR(64) NOT NULL,
  tenant_id VARCHAR(36), roles VARCHAR(256),
  status VARCHAR(16) NOT NULL,       -- ACTIVE|REVOKED|EXPIRED
  created_at TIMESTAMP NOT NULL, expires_at TIMESTAMP,
  last_used_at TIMESTAMP, revoked_at TIMESTAMP
);
CREATE TABLE mcp_call_audits (
  id VARCHAR(36) PRIMARY KEY, api_key_id VARCHAR(36), key_prefix VARCHAR(12),
  owner_username VARCHAR(64), tenant_id VARCHAR(36),
  tool VARCHAR(64) NOT NULL, request_id VARCHAR(64), session_id VARCHAR(64),
  success BOOLEAN NOT NULL, error_code INT, duration_ms BIGINT,
  ip VARCHAR(64), user_agent VARCHAR(256), at TIMESTAMP NOT NULL
);
CREATE TABLE mcp_external_servers (
  id VARCHAR(36) PRIMARY KEY, name VARCHAR(64) NOT NULL,
  transport VARCHAR(24) NOT NULL,    -- STREAMABLE_HTTP|SSE
  url VARCHAR(512) NOT NULL, headers_json TEXT,
  status VARCHAR(16) NOT NULL, created_by VARCHAR(64),
  created_at TIMESTAMP NOT NULL, last_check_at TIMESTAMP, last_check_ok BOOLEAN
);
```

### 4.2 API（JWT，经网关 `/api/mcp/**`）
```
GET    /api/mcp/config            → {endpoint, transport, protocolVersion, sampleConfig(JSON),
                                     keyHint, docs}
GET    /api/mcp/keys               → 当前用户 key 列表（平台管理员可 ?all=true）
POST   /api/mcp/keys {name, expiresInDays?} → {id, name, keyPrefix, apiKey（仅本次明文）, expiresAt}
DELETE /api/mcp/keys/{id}          → 撤销（本人或平台管理员）
GET    /api/mcp/audits?page=&size=&tool=&success=  → 本人调用审计（平台管理员全量）
GET    /api/mcp/external-servers   → 列表（平台管理员可写）
POST   /api/mcp/external-servers {name, transport, url, headers?}
DELETE /api/mcp/external-servers/{id}
POST   /api/mcp/external-servers/{id}/check   → 连通性探测（记录 last_check）
```
- `/mcp` 认证：`X-API-Key` 先查 DB（sha256 hash 比较，校验 ACTIVE/未过期），命中后每 `tools/call` 写审计（tool/success/errorCode/duration/ip/UA/requestId）；静态 env 配置 key 仍兼容（审计 key_prefix=`static`）。
- mcp-service 增加 JDBC/Flyway/H2/Postgres 依赖与 `mcp_db`；dev 用 H2 文件库。
- 网关新增代理前缀 `/api/mcp/**`、`/api/me/**`、`/api/platform/roles/**`、`/api/platform/permissions`。

## 5. 组织/账号导入（tenant-service）

```
GET  /api/tenants/{tid}/import-templates/org-units.csv
GET  /api/tenants/{tid}/import-templates/accounts.csv
POST /api/tenants/{tid}/org-units/import   multipart CSV 或 JSON {items:[{parentCode,code,name,type,sortOrder,managerUsername}]}
POST /api/tenants/{tid}/accounts/import      multipart CSV 或 JSON {items:[{username,displayName,email,phone,orgCode,role}]}
→ {total, imported, skipped, failed, errors:[{row,message}], tempPasswords:[{row,username,tempPassword}]}
```
规则：≤1000 行；按 parentCode 拓扑建组织；已存在 code/username 跳过；角色校验（不可越权）；失败行不中断；权限=平台/租户管理员（ORG_ADMIN 不允许批量导入）。
迁移 `V4__onboarding_import.sql`（tenant_db）：`onboarding_applications` + 导入审计无需新表。

## 6. 租户自助入驻（tenant-service + 登录页）

### 6.1 数据（同 V4）
```sql
CREATE TABLE onboarding_applications (
  id VARCHAR(36) PRIMARY KEY, application_no VARCHAR(32) NOT NULL UNIQUE,
  company_name VARCHAR(128) NOT NULL, tenant_code VARCHAR(64) NOT NULL,
  contact_name VARCHAR(64), contact_email VARCHAR(128), contact_phone VARCHAR(32),
  plan VARCHAR(16), region VARCHAR(32), industry VARCHAR(64), notes VARCHAR(512),
  status VARCHAR(24) NOT NULL,       -- SUBMITTED|AGENT_REVIEW|PENDING_MANUAL|ACTIVATED|REJECTED
  tenant_id VARCHAR(36),
  agent_decision VARCHAR(24), agent_risk_level VARCHAR(8), agent_score INT, agent_reasons TEXT,
  reviewed_by VARCHAR(64), reviewed_at TIMESTAMP,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
```

### 6.2 公开 API（无需认证，IP 限流 5 次/分钟）
```
POST /api/public/onboarding
  {companyName, tenantCode?, contactName, contactEmail, contactPhone, plan, region, industry, notes}
→ {applicationNo, tenantCode, status, message}
GET  /api/public/onboarding/{applicationNo}   → 状态与人工审核提示（不含内部细节）
```
流程：
1. 创建 application + 租户（DRAFT，source=SELF_SERVICE）+ 根组织；
2. 调 `risk-review` skill（失败降级本地规则）；记录 agent 决策/等级/评分/原因；
3. **合规（LOW 且非 ENTERPRISE）→ 自动提交审批并激活（ACTIVATED）**；否则提交审批进入人工（PENDING_MANUAL）；
4. 状态查询时根据租户状态归并（ACTIVE→ACTIVATED，REJECTED→REJECTED）；
5. 人工审批沿用 approval-service 现有流程（approver 决策）。
白名单：网关与 tenant-service 的 `/api/public/**`。

## 7. 角色与权限管理（tenant-service + 后台页面）

### 7.1 数据（tenant_db V5）
```sql
CREATE TABLE permissions (code VARCHAR(64) PRIMARY KEY, name VARCHAR(64), group_code VARCHAR(32), description VARCHAR(256));
CREATE TABLE roles (code VARCHAR(64) PRIMARY KEY, name VARCHAR(64), scope VARCHAR(16), builtin BOOLEAN, status VARCHAR(16), description VARCHAR(256));
CREATE TABLE role_permissions (role_code VARCHAR(64), permission_code VARCHAR(64), PRIMARY KEY (role_code, permission_code));
```
内置角色：PLATFORM_ADMIN（全部）、TENANT_ADMIN（本租户组织/账号/评级/审批只读）、ORG_ADMIN（子树组织/账号）、AUDITOR（只读+审计+登录历史）、MEMBER（本人）。
权限组：TENANT / ORG / ACCOUNT / RATING / APPROVAL / SKILL / AGENT / MCP / PLATFORM / RBAC / ONBOARDING。

### 7.2 API
```
GET  /api/me/permissions              → {roles:[], permissions:[]}（任务：当前用户权限码；平台管理员 *）
GET  /api/platform/permissions        → 权限目录（平台管理员）
GET  /api/platform/roles?scope=       → 角色列表
POST /api/platform/roles {code,name,scope,description}     → 自定义角色
PUT  /api/platform/roles/{code}/permissions {permissionCodes[]}
DELETE /api/platform/roles/{code}     → 仅非内置
```
- 网关把 `/api/platform/roles/**`、`/api/platform/permissions`、`/api/me/**` 代理到 tenant-service（覆盖 `/api/platform/overview|health` 的本地上报路由需精确前缀匹配）。
- 前端：新增「权限管理」页（平台管理员）：角色列表 + 权限矩阵（按组勾选）+ 新建自定义角色；导航/按钮按 `/api/me/permissions` 收敛（无权限隐藏）。
- 强制校验：现有角色检查保留；新增管理端点仅 PLATFORM_ADMIN；租户管理员可管理本租户组织/账号（现有 RBAC）。

## 8. 数据生命周期（tenant-service）

配置：
```
tenancy.retention.login-event-days=90
tenancy.retention.audit-days=365
tenancy.retention.collect-job-days=90
tenancy.retention.soft-deleted-tenant-days=180
tenancy.retention.auto-purge-enabled=true
```
- `@Scheduled`（每日 04:00）清理：过期登录事件、审计、采集任务；软删超期租户硬删除（级联 org/accounts/ratings/audits，审计 `TENANT_PURGED`）。
- `POST /internal/maintenance/purge`（内部 token，供 k8s CronJob）→ {loginEvents, audits, collectJobs, tenants}。
- 测试：过期/未过期边界、软删保留期、级联删除。

## 9. 验收标准

- 全量测试 ≥ 380 全绿；
- 技能：上传→审核→启用→Agent/skill.run 生效；拒绝/禁用路径；
- Agent：catalog + 运行摘要 + 中文步骤；
- MCP：创建 key→用 key 调 tools/call→审计可查→撤销后 401；外部 MCP 服务器 CRUD；config 可复制；
- 导入：组织/账号 CSV 导入结果正确、模板可下载；
- 入驻：公开提交→Agent 审核→合规自动激活 / 不合规进入人工；
- 角色权限：权限矩阵保存生效、/api/me/permissions 正确、UI 按权限收敛；
- 生命周期：保留配置与 purge 生效；
- 部署：k8s mcp_db + 新镜像滚动、E2E v5 通过。

## 10. 验收结果（2026-11-08）

| 项 | 结果 |
|---|---|
| 自动化测试 | **410 全绿**（tenant 137 / agent 66 / mcp 45 / gateway 39 / cli 68 / approval 35 / common 18 / kernel 2） |
| v5 E2E | **46/46 PASS**（导入/入驻/技能/MCP/RBAC/purge） |
| 统一验证 | **14/14 PASS**（v1/v2/v3/v5 E2E + MCP） |
| 数据库 | tenant_db Flyway **V5**；mcp_db Flyway **V1**（3 表）；agent_db **V2**（技能中心） |
| 技能中心 | 上传 zip → DRAFT → 送审 → PENDING_REVIEW → APPROVE → enabled → 热加载；重复上传 409；拒绝分支可用 |
| Agent 可读性 | `/api/agents/catalog` 4 类；运行摘要 + 中文步骤标题 + 输入/输出 |
| MCP | 按人 `tk_` Key（明文仅一次、sha256 存储、撤销 401）；`tools/list` 22 工具；`tools/call` 审计入库；外部 MCP 服务器 CRUD；配置样例可复制 |
| 入驻 | 合规 LOW 自动 ACTIVATED；非合规 PENDING_MANUAL 且审批可见；申请编号可查询；IP 限流 |
| 导入 | 组织/账号 CSV 模板 + 导入（重复跳过、一次性临时密码、越权角色行失败、ORG_ADMIN 403） |
| RBAC | 43 权限 / 11 组 / 5 内置角色；`/api/me/permissions`；角色 CRUD + 权限矩阵；越权 403 |
| 生命周期 | `POST /internal/maintenance/purge` 返回计数；CronJob 每日 04:00 UTC 实测 `{"loginEvents":0,...}` |
| 生产化 | 6 服务 2/2（tenant HPA 3/3）；PDB ×6；HPA ×2（min2/max3）；CronJob ×2；零中断 failures=0 |

## 11. 后续（本文件外）

- CLI/MCP v4 能力已补齐（解锁/改密/登录历史/审计导出/评级 delta；技能/MCP key/入驻/导入命令）
- OIDC/SSO + MFA + SCIM（v6，下一增量）

## 12. 技能中心 UI 修正（v5.1，2026-11-08）

按评审意见调整为「技能管理」形态：

- **列表改为卡片分块 + 分页**（每页 9/12 个，不用表格/列表）：卡片展示名称、描述、类型、状态徽章、启用状态、最新版本、更新时间、快捷操作；
- **单个 Skill 详情页 = 页签结构**：
  1. **内容**：SKILL.md 正文 + manifest（id/name/type/triggers/tools/description）；
  2. **代码**：rules.yaml 源码查看/编辑、保存（DRAFT/REJECTED）、另存新版本；
  3. **审查**：状态、提交审核、通过/驳回（平台管理员）+ 审核历史时间线；
  4. **调试**：输入样例 JSON → 运行规则 → 展示命中规则/决策/动作/耗时（不落库）；
  5. **版本**：全部版本（状态/启用/审核人/时间），可查看任意版本内容、基于版本创建新版本；
  6. **启用**：启用/停用开关、热加载说明、当前生效版本。
- 后端补充：
  - `GET /api/skills/{id}/versions`、`GET /api/skills/{id}/versions/{version}`
  - `POST /api/skills/{id}/debug {input, version?}` → `{version, decision, actions, matchedRules[], durationMs, notes[]}`
- 验收：卡片分页渲染、六个页签数据与操作可用、调试结果与规则一致、版本切换正确、构建 0 错误。
- **实测（2026-11-08）**：前端 `npm run build` 0 错误（dist 438.5KB）；无头浏览器页签/交互 14/14 PASS；`tests/e2e-skill.ps1` **13/13 PASS**（版本 v1.0.0/v1.1.0、旧版本详情、debug 命中/未命中、404）；`scripts/verify.ps1` 纳入技能版本链，总 **16/16 PASS**。
