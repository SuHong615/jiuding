# 02 · API 契约（唯一事实源）

所有服务统一：`Content-Type: application/json;charset=UTF-8`，统一响应包：
```json
{ "code": 0, "message": "ok", "data": {}, "traceId": "uuid|null", "timestamp": "2026-11-07T12:00:00Z" }
```
错误码见 `tenancy-common` 的 `ErrorCode`。认证：`Authorization: Bearer <JWT>`；MCP 机器调用：`X-API-Key`。
租户头：`X-Tenant-Id`（非平台管理员必须与 JWT 内 tenantId 一致）。请求追踪：`X-Request-Id`（缺失自动生成，回显响应头）。

## 0. 端口与路由

| 组件 | 端口 | 说明 |
|---|---|---|
| gateway | 8080 | 唯一对外入口，JWT 签发、聚合、路由 |
| tenant-service | 8081 | 租户生命周期 + 采集 |
| approval-service | 8082 | 审批工作流 + 自动化 |
| agent-service | 8083 | AgentScope 内核、Skill 注册与规则执行 |
| mcp-service | 8084 | MCP Server（JSON-RPC） |
| frontend | 5173(dev)/8088(docker nginx) | SPA |

网关路由前缀：`/api/tenants/**`→8081，`/api/approvals/**`→8082，`/api/skills/**`、`/api/agents/**`→8083，`/mcp/**`→8084，`/api/auth/**`、`/api/platform/**` 由网关本地处理。

## 1. 认证（gateway 本地）

预置用户（env `TENANCY_USERS` 可覆盖，JSON 数组）：

| username | password | roles | tenantId |
|---|---|---|---|
| admin | Admin@123 | PLATFORM_ADMIN | null |
| approver | Approver@123 | APPROVER | null |
| operator | Operator@123 | TENANT_ADMIN | t-acme |
| auditor | Auditor@123 | AUDITOR | null |

```
POST /api/auth/login
{ "username": "admin", "password": "Admin@123" }
→ { "token": "<jwt>", "tokenType": "Bearer", "expiresIn": 7200,
    "principal": { "userId": "u-admin", "username": "admin", "tenantId": null, "roles": ["PLATFORM_ADMIN"] } }

GET /api/auth/me → principal
GET /api/platform/overview → { tenants:{total,active,pending}, approvals:{pending,autoApproved,rejected}, skills:{total}, agents:{runs,todayRuns}, services:[{name,status,latencyMs}] }
GET /api/platform/health → [{ name, url, status: UP/DOWN, latencyMs }]
```

## 2. tenant-service（8081）

### DTO
```java
record TenantCreateRequest(
  @NotBlank String tenantCode, @NotBlank String name,
  String contactName, @Email String contactEmail, String contactPhone,
  String plan,            // FREE|PRO|ENTERPRISE，默认 PRO
  String region,          // 默认 cn-shanghai
  String isolationMode,   // SHARED_SCHEMA|DEDICATED_SCHEMA|DEDICATED_DB，默认 SHARED_SCHEMA
  Integer quotaSeats, Integer quotaStorageGb, Integer quotaApiRpm,
  String source,          // MANUAL|IMPORT|API|DISCOVERY，默认 MANUAL
  Map<String,Object> attributes,
  Boolean autoSubmit)     // true → 创建后自动走提交

record TenantView(String id, String tenantCode, String name, String contactName, String contactEmail,
  String contactPhone, String plan, String region, String isolationMode, String status,
  String riskLevel, Integer riskScore, Integer quotaSeats, Integer quotaStorageGb, Integer quotaApiRpm,
  String source, String rejectReason, String createdAt, String updatedAt,
  String submittedAt, String decidedAt, String activatedAt, long version)

record CollectJobView(String id, String mode, String sourceName, String status,
  int total, int imported, int duplicated, int failed, List<String> errors,
  String createdAt, String finishedAt)
```

