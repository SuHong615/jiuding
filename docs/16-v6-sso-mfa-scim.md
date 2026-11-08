# 16 · v6：OIDC/SSO、MFA、SCIM（分阶段）

> 状态：**阶段 A + B 已完成并验收**（2026-11-08）｜Owner：Lead｜关联：docs/13、docs/14、docs/15
> 验收：gateway 59 测试、tenant-service 166 测试全绿；`tests/e2e-v6.ps1` **25/25**、`tests/e2e-v6b.ps1` **31/31**；`scripts/verify.ps1` **17/17 PASS**。

## 1. 范围（分两阶段交付）

### 阶段 A（本增量）
1. **OIDC / SSO 登录**（gateway）
2. **MFA（TOTP）**（tenant-service，租户账号 + 平台用户后续接入）

### 阶段 B（后续增量）
3. **SCIM 2.0**（tenant-service：Users / Groups 同步）
4. 登录页 SSO 按钮、MFA 绑定/验证 UI（frontend v6）

## 2. OIDC / SSO（gateway）

配置（env 可覆盖）：
```yaml
tenancy:
  auth:
    oidc:
      enabled: ${TENANCY_OIDC_ENABLED:false}
      provider-name: ${TENANCY_OIDC_PROVIDER:企业 SSO}
      issuer: ${TENANCY_OIDC_ISSUER:}
      client-id: ${TENANCY_OIDC_CLIENT_ID:}
      client-secret: ${TENANCY_OIDC_CLIENT_SECRET:}
      redirect-uri: ${TENANCY_OIDC_REDIRECT_URI:http://localhost:30080/api/auth/oidc/callback}
      scopes: ${TENANCY_OIDC_SCOPES:openid,profile,email}
      frontend-redirect: ${TENANCY_OIDC_FRONTEND_REDIRECT:http://localhost:30088/sso-callback}
      role-claim: ${TENANCY_OIDC_ROLE_CLAIM:roles}
      default-role: ${TENANCY_OIDC_DEFAULT_ROLE:AUDITOR}
```

端点：
```
GET /api/auth/oidc/authorize            → {enabled, providerName, authorizationUrl}（无状态 state=HMAC(nonce+ts)）
GET /api/auth/oidc/callback?code=&state= → 校验 state → 换 token → userinfo → 签发 JWT
     → 302 到 frontend-redirect#token=<jwt>&username=<name>（fail → #error=...）
GET /api/auth/oidc/status               → {enabled, providerName, loginUrl}
```
- 发现文档：`${issuer}/.well-known/openid-configuration`，缓存 5 分钟；
- 授权码流程 + PKCE（S256）；state HMAC 防 CSRF；无 refresh token 持久化；
- 角色映射：取 `role-claim`（默认 roles）中的已知角色，未知/缺省 → default-role；
- 平台用户 JWT 复用现有签发逻辑；OIDC 用户仅 SSO 登录（不写本地用户表）；
- 白名单 `/api/auth/oidc/**`；失败重定向只带 error code，不泄露细节。

## 3. MFA（tenant-service）

数据（tenant_db V6）：
```sql
CREATE TABLE account_mfa (
  account_id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  secret_base32 VARCHAR(64) NOT NULL,
  enabled BOOLEAN NOT NULL DEFAULT FALSE,
  recovery_codes_hash VARCHAR(1024),
  created_at TIMESTAMP NOT NULL,
  confirmed_at TIMESTAMP,
  last_used_at TIMESTAMP
);
CREATE TABLE mfa_challenges (
  id VARCHAR(36) PRIMARY KEY,
  account_id VARCHAR(36) NOT NULL,
  tenant_id VARCHAR(36) NOT NULL,
  code_hash VARCHAR(128) NOT NULL,   -- 6 位验证码 sha256
  expires_at TIMESTAMP NOT NULL,
  attempts INT NOT NULL DEFAULT 0,
  consumed_at TIMESTAMP
);
```

