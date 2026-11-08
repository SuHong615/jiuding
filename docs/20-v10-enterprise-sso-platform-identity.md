# 20 · v10：企业 SSO、平台账号、多租户身份、MCP 重构与工作流视图

> 状态：实施中（2026-11-08）｜Owner：Lead｜关联：docs/13/16/15

## 1. 需求与行业实践结论

| # | 需求 | 结论/方案 |
|---|---|---|
| 1 | 企业 SSO | OIDC 标准；平台管理员可配置/启停/测试 IdP（DB 持久化），网关动态读取；支持多 IdP，登录页按启用的 IdP 显示按钮 |
| 2 | 平台账号管理 | 新增 `platform_users` 表（PBKDF2），平台管理员 CRUD/启停/重置密码；登录走 DB；移除登录页预置账号展示 |
| 3 | 一人多租户 | **保留“租户内账号”作为成员关系**（`UNIQUE(tenant_id, username)` 已支持同名）；登录无 tenantCode 时按 username+密码匹配多个租户 → 返回租户选择；`/api/auth/tenants` 列出可切换租户；`/api/auth/switch-tenant` 用当前账号凭据（密码哈希匹配）换发目标租户 JWT。行业实践：**全局身份 + 租户成员关系**；本期以“同用户名+同密码”近似成员关系，后续演进为用户身份表 |
| 4 | MCP 控制台 | 导航置顶；外部 MCP 服务器改为右侧抽屉配置；API Key 与调用审计移到「平台」分组下 |
| 5 | 工作流界面 | 新增只读“租户生命周期工作流”页：阶段→活动→负责 Skill/Agent→产出；数据后端聚合（生命周期状态机 + Skill 活动） |
| 6 | 登录页 | 移除预置账号快捷登录；保留企业 SSO 按钮（按 IdP 配置显示）；账号密码 + 租户选择 |

## 2. 数据（tenant-service V9）

```sql
CREATE TABLE platform_users (
  id VARCHAR(36) PRIMARY KEY, username VARCHAR(64) NOT NULL UNIQUE,
  display_name VARCHAR(128), password_hash VARCHAR(256) NOT NULL,
  roles VARCHAR(256) NOT NULL, status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE',
  email VARCHAR(128), created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL, last_login_at TIMESTAMP
);
CREATE TABLE sso_providers (
  id VARCHAR(36) PRIMARY KEY, code VARCHAR(32) NOT NULL UNIQUE, name VARCHAR(64) NOT NULL,
  issuer VARCHAR(512), client_id VARCHAR(256), client_secret VARCHAR(512),
  scopes VARCHAR(256), redirect_uri VARCHAR(512), frontend_redirect VARCHAR(512),
  role_claim VARCHAR(64), default_role VARCHAR(32), enabled BOOLEAN NOT NULL DEFAULT FALSE,
  created_at TIMESTAMP NOT NULL, updated_at TIMESTAMP NOT NULL
);
```

## 3. API 增量

平台账号（JWT 平台管理员）：
```
GET/POST /api/platform/users
GET/PUT/DELETE /api/platform/users/{id}
POST /api/platform/users/{id}/reset-password
```

SSO 配置（平台管理员）：
```
GET/POST /api/platform/sso-providers
PUT/DELETE /api/platform/sso-providers/{id}
POST /api/platform/sso-providers/{id}/test   # 拉取 discovery 校验
```
公开：`GET /api/auth/oidc/providers` → [{code,name,loginUrl?}]（启用列表，不含 secret）

多租户登录：
```
POST /api/auth/login {username,password}   # 无 tenantCode
→ 200 token（唯一匹配） | 200 {tenantSelectionRequired:true, tenants:[{tenantId,tenantCode,name}]}
POST /api/auth/login {username,password,tenantCode}  # 显式选择
GET  /api/auth/tenants      # 当前登录用户可切换租户（username + password-hash 匹配）
POST /api/auth/switch-tenant {tenantCode}  # 校验同一账号凭据后换发 JWT
```
- 密码匹配：在候选租户账号中校验同一密码；多个匹配 → 返回选择列表；密码不同不列出；
- 切换：目标租户中存在同一 username 且 password_hash 相同的 ACTIVE 账号 → 签发目标租户 JWT；
- MFA：选择租户后仍走 challenge 流程。

## 4. 前端

1. 登录：无预置账号卡片；SSO 按钮按 `/api/auth/oidc/providers` 渲染；登录返回 `tenantSelectionRequired` 时展示租户选择。
2. 顶栏：租户切换器（`/api/auth/tenants` 多条时显示），切换后重载权限与页面。
3. 平台 → 平台用户管理页（CRUD/启停/重置）。
4. MCP：导航顺序提到最前（“能力”组首位）；外部 MCP 服务器改为右侧抽屉；API Key/审计移至“平台”组的 MCP 凭据/调用审计页面。
5. 新增“工作流”页：租户生命周期阶段卡片 + 每阶段活动、Skill、产出、状态。

## 5. 验收

- 后端测试：平台用户 CRUD/登录、多租户解析/切换、SSO provider CRUD/公开列表；
- E2E v10：平台用户创建→登录→租户多选/切换；SSO provider 配置；工作流 API；
- 前端构建 + 页面验证；`verify.ps1` 纳入 v10。