### 状态机
`DRAFT → SUBMITTED → RISK_REVIEW → PENDING_APPROVAL → APPROVED → PROVISIONING → ACTIVE`；
旁支：`REJECTED`（可从 RISK_REVIEW/PENDING_APPROVAL）、`SUSPENDED`（ACTIVE）、`TERMINATED`（任意非 TERMINATED）。

### 接口
```
POST   /api/tenants                      TenantCreateRequest → TenantView
GET    /api/tenants?status=&plan=&keyword=&page=0&size=20 → PageResult<TenantView>
GET    /api/tenants/{id}                 → TenantView
PUT    /api/tenants/{id}                 TenantCreateRequest → TenantView（仅 DRAFT/REJECTED）
POST   /api/tenants/{id}/submit          → TenantView（DRAFT/REJECTED → SUBMITTED，同步调用 approval-service）
POST   /api/tenants/{id}/suspend         {"reason":"..."} → TenantView（仅 ACTIVE）
POST   /api/tenants/{id}/activate        → TenantView（SUSPENDED → ACTIVE）
POST   /api/tenants/{id}/terminate       {"reason":"..."} → TenantView
GET    /api/tenants/{id}/audits          → [{at, actor, action, detail}]
POST   /api/tenants/collect              {sourceName, mode, autoSubmit, records:[TenantCreateRequest]} → CollectJobView
POST   /api/tenants/collect/csv          multipart(file) + sourceName → CollectJobView
GET    /api/tenants/collect/jobs?page=&size= → PageResult<CollectJobView>
GET    /api/tenants/collect/jobs/{id}    → CollectJobView
GET    /api/tenants/overview             → {total, byStatus:{}, byPlan:{}, byRisk:{}, active, pending}
```

### 内部接口（`X-Internal-Token` 校验，仅供服务间调用）
```
GET   /internal/tenants/{id}             → TenantView
POST  /internal/tenants/{id}/status      {"status":"APPROVED","reason":"auto"} → TenantView
PATCH /internal/tenants/{id}/risk        {"riskLevel":"HIGH","riskScore":75} → TenantView
```

## 3. approval-service（8082）

### DTO
```java
record ApprovalCreateRequest(String tenantId, String tenantCode, String plan,
  String riskLevel, Integer riskScore, List<String> reasons, String requestedBy)

record ApprovalView(String id, String tenantId, String tenantCode, String state,
  String currentNode, String riskLevel, Integer riskScore, boolean autoDecided,
  String decidedBy, String decisionComment, String slaDueAt,
  List<NodeView> nodes, List<TimelineItem> timeline,
  String createdAt, String updatedAt)

record NodeView(String node, String type /*AUTO|HUMAN*/, String state /*PENDING|APPROVED|REJECTED|SKIPPED*/,
  String assigneeRole, String decidedBy, String decidedAt, String comment, Integer slaHours)

record TimelineItem(String at, String actor, String action, String detail)
```
### 状态机
`RISK_CHECK → AUTO_DECISION → (AUTO_APPROVED | PENDING_APPROVAL) → (APPROVED | REJECTED) → PROVISIONING → ACTIVE`
自动决策规则（由 agent-service 的 `approval-policy` skill 计算，失败时本地兜底）：
`riskLevel=LOW && plan!=ENTERPRISE` → AUTO_APPROVE；`MEDIUM` → L1 人工；`HIGH` → L2 人工 + 会签。

### 接口
```
POST /api/approvals                          ApprovalCreateRequest → ApprovalView
GET  /api/approvals?state=&page=&size=       → PageResult<ApprovalView>
GET  /api/approvals/pending                  → List<ApprovalView>
GET  /api/approvals/{id}                     → ApprovalView（含 nodes/timeline）
POST /api/approvals/{id}/decisions           {"decision":"APPROVE|REJECT","comment":"..."} → ApprovalView
POST /api/approvals/{id}/escalate            {"reason":"..."} → ApprovalView（SLA 升级到上级角色）
GET  /api/approvals/overview                 → {pending, autoApproved, approved, rejected, slaBreached}
```
最终 APPROVED 后：调用 tenant-service `/internal/tenants/{id}/status` 置 `APPROVED` → 调 agent-service `provisioning` skill → 置 `ACTIVE`。
定时任务（30s）扫描超 SLA 的 PENDING_APPROVAL 自动 escalate（`escalated=true`，不重复升级）。