API（JWT，本人）：
```
GET  /api/me/mfa                     → {enabled, confirmedAt}
POST /api/me/mfa/setup               → {secret, otpauthUrl, recoveryCodes[]}
POST /api/me/mfa/verify {code}       → {enabled:true}
POST /api/me/mfa/disable {code}
```
登录改造：
- `/internal/auth/authenticate`：密码正确且 MFA enabled → 返回 `{mfaRequired:true, challengeToken}`（短时 JWT，5 分钟，claim `mfa=true`，不含完整角色）；
- gateway `/api/auth/login` 响应 `{mfaRequired:true, challengeToken, username}`；
- 新端点 `POST /api/auth/login/mfa {challengeToken, code}` → tenant-service `/internal/auth/authenticate-mfa` 校验 TOTP/recovery code → 返回 principal → gateway 签发 JWT；
- 失败：401 统一提示；challenge 5 次失败作废；recovery code 一次性。
- TOTP：RFC 6238，HMAC-SHA1，30s 步长，6 位，±1 窗口；base32 secret；otpauth URL。
- 审计：MFA_ENROLLED / MFA_VERIFIED / MFA_DISABLED / MFA_CHALLENGE_FAILED。

## 4. SCIM 2.0（阶段 B）

```
GET/POST      /scim/v2/Users
GET/PATCH/PUT/DELETE /scim/v2/Users/{id}
GET/POST      /scim/v2/Groups
GET/PATCH/DELETE /scim/v2/Groups/{id}
GET           /scim/v2/ServiceProviderConfig
```
- 认证：`Authorization: Bearer <SCIM_TOKEN>`（每租户配置，env/平台管理后台后续）；
- 字段映射：User→accounts（userName/displayName/emails/active/role→groups）、Group→org_units；
- 过滤：`filter=userName eq "x"`、`startIndex/count`；错误用 SCIM Error schema；
- 审计 SCIM_USER_CREATED / SCIM_GROUP_SYNCED。

## 5. 验收（阶段 A）

- OIDC：stub IdP（discovery/token/userinfo）跑通 authorize→callback→JWT→受保护 API；state 篡改拒绝；未启用时 status=false、callback 400。
- MFA：setup→verify→login 返回 mfaRequired→/login/mfa 成功；错误码失败；challenge 过期/次数限制；恢复码一次性；审计齐全。
- 前端 v6：SSO 登录按钮（enabled 时显示）、MFA 绑定/验证页、mfaRequired 流程。
- 测试通过并部署验证。

## 6. 阶段 A 验收结果（2026-11-08）

| 项 | 结果 |
|---|---|
| OIDC 后端 | gateway 49→**53 测试**全绿；stub IdP 覆盖 discovery/authorize/PKCE/state/token/userinfo/角色映射；未启用三端点行为正确 |
| OIDC 实测 | `status.enabled=false`；`authorize` 404/40400；`callback` 400/40001 |
| MFA 后端 | tenant-service 137→**151 测试**全绿；V6 迁移、TOTP、恢复码、challenge 一次性、审计 |
| MFA 实测 | 租户账号 setup→verify→登录 `mfaRequired`→`/api/auth/login/mfa` 换 JWT→错误码 401→恢复码一次性→禁用→免 MFA 登录 |
| 网关适配 | `LoginResponse.mfaRequired/challengeToken/username`；新增 `POST /api/auth/login/mfa` 白名单与限流 |
| E2E | `tests/e2e-v6.ps1` **25/25 PASS** |
| 前端 v6 UI | 待下一增量（SSO 按钮、MFA 绑定/验证页、mfaRequired 流程） |

## 7. 阶段 B（SCIM）已完成（2026-11-08）

