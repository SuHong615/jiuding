# gateway（8080）

平台唯一对外入口：JWT 签发、反向代理（含 SSE 流式转发）、平台聚合。

## 启动

```powershell
$env:Path='D:\deepseekHarness\env\apache-maven-3.9.9\bin;'+$env:Path
mvn -B -f gateway\pom.xml test          # 单测
mvn -B -f gateway\pom.xml spring-boot:run
# 或
java -jar gateway\target\gateway-0.9.0-SNAPSHOT.jar
```

## 环境变量

| 变量 | 默认 | 说明 |
|---|---|---|
| `SERVER_PORT` | `8080` | 监听端口 |
| `TENANCY_SECURITY_JWT_SECRET` | `tenancy-center-dev-secret-change-me-please` | 必须与所有服务一致（跨服务验签） |
| `TENANCY_SECURITY_API_KEYS` | `dev-mcp-key` | `/mcp/**` 的 `X-API-Key` 白名单（逗号分隔） |
| `TENANCY_INTERNAL_TOKEN` | `dev-internal-token` | 内部调用 token（网关不暴露 `/internal/**`） |
| `TENANCY_USERS` | 空（用 4 个预置用户） | JSON 数组覆盖用户；支持 `password` 或 PBKDF2 `passwordHash` |
| `TENANCY_LOGIN_RATE_LIMIT_ENABLED` | `true` | 登录限流开关（仅 `POST /api/auth/login`） |
| `TENANCY_LOGIN_RATE_LIMIT` | `10` | 滑动窗口内允许的登录尝试次数（key=`clientIp:username`） |
| `TENANCY_LOGIN_RATE_WINDOW_SECONDS` | `60` | 限流窗口秒数 |
| `TENANCY_OIDC_ENABLED` | `false` | v6 OIDC/SSO 开关；需同时配置 issuer/client-id/client-secret |
| `TENANCY_OIDC_PROVIDER` / `TENANCY_OIDC_ISSUER` | `企业 SSO` / 空 | 登录按钮文案 / 发现文档基地址（`${issuer}/.well-known/openid-configuration`） |
| `TENANCY_OIDC_CLIENT_ID` / `TENANCY_OIDC_CLIENT_SECRET` | 空 | OIDC 客户端凭据 |
| `TENANCY_OIDC_REDIRECT_URI` | `http://localhost:30080/api/auth/oidc/callback` | IdP 回调地址 |
| `TENANCY_OIDC_SCOPES` / `TENANCY_OIDC_ROLE_CLAIM` / `TENANCY_OIDC_DEFAULT_ROLE` | `openid,profile,email` / `roles` / `AUDITOR` | 作用域 / 角色 claim / 未知角色兜底 |
| `TENANCY_OIDC_FRONTEND_REDIRECT` | `http://localhost:30088/sso-callback` | 登录完成后前端重定向（token 放 fragment） |
| `TENANCY_OIDC_STATE_SECRET` / `TENANCY_OIDC_STATE_TTL_SECONDS` | 共享 JWT secret / `600` | state HMAC 密钥与有效期 |
| `TENANCY_OIDC_DISCOVERY_CACHE_SECONDS` / `TENANCY_OIDC_REQUEST_TIMEOUT_MS` | `300` / `5000` | 发现文档缓存 / IdP 请求超时 |
| `TENANT_SERVICE_URL` | `http://localhost:8081` | 租户服务 |
| `APPROVAL_SERVICE_URL` | `http://localhost:8082` | 审批服务 |
| `AGENT_SERVICE_URL` | `http://localhost:8083` | Agent/Skill 服务 |
| `MCP_SERVICE_URL` | `http://localhost:8084` | MCP 服务 |
| `TENANCY_GATEWAY_REQUEST_TIMEOUT_MS` | `10000` | 普通代理超时 |
| `TENANCY_GATEWAY_MCP_TIMEOUT_MS` | `30000` | `/mcp/**` 代理超时 |
| `TENANCY_GATEWAY_HEALTH_TIMEOUT_MS` | `2000` | 健康/聚合探测超时 |
| `TENANCY_TENANT_QUOTA_ENABLED` | `true` | v7 租户 API RPM 配额开关（仅 `/api/**` 代理请求、仅带 `tid` 的 JWT） |
| `TENANCY_TENANT_QUOTA_CACHE_SECONDS` | `60` | `GET /internal/tenants/{id}/quota` 结果缓存秒数 |
| `TENANCY_TENANT_QUOTA_WINDOW_SECONDS` | `60` | 租户 RPM 滑动窗口秒数 |
| `TENANCY_TENANT_QUOTA_TIMEOUT_MS` | `2000` | 配额查询超时（失败降级为不限） |