## 4. agent-service（8083）

### 接口
```
GET  /api/skills                          → List<SkillView>
GET  /api/skills/{id}                     → SkillDetail（manifest + rules）
POST /api/skills/reload                   → {reloaded, skills:[]}
POST /api/skills/{id}/run                 {"input":{}, "tenantId":"..."} → SkillRunResult
POST /api/agents/runs                     {"agentType":"tenant-intake|risk-review|approval|provisioning","tenantId":"...","input":{},"maxSteps":6} → AgentRunView
GET  /api/agents/runs/{runId}             → AgentRunView
GET  /api/agents/runs?agentType=&page=    → PageResult<AgentRunView>
GET  /api/agents/tools                    → [{name, description, parameters(JSON Schema)}]
GET  /api/agents/overview                 → {runs, todayRuns, skills, models}
```
```java
record SkillView(String id, String name, String version, String type, String description,
  List<String> triggers, List<String> tools, boolean enabled, String path)
record SkillRunResult(String skillId, String runId, String verdict /*PASS|WARN|FAIL*/,
  int score, String decision /*AUTO_APPROVE|NEEDS_REVIEW|REJECT|NONE*/,
  List<Finding> findings, List<Action> actions, List<TraceItem> trace, long durationMs, String model)
record AgentRunView(String runId, String agentType, String status /*RUNNING|SUCCEEDED|FAILED*/,
  Object output, List<Step> steps, String model, int tokensUsed, long durationMs, String createdAt)
record Step(int index, String thought, String action, String actionInput, String observation)
```

## 5. mcp-service（8084）

JSON-RPC 2.0，`POST /mcp`（同步 JSON 响应）、`GET /mcp`（SSE 事件流）、`GET /mcp/info`（能力快照，便于前端展示）。
认证：`X-API-Key`（env `TENANCY_SECURITY_API_KEYS`，默认 `dev-mcp-key`）。工具/资源/提示词清单见 `05-mcp-spec.md`。

## 6. 关键链路（E2E 验收）

1. `POST /api/auth/login` (admin) → token
2. `POST /api/tenants` 创建租户（autoSubmit=false）→ DRAFT
3. `POST /api/tenants/{id}/submit` → approval-service 建单 → agent-service `risk-review` skill → 返回 `PENDING_APPROVAL`（MEDIUM）或自动通过（LOW）
4. `POST /api/approvals/{id}/decisions` APPROVE（approver）→ tenant 变 ACTIVE
5. MCP `tools/call` `tenant.list` 能查到该租户
6. CLI `tenancy tenant list` 输出该租户
7. 前端 Dashboard/Tenants/Approvals 展示一致数据

## 7. v2 增量契约（组织 / 账号 / 评级 / 软删除）

自 2026-11-08 起，tenant-service 增加租户身份域能力。**完整定义（DTO/路径/规则/RBAC/错误语义）以 `docs/11-tenant-identity-org-rating.md` §6 为准**，本节仅索引：

| 资源 | 前缀 | 能力 |
|---|---|---|
| 租户 | `DELETE /api/tenants/{id}` | 软删除（仅 PLATFORM_ADMIN、非 ACTIVE） |
| 组织 | `/api/tenants/{tenantId}/org-units` | tree/flat 列表、增删改、move（防环/深度≤6） |
| 账号 | `/api/tenants/{tenantId}/accounts` | 分页、增删改、启停、重置密码（一次性临时密码） |
| 评级 | `/api/tenants/{tenantId}/ratings` | current/history/assess/override（AAA~D，五维） |
| 内部 | `/internal/tenants/{id}/rating`、`/accounts/count` | 供 approval/agent 使用 |