| 项 | 结果 |
|---|---|
| 迁移 | tenant_db **V7**（`scim_tokens`） |
| Token API | `GET/POST /api/platform/scim-tokens`、`DELETE /{id}`；明文 `scim_` 仅一次、sha256、撤销、审计 |
| SCIM 端点 | `ServiceProviderConfig`；Users（filter userName/displayName/active、startIndex/count≤100、POST/GET/PUT/PATCH/DELETE）；Groups（filter displayName、CRUD、members 同步） |
| 映射 | User→accounts（userName/displayName/emails/phone/active/roles/orgUnitId 扩展）；Group→org_units（name/code/parentId/type/path/manager） |
| 错误 | RFC7644 Error schema（401/404/409/400） |
| 网关 | `/scim/**` 白名单透传（网关不校验 JWT，tenant-service 校验 SCIM token）；`/api/platform/scim-tokens/**` 代理 |
| 前端 | `/scim` 配置页（Token 列表/生成一次性明文/撤销、端点复制、映射说明） |
| 测试 | tenant 166 全绿（新增 5 类 15 例）；gateway 59 全绿；`tests/e2e-v6b.ps1` **31/31 PASS** |

### 7.0 待办（后续）

- SCIM 增量同步日志/失败重试可视化、SCIM Token 轮换提醒
- OIDC 多 IdP 并存、refresh token、MFA 平台用户接入
- 安全加固：SCIM token 绑定 IP/速率限制

### 7.1 SCIM 详细契约（实施基准）

数据（tenant_db V7）：
```sql
CREATE TABLE scim_tokens (
  id VARCHAR(36) PRIMARY KEY, tenant_id VARCHAR(36) NOT NULL, name VARCHAR(64),
  token_prefix VARCHAR(16) NOT NULL, token_hash VARCHAR(128) NOT NULL,
  status VARCHAR(16) NOT NULL,   -- ACTIVE|REVOKED
  created_by VARCHAR(64), created_at TIMESTAMP NOT NULL,
  last_used_at TIMESTAMP, revoked_at TIMESTAMP
);
```

管理 API（JWT，平台管理员）：
```
GET    /api/platform/scim-tokens?tenantId=
POST   /api/platform/scim-tokens {tenantId, name} → {id, token（仅本次明文，scim_ 前缀）, prefix, ...}
DELETE /api/platform/scim-tokens/{id}            → REVOKED
```

SCIM 端点（认证 `Authorization: Bearer scim_...`，绑定租户；白名单，不经 JWT）：
```
GET  /scim/v2/ServiceProviderConfig
GET  /scim/v2/Users?filter=userName eq "x"&startIndex=1&count=50
POST /scim/v2/Users
GET|PUT|PATCH|DELETE /scim/v2/Users/{id}
GET  /scim/v2/Groups?filter=displayName eq "x"&startIndex&count
POST /scim/v2/Groups
GET|PUT|PATCH|DELETE /scim/v2/Groups/{id}
```
- User 映射：userName→accounts.username、displayName→display_name、emails[0].value→email、phoneNumbers[0].value→phone、active→status(ACTIVE/DISABLED)、roles[0].value→account role、扩展 `urn:tenancy:params:2.0:User.orgUnitId`→org_unit；
- Group 映射：displayName→org_units.name、code 由 displayName 生成或 externalId；parentId 扩展；members 为 account id 列表（manager 用 managers 扩展）；
- 过滤：仅支持 `userName eq`、`displayName eq`、`active eq`；startIndex 1-based；count≤100；
- 错误：SCIM Error `{schemas:["urn:ietf:params:scim:api:messages:2.0:Error"], status, detail}`；
- 所有写操作审计 `SCIM_USER_*` / `SCIM_GROUP_*`。

### 7.2 前端 v6 UI（实施基准）

- 登录页：OIDC 启用时显示「企业 SSO 登录」→ `/api/auth/oidc/authorize?redirect=true`；新增 `/sso-callback` 页解析 `#token=`/`#error=` 并写入会话；
- 登录 MFA：登录响应 `mfaRequired` 时切换为验证码输入（含恢复码切换）→ `/api/auth/login/mfa`；
- 账号安全：个人菜单「两步验证」页：状态、绑定（QR/secret/otpauth）、8 个恢复码一次性展示、验证启用、禁用；
- SCIM 配置（平台管理员）：Token 列表/生成（明文仅一次）/撤销、端点 URL 复制、字段映射说明。
