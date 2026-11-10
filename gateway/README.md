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
| `TENANCY_OIDC_PROVIDERS_CACHE_SECONDS` | `60` | 动态 SSO Provider 列表（tenant-service `/internal/sso-providers/enabled`）缓存秒数；v10 起优先于环境变量静态配置 |
| `TENANCY_PRESET_FALLBACK_ENABLED` | `false` | v12 平台登录本地预置用户回退开关（生产必须 false；本地/测试可 true） |
| `TENANCY_MOCK_OIDC_ENABLED` | `false` | v12 dev-only 内置 Mock OIDC IdP 开关（生产必须 false） |
| `TENANCY_MOCK_OIDC_ISSUER` | 空 | Mock OIDC issuer 覆盖（空则按请求 Host 自动推导 `/mock-oidc`） |
| `TENANCY_MOCK_OIDC_USERNAME` / `TENANCY_MOCK_OIDC_DISPLAY_NAME` / `TENANCY_MOCK_OIDC_ROLES` | `mock-user` / `Mock User` / `TENANT_ADMIN` | Mock userinfo 返回的 sub/name/roles |
| `TENANCY_MOCK_OIDC_CODE_TTL_SECONDS` / `TENANCY_MOCK_OIDC_TOKEN_TTL_SECONDS` | `120` / `300` | Mock authorization code / access token TTL |
| `TENANCY_REDIS_ENABLED` / `REDIS_URL` | `false` / 空 | v12 分布式限流 Redis 开关与连接串（`redis://[user:password@]host:port/db`；设置 REDIS_URL 即视为启用） |
| `TENANCY_REDIS_KEY_PREFIX` | `tenancy:rl:` | Redis 限流 key 前缀 |
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
| GET | `/api/auth/tenants` | v10 当前身份可切换租户（JWT；`username`+密码哈希匹配的 ACTIVE 租户成员关系） |
| POST | `/api/auth/switch-tenant` | v10 `{tenantCode}` → 内部校验后签发目标租户 JWT（保留 roles/orgUnitId） |
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
| * | `/api/platform/users`、`/api/platform/users/**` | → tenant-service（v10 平台账号管理，需平台管理员 JWT） |
| * | `/api/platform/sso-providers`、`/api/platform/sso-providers/**` | → tenant-service（v10 SSO Provider 配置管理） |
| * | `/api/workflows`、`/api/workflows/**` | → tenant-service（v10 租户生命周期工作流只读视图） |
| * | `/api/platform/audits`、`/api/platform/audits/**` | → tenant-service（v11 平台审计日志/导出，需平台管理员 JWT） |
| * | `/api/approval-workflows`、`/api/approval-workflows/**` | → approval-service（v12 审批工作流定义/运行；`/api/workflows/**` 仍为 tenant-service 生命周期视图） |
| GET | `/api/auth/oidc/status` | v6 OIDC：`{enabled, providerName, loginUrl}`（白名单） |
| GET | `/api/auth/oidc/authorize` | v6 OIDC：返回 `{authorizationUrl}`（`?redirect=true` 时 302 到 IdP），含签名 state + PKCE S256 |
| GET | `/api/auth/oidc/callback` | v6 OIDC：校验 state → 换 token → userinfo → 签发 JWT → 302 到前端 `#token=...&username=...`；失败 `#error=...` |
| GET | `/api/auth/oidc/providers` | v10 公开的启用 SSO Provider 列表（`[{code,name,loginUrl}]`，`authorize?provider=<code>` 选择） |

认证由 `tenancy-common` 的 `AuthFilter` 统一处理；`/api/auth/login`、`/actuator/health`、`/api/public/**`、`/scim/**`、`/mock-oidc/**`（仅 dev Mock OIDC 启用时有效）在白名单。
代理透传 `Authorization`、`X-Tenant-Id`、`X-Request-Id`、`X-API-Key` 及 `Content-Type`；
下游不可达返回 `502` + `ApiResponse{code:50200}`，不抛 500。
登录错误统一为 `401 {code:40100,message:"invalid credentials"}`（不区分用户名/密码/租户）；
租户认证时 tenant-service 不可达或返回 5xx → `503 {code:50200}`（`GatewayExceptionHandler`）。

## 平台账号与多租户（v10，docs/20 §3）