`TenantView` 新增字段：`ratingGrade, ratingScore, orgUnitCount, accountCount, deletedAt`；`SkillManifest.type` 新增 `RATING`（配套 `skills/tenant-rating`）。

## 8. v3 增量：租户账号登录（详见 docs/13）

- `POST /api/auth/login` body 增加可选 `tenantCode`；平台用户与租户账号双通道；租户账号登录要求租户 `ACTIVE`、账号 `ACTIVE`；
- JWT 增加 `oid`（organization unit id）；principal 返回 `orgUnitId/mustChangePassword/authType`；
- 五档 RBAC：PLATFORM_ADMIN / TENANT_ADMIN / ORG_ADMIN（子树）/ AUDITOR（跨租户只读）/ MEMBER（只读本人）；
- 完整契约见 `docs/13-v3-account-login-rbac.md` §2/§3。

## 9. v4 增量（账号安全/合规/评级增量，详见 docs/14）

- `POST /api/tenants/{tid}/accounts/{id}/unlock`（锁定解锁）
- `POST /api/tenants/{tid}/accounts/{id}/change-password`（本人改密）
- `GET  /api/tenants/{tid}/accounts/{id}/login-events`（登录审计，含 IP/UA/结果）
- `GET  /api/tenants/{tid}/audits/export?format=csv&from=&to=`（审计 CSV）
- 账号列表/详情 `mask=true`（PII 掩码；AUDITOR 强制）
- 评级响应增加 `previousGrade/previousScore/scoreDelta/gradeChanged`
- `POST /internal/ratings/refresh`（内部 token，供 k8s CronJob）
- 网关登录限流：超限 **429 / 42900**

## 10. v5 增量（技能中心/MCP 凭据/入驻/导入/RBAC，详见 docs/15）

- 技能：`POST /api/skills/upload`（zip）、`PUT /api/skills/{id}`、`submit-review`、`review`、`enable|disable`、`GET /api/skills/template`
- Agent：`GET /api/agents/catalog`；运行视图含 displayName/purpose/summary/中文步骤
- MCP：`GET /api/mcp/config`、`GET|POST|DELETE /api/mcp/keys`、`GET /api/mcp/audits`、`/api/mcp/external-servers`
- 入驻（公开）：`POST /api/public/onboarding`、`GET /api/public/onboarding/{applicationNo}`
- 导入：`GET /api/tenants/{tid}/import-templates/{org-units|accounts}.csv`、`POST .../org-units/import`、`POST .../accounts/import`
- RBAC：`GET /api/me/permissions`、`GET /api/platform/permissions`、`GET|POST /api/platform/roles`、`PUT /api/platform/roles/{code}/permissions`、`DELETE /api/platform/roles/{code}`
- 生命周期：`POST /internal/maintenance/purge`（内部令牌）

## 11. v6 增量（OIDC/SSO 与 MFA，详见 docs/16）

- `GET /api/auth/oidc/status|authorize|callback`（默认关闭；配置 `TENANCY_OIDC_*` 后启用，回调 302 携带 token/error）
- `POST /api/auth/login/mfa {challengeToken, code}`（第二因子登录，返回 JWT）
- 登录响应新增 `mfaRequired/challengeToken/username`（仅 MFA 挑战阶段）
- `/api/me/mfa`：`GET` 状态、`POST setup|verify|disable`（租户账号本人，TOTP + 恢复码）
- 技能版本/调试：`GET /api/skills/{id}/versions`、`GET .../versions/{version}`、`POST .../debug`
- SCIM 2.0（v6B）：`/scim/v2/ServiceProviderConfig`、`/scim/v2/Users`、`/scim/v2/Groups`（Bearer `scim_` token，RFC7644 JSON）；SCIM Token 管理 `GET/POST /api/platform/scim-tokens`、`DELETE /api/platform/scim-tokens/{id}`
- 前端路由：`/sso-callback`（OIDC 回调）、`/security/mfa`、`/scim`

