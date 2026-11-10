# 23 · v12：生产化四件套（RLS、工作流编排、IdP、平台账号/Redis）

> 状态：**已验收**（2026-11-10）：RLS、bootstrap、工作流定义/编排 UI、Mock OIDC 端到端、E2E v12 33/33；Redis 共享缓存/限流仍在后续｜Owner：Lead

## 1. 范围

| # | 目标 | 方案 |
|---|---|---|
| G1 | **数据面隔离** | PostgreSQL RLS：tenant_db 核心表启用 RLS，应用通过连接级 GUC `app.tenant_id` 注入；平台管理员 `app.platform=true` 绕过；H2 测试降级为应用层断言；提供迁移与验证用例 |
| G2 | **工作流编排** | approval_db 新增 `workflow_definitions`（JSON DAG：阶段/节点/parallel/compensation/SLA），平台管理员 CRUD/发布；审批引擎按定义执行（保留默认内置流程兜底）；提供校验（环检测/必填） |
| G3 | **企业 IdP 端到端** | 网关内置 **dev Mock OIDC Provider**（discovery/authorize/token/userinfo，仅 `mock-enabled=true` 时开放）；用它对走完整 SSO 登录链路（authorize→callback→JWT），并保留真实 IdP 配置能力 |
| G4 | **平台账号初始化 + 分布式限流** | 启动时按 env 初始化平台管理员（无硬编码）；移除/关闭预置回退；Redis 作为权限缓存/登录与租户 RPM 的共享存储（无 Redis 时降级内存） |
| G5 | 验证 | 后端测试、E2E v12、verify 门控、部署 |

## 2. RLS 设计（tenant-service）

迁移 `V11__tenant_rls.sql`：
```sql
ALTER TABLE org_units ENABLE ROW LEVEL SECURITY;
ALTER TABLE accounts ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_ratings ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_audits ENABLE ROW LEVEL SECURITY;
CREATE POLICY org_units_tenant ON org_units USING (
  current_setting('app.platform', true) = 'true'
  OR tenant_id = current_setting('app.tenant_id', true)
) WITH CHECK (...same...);
-- 其余表同理
```
应用：
- `TenantRlsDataSource` 包装 Hikari DataSource：`getConnection()` 后执行 `SET app.tenant_id = ?`、`SET app.platform = ?`（来自 TenantContext/AuthContext），close 前 reset；无租户上下文（内部/调度）设置 platform=true。
- 启用开关 `tenancy.rls.enabled`（docker/k8s=true，H2/test=false）。
- 测试：Postgres 集成（k3s 上 E2E 验证跨租户 SQL 不可见；H2 跳过）。
- 注意：Flyway 迁移本身使用超级用户连接，RLS 不启用对 owner 默认；应用账号为表 owner 时 RLS 默认不生效 → 迁移需 `FORCE ROW LEVEL SECURITY` 并确保应用用户非超级用户（当前 tenancy 用户是 owner，需要 `ALTER TABLE ... FORCE ROW LEVEL SECURITY`）。

## 3. 工作流编排（approval-service）

数据（approval_db V2）：
```sql
CREATE TABLE workflow_definitions (
  id VARCHAR(36) PRIMARY KEY, code VARCHAR(64) UNIQUE, name VARCHAR(128),
  version INT NOT NULL, status VARCHAR(16), definition_json TEXT,
  created_by VARCHAR(64), created_at TIMESTAMP, updated_at TIMESTAMP, published_at TIMESTAMP
);
```
definition JSON：
```json
{"nodes":[{"id":"risk","type":"AUTO_CHECK","skill":"risk-review","next":"approval"},
          {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24,"next":null}],
 "transitions":[...],"compensations":[{"onReject":"close"}]}
```
API（平台管理员）：`GET/POST /api/workflows`、`GET/PUT/DELETE /api/workflows/{id}`、`POST /{id}/publish`、`POST /{id}/validate`。
引擎：创建审批时按已发布定义生成节点（当前默认流程作为 fallback）；节点类型 AUTO_CHECK/HUMAN/PARALLEL(阶段占位)/SLA。
前端：工作流页增加「流程定义」编辑（节点增删/连线/SLA）与发布。

## 4. Mock OIDC（gateway，dev-only）

- 配置 `tenancy.auth.mock-oidc.enabled`（默认 false；本地/测试 true）。
- 端点 `/mock-oidc/.well-known/openid-configuration`、`/authorize`（自动签发 code）、`/token`（code→access_token）、`/userinfo`。
- 仅当启用时暴露；生产默认关闭。
- E2E：配置 SSO Provider issuer=`http://gateway:8080/mock-oidc` → authorize→callback→JWT→受保护 API。

## 5. 平台账号与 Redis

- 启动 Seeder：env `TENANCY_PLATFORM_BOOTSTRAP_USERNAME/PASSWORD/ROLES`（密码必填才创建；不硬编码），创建后记录审计。
- 网关：`tenancy.auth.preset-fallback-enabled` 默认 false（生产），本地测试可 true；登录只走 DB + 租户账号。
- Redis（可选）：`REDIS_URL` 存在时，权限缓存、登录/租户 RPM 使用 Redis；否则内存降级。compose/k8s 增加 redis 服务与 env。

## 6. 验收

- 后端全量测试全绿；RLS 在 k3s Postgres 实测跨租户不可见；工作流定义 CRUD/发布/校验；Mock OIDC 端到端 SSO；平台账号 bootstrap；Redis 降级/启用。
- E2E `tests/e2e-v12.ps1`；`verify.ps1` 纳入 v12。