- 平台账号登录优先调用 tenant-service `POST /internal/platform-users/authenticate`（X-Internal-Token，PBKDF2 在租户侧校验）；
  v12 起仅当 `tenancy.auth.preset-fallback-enabled=true`（`TENANCY_PRESET_FALLBACK_ENABLED`，生产默认 false）才回退本地预置用户目录
  （兼容/本地测试用，任何 API 都不暴露预置列表）；关闭时只走 DB + 租户账号。
- `tenantCode` 为空：平台账号 → 本地预置 → 租户成员解析；同一 username+密码命中多个租户时返回
  `200 {mfaRequired:false, tenantSelectionRequired:true, tenants:[{tenantId,tenantCode,name}]}`；显式 `tenantCode` 直接走租户认证。
- `/api/auth/tenants`（v10.1）使用当前 JWT 的 `accountId/username/currentTenantId` 调 tenant-service
  `GET /internal/auth/tenants?accountId=&username=&currentTenantId=`；平台账号/无租户身份、或下游失败均返回 `200 []`（不返回 502）。
- `/api/auth/switch-tenant {tenantCode, password?}`（v10.1）：未提供 password 时走免密新契约
  `POST /internal/auth/switch-tenant {currentAccountId, tenantCode}`（后端校验目标租户同名且 password_hash 相同）；提供 password 时回退旧契约
  `{username, tenantCode, password}`；成功签发目标租户 JWT（保留 roles/orgUnitId），失败 401。账号 username 由登录时的
  [TenantIdentityCache] 内存映射还原（JWT 只有 accountId+displayName），旧契约回退时使用。

## 动态 SSO Provider（v10，docs/20 §3）

- 启动/首次请求从 tenant-service `GET /internal/sso-providers/enabled`（X-Internal-Token）读取启用 IdP，缓存 60s
  （`TENANCY_OIDC_PROVIDERS_CACHE_SECONDS`）；失败降级为无动态 Provider（不拖垮静态 `tenancy.auth.oidc.*` 配置）。
- `GET /api/auth/oidc/providers` 返回 `[{code,name,loginUrl}]`（不含 secret）；`authorize?provider=<code>` 选择 Provider，
  provider code 绑定进签名 state，callback 使用对应 issuer/client/secret/frontendRedirect；未知 provider → 404；多个 Provider
  未指定 code → 400/404 明确提示。未启用任何 Provider 时保持 v6 disabled 行为。

## Redis 分布式限流（v12，docs/23 §5）

- 新增 `spring-boot-starter-data-redis`；`tenancy.redis.enabled=true` 或设置 `REDIS_URL` 时，登录限流与租户 API RPM 使用 Redis，
  否则沿用内存实现；Redis 连接失败/命令异常时按请求自动降级为内存计数（WARN 日志），不返回 5xx。
- 登录限流：ZSET 滑动窗口，key `tenancy:rl:login:<clientIp>:<username>`，窗口/阈值同 `tenancy.auth.login-rate-limit.*`（默认 60s/10 次）。
- 租户 RPM：key `tenancy:rl:quota:<tenantId>`，窗口同 `tenancy.gateway.tenant-quota.window-seconds`（默认 60s）。
- 每次请求清理窗口外成员、`ZCARD` 判阈值、通过后 `ZADD` + `PEXPIRE`（窗口 TTL）；多副本共享计数。
- `GatewayApplication` 已排除 Spring Boot 默认 Redis auto-configuration，Redis 仅按需创建，不影响未启用时的启动/健康检查。

## Mock OIDC（v12，dev-only，docs/23 §4）

- 仅 `tenancy.auth.mock-oidc.enabled=true`（`TENANCY_MOCK_OIDC_ENABLED`）时注册；生产保持关闭。
- 端点：`GET /mock-oidc/.well-known/openid-configuration`、`GET /mock-oidc/authorize`（直接 302 回 `redirect_uri?code=...&state=...`）、
  `POST /mock-oidc/token`（authorization_code + PKCE S256 校验）、`GET /mock-oidc/userinfo`（`sub/name/roles`）。
- 用途：把 SSO Provider issuer 指向 `http://<gateway>/mock-oidc`，即可在无外部 IdP 环境跑通 authorize→callback→JWT 全链路；
  关闭时 `/mock-oidc/**` 固定 404。

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