## 接口

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/login` | 双通道登录（docs/13 §2）：`{username,password,tenantCode?}`；`tenantCode` 空 → 平台预置用户优先，未命中则走租户账号认证；非空 → 直接租户账号认证（调 tenant-service `POST /internal/auth/authenticate`，带 `X-Internal-Token`）。成功返回 JWT（`tid` + `oid` claim）与 principal（含 `orgUnitId/mustChangePassword/authType=PLATFORM|TENANT`） |
| GET | `/api/auth/me` | 当前 principal（含 `orgUnitId`、`authType`；`mustChangePassword` 仅登录响应返回，JWT 无此 claim） |
| GET | `/api/platform/overview` | 租户/审批/技能/Agent 聚合（单服务失败降级） |
| GET | `/api/platform/health` | 4 个下游 `/actuator/health` 探测 |
| * | `/api/tenants/**` | → tenant-service |
| * | `/api/approvals/**` | → approval-service |
| * | `/api/skills/**`、`/api/agents/**` | → agent-service |
| * | `/mcp`、`/mcp/**` | → mcp-service（SSE 逐块转发，不缓冲） |
| * | `/api/mcp`、`/api/mcp/**` | → mcp-service（v5 MCP 控制台：config/keys/audits/external-servers） |
| * | `/api/me/**`、`/api/platform/roles/**`、`/api/platform/permissions` | → tenant-service（v5 RBAC/权限目录；不覆盖本地 overview/health） |
| * | `/api/public/**` | → tenant-service（v5 公开自助入驻，白名单免认证） |
| * | `/scim/v2/**` | → tenant-service（v6 SCIM 2.0；`Authorization: Bearer scim_...` 由下游校验，网关白名单透传） |
| * | `/api/platform/scim-tokens/**` | → tenant-service（v6 平台 SCIM Token 管理，需平台管理员 JWT） |
| GET | `/api/auth/oidc/status` | v6 OIDC：`{enabled, providerName, loginUrl}`（白名单） |
| GET | `/api/auth/oidc/authorize` | v6 OIDC：返回 `{authorizationUrl}`（`?redirect=true` 时 302 到 IdP），含签名 state + PKCE S256 |
| GET | `/api/auth/oidc/callback` | v6 OIDC：校验 state → 换 token → userinfo → 签发 JWT → 302 到前端 `#token=...&username=...`；失败 `#error=...` |

认证由 `tenancy-common` 的 `AuthFilter` 统一处理；`/api/auth/login`、`/actuator/health`、`/api/public/**`、`/scim/**` 在白名单。
代理透传 `Authorization`、`X-Tenant-Id`、`X-Request-Id`、`X-API-Key` 及 `Content-Type`；
下游不可达返回 `502` + `ApiResponse{code:50200}`，不抛 500。
登录错误统一为 `401 {code:40100,message:"invalid credentials"}`（不区分用户名/密码/租户）；
租户认证时 tenant-service 不可达或返回 5xx → `503 {code:50200}`（`GatewayExceptionHandler`）。

## OIDC / SSO（v6，docs/16 §2）

- 开关：`tenancy.auth.oidc.enabled`（env `TENANCY_OIDC_ENABLED`）；未启用时 `/status` 返回 `enabled=false`、
  `/authorize` 404、`/callback` 400（不泄露配置细节）。
- 授权码 + PKCE：`/authorize` 从发现文档（`${issuer}/.well-known/openid-configuration`，缓存 5 分钟）取端点，
  生成 `state = nonce.issuedAt.HMAC-SHA256(secret, nonce.issuedAt)`（有效期 `state-ttl-seconds`，默认 10min）
  与 S256 `code_challenge`；PKCE verifier 仅存内存、回调一次性消费。
- 回调：校验 state（防篡改/过期）→ `grant_type=authorization_code` + code_verifier 换 token →
  userinfo 取 `role-claim`（已知角色映射；未知/缺省 → `default-role`）→ 复用现有 JWT 签发（平台用户 JWT）→
  302 到 `frontend-redirect#token=<jwt>&username=<name>`；失败只回 `#error=<code>`。
- 安全：OIDC 用户不写本地用户表；失败重定向不含内部细节；`/api/auth/oidc/**` 在鉴权白名单。
- 限制：无 refresh token 持久化；PKCE verifier 为进程内存储（多副本需共享存储或改无状态加密 state）。

## 密码策略

预置密码仅存在于 `PresetUsers`（文档要求可读），启动时用 **PBKDF2-HMAC-SHA256（120000 次迭代、16 字节随机盐）**
生成内存哈希；登录比较使用常量时间 `MessageDigest.isEqual`，未知用户名也执行一次哈希以免时序枚举。
密码不落盘、不打日志。

## 设计要点

- servlet MVC + `java.net.http.HttpClient`，`BodyHandlers.ofInputStream()` 逐块 `flush()` 转发，满足 MCP SSE 要求。
- 响应头逐项复制（剔除 hop-by-hop 与 `Content-Length`），`Mcp-Session-Id`、`X-Accel-Buffering` 原样透传。
- 聚合接口对每个下游独立 try/catch，全部失败也返回 200（字段置 0 / 服务置 DOWN）。
- `/internal/**` 由 `InternalPathController` 统一返回 **404**（不代理、不泄露内部接口存在性）：
  该路径同时加入 `tenancy.security.whitelist`，避免共享 `AuthFilter` 先返回 401/500 而暴露路径语义。
- 登录限流（docs/14 §4）：仅 `POST /api/auth/login`；内存滑动窗口 key=`clientIp:username`，默认 10 次/60s
  （env `TENANCY_LOGIN_RATE_LIMIT` / `TENANCY_LOGIN_RATE_WINDOW_SECONDS`，可 `TENANCY_LOGIN_RATE_LIMIT_ENABLED=false` 关闭），
  超限返回 `429 {code:42900,message:"too many login attempts, retry later"}`；成功/失败请求都计数，窗口滑出后自动恢复；
  `ConcurrentHashMap` + 每窗口时间窗清理（每 1024 次调用清理过期 key），线程安全。
- 客户端 IP：优先 `X-Forwarded-For` 首段，否则 `remoteAddr`；`User-Agent` 截断 256。
  调 tenant-service `/internal/auth/authenticate` 时携带 `X-Forwarded-For` 与 `User-Agent`（供 `account_login_events` 审计）；平台登录不调用下游。
- 租户 API RPM 配额（docs/17 §5）：仅统计携带 `tid` 的 JWT 的 `/api/**` 代理请求（平台管理员/无 tid 不计数，`/api/auth/**` 与本地
  `/api/platform/overview|health` 排除）；limit 来自 `GET /internal/tenants/{id}/quota` 的 `apiRpm.limit`（`X-Internal-Token`，缓存 60s，
  查询失败/401 降级为不限并 WARN）；`key=tenantId` 60s 滑动窗口，超限 `429 {code:42901,message:"tenant api quota exceeded"}`。
