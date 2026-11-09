# tests/ · 端到端与 MCP 协议测试

本目录提供**不依赖真实网络、可在 Windows PowerShell 5.1 / PowerShell 7 运行**的黑盒测试脚本。
默认目标地址为本地进程模式的网关 `http://localhost:18080`（本机 8080 被其它应用占用；
Docker 模式传 `-BaseUrl http://localhost:8080`，k8s NodePort/Port-Forward 传 `-BaseUrl http://localhost:30080`）。
所有脚本均为 **UTF-8 无 BOM、纯 ASCII 文本**，避免 PowerShell 5.1 把无 BOM 的 UTF-8 中文误判为 ANSI。

## 文件

| 文件 | 作用 |
|---|---|
| `e2e.ps1` | v1 主链路 E2E：登录 → 建租户 → 提交 → 审批 → 租户 ACTIVE → MCP `tools/call` → CLI 查询 |
| `e2e-v2.ps1` | **v2 身份域 E2E**：登录 → 建租户 → 组织树（公司/部门/子部门）→ 建账号（一次性临时密码、无 hash）→ 评级评估（等级/五维/历史）→ 组织移动/删除校验（409）→ 账号禁用/启用/重置密码 → 租户删除（ACTIVE 409 / 非 ACTIVE 成功且默认不可见） |
| `e2e-v3.ps1` | **v3 登录与 RBAC E2E**：admin 建租户/组织树/账号 → 租户账号登录（`tenantCode+username+password`，JWT 含 tid/oid）→ ORG_ADMIN 子树内可写、子树外/跨租户 403、提权授予被拒 → MEMBER 仅见本人账号、只读、所有写 403 → 平台 auditor 跨租户只读、写/删除 403 → 错误密码统一 401（5 次锁定为可选断言） |
| `e2e-v4.ps1` | **v4 账号安全与合规 E2E**：锁定/解锁、修改密码、登录事件、审计 CSV 导出、PII 脱敏、评级 delta、登录限流 429 |
| `e2e-v5.ps1` | **v5 技能中心/入驻/导入/MCP/权限 E2E**（docs/15）：CSV 模板下载 + 组织/账号导入（重复跳过、临时密码登录）→ 自助入驻（合规自动激活 / 不合规人工分支 + 状态查询）→ 技能包 zip 上传/送审/审核/启用/重复 409/驳回 → Agent catalog 与可读运行摘要 → MCP config/按人 key/tools 调用/审计/撤销 401/外部服务器 CRUD → RBAC 权限（`/api/me/permissions`、5 内置角色、自定义角色+权限矩阵、越权 403）→ 内部 purge |
| `e2e-skill.ps1` | **v5.1 技能版本/调试 E2E**（docs/15 §12）：上传 zip（1.0.0）→ 版本列表 → `PUT` 另存新版本（1.1.0）→ 多版本列表 → 旧版本详情内容正确 → debug 命中/未命中（decision/actions/matchedRules/durationMs）→ 不存在版本 404 → 清理 |
| `e2e-v6.ps1` | **v6 阶段 A E2E**（docs/16 §2/§3）：OIDC `status`（未配置 enabled=false）/`authorize` 404/`callback` 400；MFA 租户账号 setup→verify（TOTP）→登录返回 `mfaRequired`→`/api/auth/login/mfa` 成功→错误码 401→恢复码一次性→禁用→审计（MFA_ENROLLED/VERIFIED/DISABLED） |
| `e2e-v6b.ps1` | **v6 阶段 B SCIM E2E**（docs/16 §7.1）：SCIM token 创建（明文一次）/撤销、无 token 与坏 token 401、ServiceProviderConfig 200；Users POST/GET/filter/GET id/PATCH active=false/PUT displayName/分页/DELETE；Groups POST/GET/filter/PATCH 改名+成员/DELETE（204 或 409 均接受）；SCIM Error schema（401/404/409）；清理撤销 token + 软删租户 |
| `e2e-v7.ps1` | **v7 配额/用量/RPM E2E**（docs/17）：建租户（quotaSeats=2/quotaOrgUnits=2/quotaApiRpm=3）→ `GET /api/tenants/{id}/quota` 字段/状态 → 组织与账号按上限创建、超限 409 → CSV 导入（重复行 skipped + 超限行 failed，不中断）→ PUT 提升配额并校验 → 租户 JWT 网关 RPM 第 4 次 429/42901（未实现 SKIP）→ 无配额租户不受限 → 软删租户 |
| `e2e-v8.ps1` | **v8 导出/离户 E2E**（docs/18）：数据准备（组织/账号/评级/审计）→ `export?mask=true` 结构与 PII 掩码 + `Content-Disposition` → 默认 mask、TENANT_ADMIN 只读掩码、admin `mask=false` 明文、非平台 403 → MEMBER/ORG_ADMIN 403 → `TENANT_EXPORTED` 审计 → 离户（reason 必填、仅平台管理员、ACTIVE/SUSPENDED、TERMINATED+deletedAt）→ 离户后 detail/export 404、重复离户 404/409、`TENANT_OFFBOARDED` 审计探测 → 清理 |
| `e2e-v10.ps1` | **v10 平台身份/SSO/多租户/工作流 E2E**（docs/20）：平台用户 CRUD/登录/禁用/重置/删除；SSO Provider 创建/列表不泄露 secret/启停/不可达 issuer test 失败/公开列表只含 enabled/删除；双租户同用户名+密码账号 → 无 tenantCode 登录返回 `tenantSelectionRequired`（2 项）→ 带 tenantCode 登录 → `/api/auth/tenants` 可切换列表 → `/api/auth/switch-tenant` 换发 JWT；`/api/workflows/tenant-lifecycle` 状态/活动/Skill 映射/迁移；清理 |
| `e2e-v11.ps1` | **v11 平台审计中心 E2E**（docs/21）：admin 建两租户并触发审计动作 → `GET /api/platform/audits` 跨租户记录（含 tenantCode/tenantName）→ tenantId/action/keyword 过滤 → 分页 total 与 at DESC 排序 → 租户管理员/AUDITOR 403、未认证 401 → `GET /api/platform/audits/export?format=csv` text/csv attachment + 内容含租户 code/动作 + tenantId 过滤 → `PLATFORM_AUDIT_EXPORTED` 审计 → 清理 |
| `mcp-smoke.ps1` | v1 MCP 协议冒烟：`initialize` / `notifications/initialized` / `ping` / `tools/list`（14 工具）/ `tools/call` / 未知方法 `-32601` / `GET /mcp/info` |
| `mcp-smoke-v2.ps1` | **v2 MCP 冒烟**：`tools/list` 数量（14 基础工具 + 8 个 v2 工具 = 22）+ `org.tree` / `account.list` / `rating.list` 调用断言 |
| `README.md` | 本文件 |

## 前置条件

1. 服务已启动（三选一）：
   - 本地进程：`pwsh ./scripts/run-local.ps1`（gateway 18080，服务 8081-8084，前端 15173）
   - k8s / Port-Forward：`http://localhost:30080`（本机当前部署形态）
   - Docker：`cd deploy && docker compose up -d --build`（gateway 8080，前端 8088）
2. CLI fat jar 已构建（仅 `e2e.ps1` 的 CLI 断言需要）：`pwsh ./scripts/build.ps1 -Module tenancy-cli`
3. 默认账号（docs/02-api-contracts.md）：`admin/Admin@123`、`approver/Approver@123`；MCP Key：`dev-mcp-key`
4. v2 脚本依赖 `docs/11-tenant-identity-org-rating.md` §6 的 tenant-service v2 接口与 task-10 的 MCP 工具；
   **部署未包含 v2 时脚本输出 SKIP（不 FAIL）**，见下节。
5. v3 脚本依赖 `docs/13-v3-account-login-rbac.md` §2/§3 的租户账号登录与 RBAC；前提是 v2 已就绪，
   **gateway v3（登录 principal 含 `authType`）或 tenant-service `/internal/auth/authenticate` 未部署时整体 SKIP**。
6. v4/v5 脚本分别依赖 `docs/14-v4-security-compliance-production.md` 与
   `docs/15-v5-skill-mcp-onboarding-rbac.md`；**v5 未部署时 `e2e-v5.ps1` 输出 SKIP（退出码 0）**，见下节能力门。
7. `e2e-v5.ps1` 的 purge 断言需要直连 tenant-service（网关对 `/internal/**` 恒返回 404，这是设计行为）：
   传 `-TenantServiceUrl http://localhost:8081`；不传时该两条断言为 SKIP。
8. `e2e-v5.ps1` 的入驻请求会带随机 `X-Forwarded-For` 以隔离 IP 限流；若仍命中 429（5 次/分钟），脚本等待 60s 自动重试一次。
9. `e2e-skill.ps1` 依赖 `docs/15 §12` 的技能版本/调试接口（`GET /api/skills/{id}/versions`、
   `GET /api/skills/{id}/versions/{version}`、`POST /api/skills/{id}/debug`）；**接口缺失时整体 SKIP**。
10. `e2e-v6.ps1` 依赖 `docs/16 §2/§3` 的 OIDC 与 MFA 接口；默认环境 OIDC 未配置
    （`TENANCY_OIDC_ENABLED=false`），脚本断言 `enabled=false`。MFA 用租户账号 + 计算出的 TOTP 验证，
    需要本机与后端时钟一致（UTC，30s 步长，±1 窗口）。
11. `e2e-v6b.ps1` 依赖 `docs/16 §7.1` 的 SCIM 2.0 接口：管理 API（`/api/platform/scim-tokens`，平台管理员 JWT，
    token 明文仅一次）与 SCIM 端点（`/scim/v2/**`，`Authorization: Bearer scim_...`，白名单不经 JWT）。
    **后端未部署 SCIM 时整体 SKIP**；SCIM 响应是 RFC 7644 原生文档（非 `ApiResponse` 包），脚本按原生 JSON 断言。
12. `e2e-v7.ps1` 依赖 `docs/17` 的配额/用量 API（`GET /api/tenants/{id}/quota`）与网关 RPM 限流：
    建租户时传 `quotaOrgUnits`（迁移 V8 新字段）；**quota 路由缺失时整体 SKIP**；
    网关 RPM 默认不启用，6 次租户 JWT 调用内没有 429 时该断言为 SKIP（不算失败）。
13. `e2e-v8.ps1` 依赖 `docs/18` 的导出（`GET /api/tenants/{id}/export?mask=`）与离户（`POST .../offboard`）：
    **export 路由缺失时整体 SKIP**；离户后数据会被软删，`TENANT_OFFBOARDED` 审计若没有可读的
    审计 API（租户审计/平台审计）则该条断言为 SKIP（不算失败）。
14. `e2e-v10.ps1` 依赖 `docs/20` 的平台用户、SSO Provider、多租户登录/切换与工作流视图：
    `/api/workflows/tenant-lifecycle` 与 `/api/platform/users` 均缺失时整体 SKIP；网关对启用 SSO Provider
    有 60s 缓存，脚本在禁用后会轮询公开列表直至缓存过期（最长 75s）；`/api/auth/tenants` 的可切换候选按
    `password_hash` 匹配（含随机盐），同密码但独立创建的账号可能不被列出，该条按实测降级 SKIP 并在
    detail 注明契约缺口。
15. `e2e-v11.ps1` 依赖 `docs/21` 的平台审计中心（`GET /api/platform/audits`、`.../export?format=csv`）：
    **接口缺失（404/405/500/501）时整体 SKIP**；租户管理员/AUDITOR 访问平台审计应为 403，未认证 401。

## 执行

```powershell
# v1 全链路 E2E（默认 http://localhost:18080）
pwsh ./tests/e2e.ps1

# v2 身份域 E2E（组织/账号/评级/软删除）
pwsh ./tests/e2e-v2.ps1 -BaseUrl http://localhost:18080

# v3 登录与 RBAC E2E（租户账号登录 / ORG_ADMIN / MEMBER / auditor）
pwsh ./tests/e2e-v3.ps1 -BaseUrl http://localhost:18080

# v4 账号安全与合规 E2E（锁定/改密/登录事件/CSV/脱敏/评级/限流）
pwsh ./tests/e2e-v4.ps1 -BaseUrl http://localhost:18080

# v5 技能中心/入驻/导入/MCP/权限 E2E
# 网关不代理 /internal/**，purge 断言需直连 tenant-service（否则该两条 SKIP）
pwsh ./tests/e2e-v5.ps1 -BaseUrl http://localhost:18080 -TenantServiceUrl http://localhost:8081

# v5.1 技能版本与调试 E2E（上传→多版本→旧版本详情→debug 命中/未命中→404→清理）
pwsh ./tests/e2e-skill.ps1 -BaseUrl http://localhost:18080

# v6 OIDC/MFA E2E（OIDC 未配置时断言 enabled=false；MFA 走 TOTP 全流程）
pwsh ./tests/e2e-v6.ps1 -BaseUrl http://localhost:18080

# v6B SCIM E2E（SCIM token/Users/Groups/过滤/分页/错误 schema/清理）
pwsh ./tests/e2e-v6b.ps1 -BaseUrl http://localhost:18080

# v7 配额/用量/RPM E2E（配额字段→组织/账号超限→导入超限→PUT 提升→RPM 429→无限额租户）
pwsh ./tests/e2e-v7.ps1 -BaseUrl http://localhost:18080

# v8 租户导出/离户 E2E（掩码/明文/权限/审计→离户→404/重复离户）
pwsh ./tests/e2e-v8.ps1 -BaseUrl http://localhost:18080

# v10 平台用户/SSO/多租户/工作流 E2E
pwsh ./tests/e2e-v10.ps1 -BaseUrl http://localhost:18080

# v11 平台审计中心 E2E（跨租户查询/过滤/分页/CSV/权限）
pwsh ./tests/e2e-v11.ps1 -BaseUrl http://localhost:18080

# 指定 k8s 端口
powershell -File ./tests/e2e-v2.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v3.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v4.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v5.ps1 -BaseUrl http://localhost:30080 -TenantServiceUrl http://localhost:8081
powershell -File ./tests/e2e-skill.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v6.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v6b.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v7.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v8.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v10.ps1 -BaseUrl http://localhost:30080
powershell -File ./tests/e2e-v11.ps1 -BaseUrl http://localhost:30080

# MCP 协议冒烟
pwsh ./tests/mcp-smoke.ps1 -BaseUrl http://localhost:18080 -ApiKey dev-mcp-key
pwsh ./tests/mcp-smoke-v2.ps1 -BaseUrl http://localhost:18080 -ApiKey dev-mcp-key

# 顶层汇总（静态检查 + v1 E2E + v1 MCP + 条件化 v2/v3/v5 E2E/MCP，带 PASS/FAIL/SKIP）
pwsh ./scripts/verify.ps1
# v5 purge 断言需要直连 tenant-service 时：
pwsh ./scripts/verify.ps1 -TenantServiceUrl http://localhost:8081
```

建议执行顺序：`e2e.ps1` → `mcp-smoke.ps1` → `e2e-v2.ps1` → `mcp-smoke-v2.ps1` → `e2e-v3.ps1` → `e2e-v4.ps1` → `e2e-v5.ps1` → `e2e-skill.ps1` → `e2e-v6.ps1` → `e2e-v6b.ps1` → `e2e-v7.ps1` → `e2e-v8.ps1` → `e2e-v10.ps1` → `e2e-v11.ps1` → `scripts/verify.ps1`。
`verify.ps1` 会自动探测 v2/v3/v5/v5.1/v6/v6B/v7/v8/v10/v11 能力：可用则追加对应脚本，不可用则 `SKIP` 并打印缺失接口/标记。

## 版本能力门（v2 rollout 期间的关键行为）

`e2e-v2.ps1` 在开跑前做能力探测（不创建数据）：

1. 登录 admin 成功；
2. `GET /api/tenants?page=0&size=1` 可用；
3. `GET /api/tenants/{tenantId}/org-units`、`/accounts`、`/ratings/current` 三个路由存在
   （旧部署对未知路径返回 500，v2 返回 200/404，因此可区分）；
4. `TenantView` 出现 `ratingGrade` 等 v2 字段（有存量租户时校验）。

任一缺失 → `[SKIP] v2 api availability - new API not deployed; missing: ...`，其余断言全部 SKIP，退出码 0。
`scripts/verify.ps1` 中对应 `E2E v2 chain` / `MCP v2 smoke` 两条结果也按 SKIP 处理，不拖红旧环境。

### v3 能力门

`e2e-v3.ps1` 在创建数据前探测：

1. admin 登录成功且 `principal` 含 `authType`（gateway v3 标记，docs/13 §2.3）；
2. v2 基础能力存在：`GET /api/tenants` 的 `ratingGrade` 字段 + `org-units`/`accounts`/`ratings` 路由不是 5xx；
3. 创建租户/组织/账号后，租户账号登录（`tenantCode`+`username`+`password`）成功——若返回 401/404/5xx，
   说明 tenant-service `/internal/auth/authenticate` 未部署，剩余断言整体 SKIP 并说明。

`scripts/verify.ps1` 的 v3 探测复用同样的标记（`authType` + 不存在租户登录应返回 401 而非 404/500 +
`ratingGrade`），不可用时输出 `E2E v3 chain - SKIP <缺失标记>`。

### v4 能力门

`e2e-v4.ps1` 在创建数据前探测：admin 登录成功 + v2 基础接口（`GET /api/tenants` 的 `ratingGrade`）
存在；v4 的锁定/改密/登录事件/CSV 导出/脱敏/评级 delta/限流断言在接口缺失时按 SKIP 处理，不拖红旧环境。
`scripts/verify.ps1` 目前不单独跑 v4 链路（v4 与 v5 的账号安全能力由 v5 脚本的导入登录与 RBAC 断言间接覆盖）。

### v5 能力门

`e2e-v5.ps1` 在**创建任何数据前**探测 4 个 v5 标记接口（均要求 HTTP 200 且响应包 `code=0`）：

1. `GET /api/me/permissions`（权限矩阵）；
2. `GET /api/mcp/config`（MCP 控制台）；
3. `GET /api/agents/catalog`（Agent 目录）；
4. `GET /api/platform/roles`（角色管理）。

任一缺失 → `[SKIP] v5 api availability - v5 API not deployed; missing: ...`，其余 45 条断言全部 `SKIP`，
退出码 0。`scripts/verify.ps1` 复用同一探测，输出 `E2E v5 chain - SKIP <缺失标记>` 或
`E2E v5 chain - PASS import/onboarding/skills/agents/mcp/rbac/purge`。

> MCP 工具总数已确认为 **22**（原 14 + 8 个身份域工具），`docs/05-mcp-spec.md` 已同步。
> `mcp-smoke-v2.ps1` 默认断言 22；如后续再新增工具，可用 `-ExpectedToolCount` 覆盖。

### v5.1 技能版本/调试能力门

`e2e-skill.ps1` 在**创建任何数据前**探测：

1. `GET /api/skills/template` 返回 zip（PK 魔数）；
2. `GET /api/skills` 至少有一个技能包；
3. 对第一个技能包调用 `GET /api/skills/{id}/versions` 返回 200 且 `code=0`。

任一缺失 → `[SKIP] skill versions api availability - ...`，其余断言全部 `SKIP`，退出码 0。
（本机实测：v5 后端已能 upload/update，但 versions/debug 未部署时返回 500，此时整体 SKIP，不会误报失败。）
`scripts/verify.ps1` 复用同一探测，输出 `E2E skill versions - SKIP <缺失标记>` 或
`E2E skill versions - PASS versions + version detail + debug hit/miss + 404`。

### v6 能力门

`e2e-v6.ps1` 在创建数据前探测：

1. `GET /api/auth/oidc/status` 返回 200 且 `data.enabled` 存在（v6 阶段 A 的主标记）；
2. `GET /api/me/mfa`（admin JWT）用于判断 MFA 路由是否已部署：404/500/501 → MFA 段整体 SKIP，
   否则继续创建租户账号并跑 MFA 全流程。

OIDC 未部署 → `[SKIP] v6 api availability - v6 API not deployed; GET /api/auth/oidc/status -> ...`，
其余 22 条断言全部 `SKIP`，退出码 0。`scripts/verify.ps1` 输出 `E2E v6 chain - SKIP <缺失标记>` 或
`E2E v6 chain - PASS OIDC status/authorize + MFA enroll/login/recovery/disable`。

### v6B SCIM 能力门

`e2e-v6b.ps1` 在创建数据前探测：

1. **决定性标记** `GET /api/platform/scim-tokens`（admin JWT）：返回 200（或 400，表示 `tenantId` 必填）说明
   tenant-service SCIM 管理 API 已部署；旧部署这里通常是 404/500；
2. 辅助信息 `GET /scim/v2/ServiceProviderConfig`（无凭证）的状态仅用于输出 detail（新部署应为 401）。

任一缺失 → `[SKIP] scim api availability - SCIM API not deployed; ...`，其余 30 条断言全部 `SKIP`，退出码 0。
`scripts/verify.ps1` 复用同一探测，输出 `E2E v6b SCIM - SKIP <缺失标记>` 或
`E2E v6b SCIM - PASS SCIM token + ServiceProviderConfig + Users/Groups CRUD/filter/pagination/error schema`。

### v7 配额/用量能力门

`e2e-v7.ps1` 建租户后立即调用 `GET /api/tenants/{id}/quota` 作为能力标记：

- 200 且含 `seats/orgUnits/storageGb/apiRpm/status` → 继续全部配额断言；
- 404/405/500/501 → `[SKIP] quota api availability - ... (v7 quota API not deployed)`，其余 20 条断言全部
  `SKIP`，退出码 0（脚本会清理刚建的租户）。

`scripts/verify.ps1` 的 `Test-QuotaAvailable` 复用同一探测：取列表里第一个租户调用 quota；没有租户时
`SKIP 'no tenant available to probe...'`，不创建数据。网关 RPM 未启用时 `rpm 429` 断言为 SKIP。

### v8 导出/离户能力门

`e2e-v8.ps1` 建租户后、准备数据前调用 `GET /api/tenants/{id}/export?mask=true` 作为能力标记：

- 200 且响应含 `tenant/accounts/audits` → 继续全部导出/离户断言；
- 404/405/500/501 → `[SKIP] export api availability - ... (v8 export API not deployed)`，其余 24 条断言
  全部 `SKIP`，退出码 0（脚本会清理刚建的租户）。

`scripts/verify.ps1` 的 `Test-ExportAvailable` 复用同一探测：取列表里第一个租户调用 export；没有租户时
`SKIP 'no tenant available to probe...'`，不创建数据。离户后的 `TENANT_OFFBOARDED` 审计若没有可读审计
API（租户审计/平台审计）则该条为 SKIP。

### v10 平台身份/SSO/多租户能力门

`e2e-v10.ps1` 登录后、创建数据前探测：

1. `GET /api/workflows/tenant-lifecycle`（admin JWT）：200 且 `data.states` 存在；
2. `GET /api/platform/users`（admin JWT）：200。

两者均缺失 → `[SKIP] v10 api availability - v10 API not deployed; ...`，其余 31 条断言全部 `SKIP`，退出码 0。
`scripts/verify.ps1` 的 `Test-V10Available` 复用同一探测，输出 `E2E v10 chain - SKIP <缺失标记>` 或
`E2E v10 chain - PASS platform users + SSO providers + tenant selection/switch + workflow`。

已知契约缺口：`/api/auth/tenants` 的候选按 `password_hash`（含随机盐）匹配，两个租户分别用相同明文密码创建
账号时盐不同，候选可能只返回当前租户；脚本在「无其它租户」时将该条降级为 SKIP 并在 detail 记录，
`switch-tenant` 仍单独断言（当前后端可成功换发 JWT）。

### v11 平台审计中心能力门

`e2e-v11.ps1` 登录后、创建数据前探测 `GET /api/platform/audits?page=0&size=1`（admin JWT）：

- 200 且 `data` 为 PageResult（含 `items/total`）→ 继续全部审计断言；
- 404/405/500/501 → `[SKIP] v11 api availability - ... (v11 audit center not deployed)`，其余 21 条断言
  全部 `SKIP`，退出码 0；
- 其他状态（如 admin 403）→ FAIL，便于发现权限/网关代理问题。

`scripts/verify.ps1` 的 `Test-V11Available` 复用同一探测，输出 `E2E v11 audit - SKIP <缺失标记>` 或
`E2E v11 audit - PASS cross-tenant audits + filters/pagination + CSV export + permissions`。

## 断言与退出码

- 每个断言输出一行 `[PASS]` / `[FAIL]` / `[SKIP]`，末尾输出汇总表与 `PASS=x FAIL=y SKIP=z`。
- **任一 FAIL → 退出码 1**；全部通过或仅 SKIP → 退出码 0。
- 网关不可达时，脚本把全部断言标为 `SKIP` 并退出 0（并行开发/服务未启动不属于测试失败）。
- `e2e-v2.ps1` 全流程使用唯一随机编码（`e2e-v2-*` / `org-*` / `e2e.user-*`），可重复执行；
  失败中断时会尽力软删本次创建的租户（`tenant.delete`）。

### e2e.ps1 断言清单（v1）

| # | 断言 | 说明 |
|---|---|---|
| 1 | preflight gateway | `GET /actuator/health` 可访问 |
| 2 | login | `POST /api/auth/login` 返回 token 与 principal |
| 3 | tenant create | 创建后状态为 `DRAFT` |
| 4 | tenant submit | 提交后状态属于 `SUBMITTED/RISK_REVIEW/PENDING_APPROVAL/...` |
| 5 | approval decision | 找到该租户审批单，以 approver 身份 `APPROVE`（自动通过则 SKIP） |
| 6 | tenant active | 轮询租户直到 `ACTIVE`（`-TimeoutSeconds`，默认 120s） |
| 7 | mcp tools/call | `tenant.list` 结果中包含新建租户编码 |
| 8 | cli query | `java -jar tenancy-cli.jar ... tenant list --keyword <code>` 输出该租户 |

### e2e-v2.ps1 断言清单

| # | 断言 | 契约依据 |
|---|---|---|
| 1 | preflight gateway / login admin | docs/02 §1 |
| 2 | v2 api availability | docs/11 §6 路由与 `TenantView` 字段 |
| 3 | tenant create（DRAFT） | docs/02 §2 |
| 4-6 | org company / department / sub-department create | docs/11 §6.2：`parentId/code/name/type`，返回 `path/depth` |
| 7 | org tree format | `?format=tree` 嵌套 `children` 层级正确 |
| 8 | org flat path/depth | `?format=flat` 的 `parentId`、`depth`、`path` 前缀一致 |
| 9 | org duplicate code 409 | docs/11 §2.4：唯一约束冲突返回 409 |
| 10-12 | account create / tempPassword 无 hash / 密码策略 | docs/11 §6.3：返回一次性 `tempPassword`，≥10 位含字母+数字，响应无 hash/salt |
| 13-14 | account get / list filter | 详情与 `orgUnitId` 过滤可用 |
| 15-16 | org delete with children/accounts 409 | docs/11 §6.2：有子节点/成员 → 409 |
| 17-20 | rating assess / dims / current / history | docs/11 §6.4：`grade∈AAA~D`、五维 0-100、`/current`、历史倒序含本次 |
| 21 | tenant v2 fields | `TenantView.ratingGrade/orgUnitCount/accountCount` 与评估结果一致 |
| 22-23 | org move / move cycle rejected | docs/11 §6.2：移动后重算 `path/depth`，防环返回 400/409 |
| 24-26 | account disable / enable / reset-password | docs/11 §6.3：`status` 切换与一次性临时密码 |
| 27 | tenant delete ACTIVE 409 | docs/11 §6.1：仅非 ACTIVE 可删（无 ACTIVE 租户时 SKIP） |
| 28 | tenant delete draft success | 软删非 ACTIVE 租户 |
| 29 | tenant list hides deleted | 默认列表不可见 |
| 30 | tenant includeDeleted | `?includeDeleted=true` 可见且 `deletedAt` 非空 |

### mcp-smoke.ps1 断言清单（v1）

| # | 断言 | 说明 |
|---|---|---|
| 1 | GET /mcp/info | 返回 `protocolVersion`、`server`、`tools` |
| 2 | initialize | 返回 `protocolVersion` 与 `serverInfo.name`，可选 `Mcp-Session-Id` |
| 3 | notifications/initialized | 返回 200/202/204（规范为 202） |
| 4 | ping | 返回 result 而非 error |
| 5 | tools/list | 14 个 v1 基础工具名称与 docs/05 一致，含 inputSchema（v2 部署时共 22，脚本接受 14 或 22） |
| 6 | tools/call tenant.list | `isError=false` 且返回 content 或 structuredContent |
| 7 | unknown method | JSON-RPC `error.code = -32601` |

### mcp-smoke-v2.ps1 断言清单

| # | 断言 | 说明 |
|---|---|---|
| 1 | preflight /mcp/info | MCP 服务可达 |
| 2 | tools/list base tools | 14 个 v1 工具名称全部存在 |
| 3 | tools/list v2 tool count | 期望 22（14 + 8 个 v2 工具；可用 `-ExpectedToolCount` 覆盖） |
| 4 | tools/list v2 tools present | `org.tree/org.create/account.list/account.create/account.reset_password/rating.assess/rating.list/tenant.delete` |
| 5-7 | tools/call org.tree / account.list / rating.list | `isError=false` 且返回 `structuredContent`；无租户时先用 `tenant.list` 取，必要时 `tenant.create` 并在结束时 `tenant.delete` 清理 |

### e2e-v3.ps1 断言清单

| # | 断言 | 说明 |
|---|---|---|
| 1-3 | preflight / login admin / v3 api availability | gateway 健康、admin 登录、v3+v2 能力门 |
| 4 | tenant create | 主租户（autoSubmit=false），DRAFT |
| 5 | tenant submit | 显式提交；若进入人工审批则用 approver 账号通过 |
| 6 | tenant activate | 轮询至 `ACTIVE`（docs/13 §2.2：租户账号登录要求租户 ACTIVE） |
| 7 | second tenant create | 第二个租户（跨租户测试用），DRAFT |
| 8-11 | org company / department / member department / sibling department | 组织树：company → dept → member dept，另建 dept 的兄弟节点（子树外） |
| 12-14 | account ORG_ADMIN / MEMBER / locktest create | 管理员建号并取得一次性临时密码 |
| 15-17 | tenant login ORG_ADMIN / principal fields / JWT tid+oid | 登录 body 带 `tenantCode`；principal 含 tenantId/orgUnitId/role/authType=TENANT/mustChangePassword；JWT 解析 `tid`/`oid` |
| 18 | ORG_ADMIN tenant list scoped | 列表仅本租户，不泄露其他租户 |
| 19-20 | ORG_ADMIN create sub-department / account in subtree | 子树内写成功 |
| 21-22 | ORG_ADMIN create org/account outside subtree 403 | 子树外（兄弟节点）写被拒 |
| 23 | ORG_ADMIN grant TENANT_ADMIN rejected | 提权授予 403/400 |
| 24 | ORG_ADMIN cross-tenant 403 | 访问其他租户组织 403 |
| 25-26 | member login / principal fields | MEMBER 登录与 principal 校验 |
| 27 | MEMBER account list only self | `total=1` 且仅本人账号 |
| 28-32 | MEMBER org read / org write 403 / account create 403 / rating read 403 / tenant detail 403 | 只读、写与敏感读全被拒 |
| 33-37 | auditor login / cross-tenant list / detail / org read / org write 403 | 平台审计员跨租户只读 |
| 38 | auditor tenant delete 403 | 仅 PLATFORM_ADMIN 可删 |
| 39-40 | wrong password 401 / unified 401 | 密码错与未知租户/用户均 401 且 message 一致 |
| 41 | lockout after 5 failures (optional) | 连续 5 次错误 → LOCKED 为 PASS；未实现锁定策略为 SKIP |
| 42 | tenant cleanup | ACTIVE 租户先 terminate 再软删；第二个租户直接软删 |

### e2e-v5.ps1 断言清单（46 条，docs/15）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / v5 api availability | 网关健康、admin 登录、4 个 v5 标记接口探测 |
| A 导入 | tenant create / tenant activation | 建租户并走 submit+审批直到 `ACTIVE` |
| A 导入 | import template org / import template accounts | 下载 CSV 模板并校验表头 |
| A 导入 | org import / org import duplicate skipped | 导入 2 个组织（含父子拓扑），重复导入 `imported=0, skipped>=2` |
| A 导入 | account import / account import duplicate skipped | 导入 2 个账号，返回 2 条 `tempPasswords`；重复导入被跳过 |
| A 导入 | imported account login | 用一次性临时密码 + `tenantCode` 登录，`principal.authType=TENANT` |
| B 入驻 | onboarding compliant submit / onboarding auto activated | 合规资料（PRO + 合规行业/区域）应 `ACTIVATED`；若 `PENDING_MANUAL` 则自动激活断言 SKIP 并说明 |
| B 入驻 | onboarding status query | `GET /api/public/onboarding/{applicationNo}` 可查且状态在合法枚举内 |
| B 入驻 | onboarding non-compliant manual | `ENTERPRISE` + 高风险区域/行业 → `PENDING_MANUAL` |
| B 入驻 | onboarding pending visible | 该租户在 admin 租户列表中可见且非 `ACTIVE` |
| C 技能 | skill template download | `GET /api/skills/template` 返回 zip（PK 魔数） |
| C 技能 | skill upload draft / submit review / approve / enable / list approved enabled | zip 上传→`DRAFT`→`PENDING_REVIEW`→`APPROVED`→`enabled=true`→列表可查 |
| C 技能 | skill duplicate upload 409 | 再次上传同名技能 → HTTP 409 / code 40900 |
| C 技能 | skill reject branch | 第二个技能包走 `REJECT` → `REJECTED` |
| D Agent | agent catalog | `GET /api/agents/catalog` 含 4 类且 `description` 非空 |
| D Agent | agent run risk-review | 运行 `risk-review`，轮询至 `SUCCEEDED` |
| D Agent | agent run readable summary | run 详情 `displayName` + `summary` 非空 |
| D Agent | agent run chinese step titles | `steps[].title/actionLabel` 至少一个含中文 |
| E MCP | mcp config | `endpoint/transport/protocolVersion/sampleConfig` 齐全 |
| E MCP | mcp key create | 返回 `id/keyPrefix/apiKey`，明文只出现一次 |
| E MCP | mcp tools list with key | 用新 key 调 `tools/list`，工具数 ≥14 且含 `tenant.list` |
| E MCP | mcp tools call with key | `tools/call tenant.list`，`isError=false` |
| E MCP | mcp audit recorded | `GET /api/mcp/audits?tool=tenant.list` 能查到本次调用 |
| E MCP | mcp key revoked 401 | `DELETE /api/mcp/keys/{id}` 后再调 `/mcp` → HTTP 401 |
| E MCP | mcp external server create+list / delete | 平台管理员 CRUD `external-servers` 并验证列表 |
| F RBAC | me permissions admin | admin 的 `roles` 含 `PLATFORM_ADMIN`、`permissions` 含 `*` |
| F RBAC | platform roles builtin | 5 个内置角色齐全 |
| F RBAC | custom role create / permission set / query / permissions readback | 自定义角色 + 权限矩阵（权限码取自 `/api/platform/permissions`） |
| F RBAC | tenant account platform roles 403 | 导入的租户账号访问平台角色 API → 403 |
| G 生命周期 | purge internal token | 直连 tenant-service 调 `POST /internal/maintenance/purge`，返回 `{loginEvents,audits,collectJobs,tenants}` |
| G 生命周期 | purge no token 401 | 无 `X-Internal-Token` → 401 |

### e2e-skill.ps1 断言清单（13 条，docs/15 §12）

| # | 断言 | 说明 |
|---|---|---|
| 1 | preflight gateway / login admin | 网关健康、admin 登录 |
| 2 | skill versions api availability | template zip + `GET /api/skills/{id}/versions` 探测 |
| 3 | skill upload v1 | 生成 zip（SKILL.md + rules.yaml）上传，返回 `version=1.0.0`、`DRAFT` |
| 4 | skill versions contains v1 | 版本列表包含 `1.0.0`（含 status/enabled/latest 字段） |
| 5 | skill update new version | `PUT /api/skills/{id}` `{version:1.1.0,...}` → 新 `DRAFT` 版本 |
| 6 | skill versions multiple | 版本列表同时含 `1.0.0` 与 `1.1.0` |
| 7 | skill version detail v1 | `GET .../versions/1.0.0` 的 `instructions/rulesYaml` 仍是 v1 内容 |
| 8 | skill debug hit | `input.plan=PRO` + `version=1.0.0` → 命中 `E2E-SKILL-001`、actions≥1、durationMs≥0 |
| 9 | skill debug miss | `input.plan=FREE` → 无 `matched=true` 规则、durationMs≥0 |
| 10 | skill version detail 404 | 不存在版本（9.9.9）→ 404 / code 54400 |
| 11 | skill debug version 404 | debug 指定不存在版本 → 404 / code 54400 |
| 12 | skill cleanup | `DELETE /api/skills/{id}` → 逻辑禁用 |

### e2e-v6.ps1 断言清单（25 条，docs/16 §2/§3）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / v6 api availability | OIDC status 探测；`/api/me/mfa` 判断 MFA 是否部署 |
| OIDC | oidc status disabled | `enabled=false`、`providerName` 非空、`loginUrl` 为空（已配置 SSO 时改为 PASS 并说明） |
| OIDC | oidc authorize disabled | 未启用时 `GET /api/auth/oidc/authorize` → 404/40400 |
| OIDC | oidc callback disabled | 未启用时 `GET /api/auth/oidc/callback` → 400/40001 |
| MFA | tenant create / activation / org create / account create | 建租户并激活、建组织与账号（一次性临时密码） |
| MFA | tenant login / password change | 临时密码登录→改密→新密码重新登录 |
| MFA | mfa status initial / setup / verify / status enabled | `GET /api/me/mfa` → setup（secret/otpauthUrl/恢复码）→ TOTP verify → enabled=true |
| MFA | login mfaRequired / login mfa success | 密码登录返回 `{mfaRequired:true, challengeToken}`；`/api/auth/login/mfa` 换 JWT |
| MFA | login mfa wrong code 401 | 错误 TOTP → 401/40100 |
| MFA | mfa recovery login / single use | 恢复码登录一次成功；同一恢复码复用被拒（按实现，未支持则 SKIP） |
| MFA | mfa disable / login after disable | 禁用 MFA；再次密码登录直接发 JWT（无 mfaRequired） |
| MFA | mfa audit trail | 租户审计含 MFA_ENROLLED / MFA_VERIFIED / MFA_DISABLED |
| 清理 | tenant cleanup | 终止 + 软删测试租户 |

### e2e-v6b.ps1 断言清单（31 条，docs/16 §7.1）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / scim api availability | 网关健康、admin 登录、`/api/platform/scim-tokens` 探测 |
| 租户 | tenant create / tenant activation | 建租户并 submit+审批至 `ACTIVE`（SCIM token 绑定租户） |
| Token | scim token create | `POST /api/platform/scim-tokens {tenantId,name}` 返回 `scim_` 明文一次 + id/prefix |
| 认证 | scim no token 401 / scim bad token 401 | 无凭证与坏 token 均 401，且为 SCIM Error schema |
| 协议 | scim service provider config | `GET /scim/v2/ServiceProviderConfig` 200，schemas 含 ServiceProviderConfig |
| Users | scim user create | `POST /scim/v2/Users`（userName/displayName/emails/active）→ id + active=true |
| Users | scim user list / filter userName / get | ListResponse totalResults≥1；`filter=userName eq "..."` 命中；按 id 详情一致 |
| Users | scim user patch active false | SCIM PatchOp `replace active=false` → 200/204 且 active=false |
| Users | scim user put displayName | `PUT /scim/v2/Users/{id}` 全量更新 displayName 生效 |
| Users | scim user pagination / beyond range | `startIndex=1&count=1` 的 itemsPerPage≤1；`startIndex=999` 的 Resources 为空 |
| Users | scim user delete / list after delete | DELETE 后按 userName 过滤 totalResults=0 或 GET 404 |
| 错误 | scim error 404 schema | 未知用户 id → 404 且为 SCIM Error schema（schemas/status/detail） |
| Groups | scim group member user create | 为 Group members 准备一个 SCIM 用户 |
| Groups | scim group create / get / filter | `POST /scim/v2/Groups`（displayName）；详情；`filter=displayName eq` 命中 |
| Groups | scim group patch | PATCH `replace displayName` + `add members` 生效 |
| Groups | scim group delete / conflict branch 409 | DELETE 返回 204 或 409；返回 409 时校验 SCIM Error schema（有成员时属允许分支） |
| Token | scim token list / revoke / revoked 401 | token 列表含该 id；DELETE 撤销；撤销后 SCIM 调用 → 401 |
| 清理 | scim tenant cleanup | 终止 + 软删测试租户（含尽力清理 group/member user） |

### e2e-v7.ps1 断言清单（23 条，docs/17）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / tenant create | 建租户（`quotaSeats=2 quotaOrgUnits=2 quotaApiRpm=3`，DRAFT） |
| 配额 | quota api availability | `GET /api/tenants/{id}/quota` 200 且含 seats/orgUnits/storageGb/apiRpm/status |
| 配额 | quota fields | `seats.limit=2`、`orgUnits.limit=2`、`apiRpm.limit=3`、used/remaining/percent 齐全、`storageGb.used=null`、status=OK |
| 组织 | org within quota / org over quota 409 | 在组织上限内创建成功；再加一个 → 409/429 `QUOTA_EXCEEDED`（message 含 used/limit） |
| 席位 | account within quota / account over quota 409 | 在席位上限内创建成功；再加一个 → 409/429 `QUOTA_EXCEEDED` |
| 导入 | import org quota enforcement | CSV 两行：重复 code → skipped；超限行 → failed；`imported=0` 且整批不中断 |
| 导入 | import account quota enforcement | CSV 两行：重复 username → skipped；超限行 → failed；整批不中断 |
| 更新 | quota update / quota reflects update | `PUT /api/tenants/{id}` 把 quotaSeats/quotaOrgUnits 提到 5；quota API 返回新 limit |
| 更新后 | org create after quota update / account create after quota update | 提升后可继续创建第 3 个组织/账号 |
| 生命周期 | tenant activation | submit + 审批至 `ACTIVE`（租户账号登录前提） |
| RPM | rpm 429 | 租户 JWT 连续 6 次请求，出现 429/42901 即 PASS；网关未启用限流 → SKIP |
| 无限额 | unlimited tenant create / activation / quota fields | 不传配额建租户 → quota limit 为 null/0 |
| 无限额 | unlimited org not limited / account not limited | 无配额租户创 3 个组织/3 个账号均成功 |
| 清理 | tenant cleanup | 软删两个测试租户 |

### e2e-v8.ps1 断言清单（28 条，docs/18）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / tenant create | 建导出测试租户 A（DRAFT） |
| 能力门 | export api availability | `GET /api/tenants/{A}/export?mask=true` 200 且含 tenant/accounts/audits |
| 数据准备 | tenant activation / org create / account create / rating assess | 激活 A；2 个组织；MEMBER/ORG_ADMIN/TENANT_ADMIN 账号；评级评估 |
| 导出 | export masked structure | 200 `application/json` + `Content-Disposition: attachment; filename="tenant-<code>-export-..."`；`exportedAt/tenant/orgUnits/accounts/rating.current+history/audits` 齐全 |
| 掩码 | export masked pii | 所有账号 email/phone 含 `*`，且导出正文不出现账号明文 email |
| 掩码 | export default mask | 不带 mask 参数默认掩码 |
| 权限 | export tenant admin masked allowed | TENANT_ADMIN 掩码导出 200 |
| 权限 | export unmasked admin | admin `mask=false` 返回明文 email/phone |
| 权限 | export unmasked tenant admin / auditor | 非平台管理员 `mask=false` → 403（或保持掩码，按实测记录） |
| 权限 | export forbidden member 403 / forbidden org admin 403 | MEMBER 与 ORG_ADMIN 导出 → 403 |
| 审计 | export audit TENANT_EXPORTED | 后续导出的 audits 中含 `TENANT_EXPORTED` |
| 离户准备 | offboard tenant setup | 建租户 B 并激活（ACTIVE 才允许离户） |
| 离户状态 | offboard invalid state 409 | 对 DRAFT 租户 C 离户 → 409 |
| 离户校验 | offboard reason required | 空 reason → 400，且租户状态不变 |
| 离户权限 | offboard forbidden | 租户 JWT 调离户 → 403（仅 PLATFORM_ADMIN） |
| 离户 | offboard success | `status=TERMINATED`、`deletedAt` 非空 |
| 离户后 | tenant detail after offboard 404 / export after offboard 404 | 软删后详情与导出均 404 |
| 幂等 | offboard repeated | 重复离户 → 404/409 均允许 |
| 审计 | offboard audit TENANT_OFFBOARDED | 通过租户审计/平台审计探测；无读取 API 时 SKIP |
| 清理 | tenant cleanup | 软删 A、C（B 已离户） |

### e2e-v10.ps1 断言清单（34 条，docs/20）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / v10 api availability | workflow + platform users 探测 |
| 平台用户 | platform user create / list / login | 创建（临时密码一次）、列表、用新账号登录（roles 含 APPROVER） |
| 平台用户 | disable / disabled login 401 / re-enable | PUT DISABLED → 登录 401 → PUT ACTIVE |
| 平台用户 | reset password / login after reset / delete / deleted login 401 | 重置返回一次性临时密码；删除后登录 401 |
| SSO | sso provider create / list no secret | 创建（不可达 issuer、enabled=false）；列表含 `hasClientSecret` 但不返回 secret |
| SSO | enable / public list enabled / test unreachable / disable / public list disabled | 公开列表只含 enabled；不可达 issuer test → 400/40001；禁用后轮询公开列表（网关 60s 缓存）直至移除 |
| SSO | delete | 删除 Provider |
| 多租户 | tenant A/B create+activation | 两个 ACTIVE 租户 |
| 多租户 | shared account A/B create | 同 username + 同 initialPassword 账号 |
| 多租户 | login tenant selection required | 无 tenantCode 登录 → `tenantSelectionRequired=true` 且 2 个候选项 |
| 多租户 | login with tenantCode | 显式 tenantCode 登录，principal.tenantId 正确 |
| 多租户 | auth tenants list contains other | `/api/auth/tenants` 至少含另一租户（hash+salt 限制时 SKIP 并注明） |
| 多租户 | switch tenant | `/api/auth/switch-tenant` 换发目标租户 JWT，tenantId 变化 |
| 工作流 | workflow structure / activities map skills | states≥8、transitions>0；activities 均映射 skillId |
| 清理 | tenant cleanup | 软删两个租户 |

### e2e-v11.ps1 断言清单（24 条，docs/21）

| 分组 | 断言 | 说明 |
|---|---|---|
| 前置 | preflight gateway / login admin / v11 api availability | `/api/platform/audits` 探测（PageResult） |
| 数据 | tenant A create / update / org create / account create / activation | 触发 `CREATED` / `UPDATED` / `ORG_CREATED` / `ACCOUNT_CREATED` / `SUBMITTED` 审计 |
| 权限准备 | tenant admin login / tenant B create | 租户管理员 JWT；第二租户用于跨租户记录 |
| 查询 | platform audits cross tenant | 返回含 tenantId/tenantCode/tenantName/at/actor/action/detail，A/B 两租户均有记录 |
| 过滤 | filter tenantId / filter action / filter keyword | 按租户 id、动作（CREATED）、关键字（租户 code）过滤均生效 |
| 分页 | pagination page size / pagination total | size=2 时每页 ≤2 且 total 稳定；page0/page1 不重叠 |
| 排序 | sorting at desc | `at` 倒序 |
| 权限 | tenant admin 403 / auditor 403 / unauthenticated 401 | 平台审计仅 PLATFORM_ADMIN |
| CSV | csv export structure / csv export filter | `text/csv` + attachment；内容含租户 code 与动作；tenantId 过滤后不含另一租户 |
| 审计 | platform audit exported | 导出自身写 `PLATFORM_AUDIT_EXPORTED` 并可查 |
| 清理 | tenant cleanup | 软删两个租户 |

## 预期输出示例

旧环境（无 v2 接口）：

```
[SKIP] v2 api availability - new API not deployed; missing: GET /api/tenants/{tenantId}/org-units; ...
PASS=2  FAIL=0  SKIP=29
result: PASS
```

v2 环境：

```
[PASS] org tree format - company -> department -> sub-department nesting verified
[PASS] rating assess - grade=B score=64 source=SKILL
[PASS] tenant delete draft success - HTTP 200
PASS=30  FAIL=0  SKIP=1
result: PASS
```

v3 环境：

```
[PASS] tenant submit - status=ACTIVE
[PASS] tenant activate - status=ACTIVE
[PASS] tenant login ORG_ADMIN - user=E2E V3 Org Admin token issued
[PASS] JWT tid/oid claims - tid=... oid=...
[PASS] ORG_ADMIN create org outside subtree 403 - HTTP 403 code=40300
[PASS] MEMBER account list only self - total=1 username=...
[PASS] auditor cross-tenant tenant list - tenants=18
[PASS] lockout after 5 failures (optional) - account LOCKED after 5 failed attempts
PASS=42  FAIL=0  SKIP=0
result: PASS
```

v4 环境（账号安全/合规）：

```
[PASS] account locked after failures - login rejected after 5 failures
[PASS] audit csv export - contentType=text/csv;charset=UTF-8 rows=...
[PASS] pii masking - email=v****@example.com
[PASS] login rate limit 429 - 11th attempt returns 429/42900
PASS=20  FAIL=0  SKIP=0
result: PASS
```

v5 环境：

```
[PASS] v5 api availability - me/permissions + mcp/config + agents/catalog + platform/roles present
[PASS] org import - imported=2 skipped=0 failed=0
[PASS] onboarding auto activated - applicationNo=ONB... status=ACTIVATED
[PASS] skill approve - status=APPROVED
[PASS] agent run chinese step titles - title=读取租户画像
[PASS] mcp key revoked 401 - revoked key rejected with HTTP 401
[PASS] tenant account platform roles 403 - HTTP 403 code=40300
[PASS] purge internal token - loginEvents=0 audits=0 collectJobs=0 tenants=0
PASS=46  FAIL=0  SKIP=0
result: PASS
```

旧环境（v4 部署，尚无 v5 接口）：

```
[PASS] preflight gateway - http://localhost:30080
[PASS] login admin - token issued
[SKIP] v5 api availability - v5 API not deployed; missing: GET /api/me/permissions (HTTP 500); ...
[SKIP] ... (其余 45 条)
PASS=2  FAIL=0  SKIP=44
result: PASS
```

v5.1 技能版本/调试环境：

```
[PASS] skill versions api availability - template bytes=1826 versions(tenant-intake) ok
[PASS] skill upload v1 - id=e2e-skill-... version=1.0.0 status=DRAFT
[PASS] skill versions multiple - versions=1.1.0,1.0.0
[PASS] skill debug hit - decision=AUTO_APPROVE actions=1 matchedRule=E2E-SKILL-001 durationMs=2
[PASS] skill debug miss - matchedRules=1 matchedTrue=0 durationMs=2
PASS=13  FAIL=0  SKIP=0
result: PASS
```

v6 环境（阶段 A）：

```
[PASS] v6 api availability - oidc enabled=False; mfa probe HTTP 200 code=0; mfaReady=True
[PASS] oidc status disabled - enabled=false providerName=Enterprise SSO loginUrl=
[PASS] oidc authorize disabled - HTTP 404 code=40400
[PASS] mfa verify - enabled=true code=...
[PASS] login mfaRequired - mfaRequired=true challengeTokenLen=...
[PASS] mfa recovery single use - reuse rejected HTTP 401 code=40100
[PASS] mfa audit trail - actions=MFA_ENROLLED,MFA_VERIFIED,MFA_DISABLED
PASS=25  FAIL=0  SKIP=0
result: PASS
```

旧环境（v5 部署，尚无 v5.1/v6 接口）：

```
[PASS] preflight gateway
[PASS] login admin
[SKIP] skill versions api availability - GET /api/skills/tenant-intake/versions -> HTTP 500 code=50000
[SKIP] ... (其余 11 条)
PASS=2  FAIL=0  SKIP=11
result: PASS
```

v6B SCIM 环境：

```
[PASS] scim api availability - scimConfig HTTP 401; tokenApi HTTP 200
[PASS] scim token create - id=... prefix=scim_... plaintextLen=...
[PASS] scim service provider config - HTTP 200 patch=True
[PASS] scim user patch active false - HTTP 200 active=false
[PASS] scim user pagination - startIndex=1 count=1 itemsPerPage=1 total=1
[PASS] scim group delete - HTTP 409 (allowed: group still has members/children)
[PASS] scim group delete conflict branch 409 - SCIM Error schema with status=409
[PASS] scim token revoked 401 - revoked token rejected with HTTP 401
PASS=31  FAIL=0  SKIP=0
result: PASS
```

旧环境（无 SCIM 接口）：

```
[PASS] preflight gateway
[PASS] login admin
[SKIP] scim api availability - SCIM API not deployed; ...
[SKIP] ... (其余 29 条)
PASS=2  FAIL=0  SKIP=29
result: PASS
```

v7 配额/用量环境：

```
[PASS] quota fields - seats 0/2 orgUnits 0/2 apiRpm=3 status=OK
[PASS] org within quota - created=2 (orgUnits limit=2 used=0)
[PASS] org over quota 409 - HTTP 409 code=42900 message=tenant org unit quota exceeded (used=2, limit=2)
[PASS] import org quota enforcement - imported=0 skipped=1 failed=1 (batch not interrupted)
[PASS] quota reflects update - seats.limit=5 orgUnits.limit=5 status=OK
[PASS] rpm 429 - limited at 4:HTTP429/code42901
[PASS] unlimited org not limited - created 3 org units without quota
PASS=23  FAIL=0  SKIP=0
result: PASS
```

旧环境（无 quota API）：

```
[PASS] preflight gateway
[PASS] login admin
[PASS] tenant create
[SKIP] quota api availability - GET /api/tenants/{id}/quota -> HTTP 404 (v7 quota API not deployed)
[SKIP] ... (其余 20 条)
PASS=3  FAIL=0  SKIP=20
result: PASS
```

v8 导出/离户环境：

```
[PASS] export masked structure - orgUnits=2 accounts=3 audits=5 filename=attachment; filename="tenant-e2e-v...
[PASS] export masked pii - accounts=3 sample=e***@example.com
[PASS] export unmasked admin - email=... phone=13800001234
[PASS] export unmasked tenant admin - HTTP 403 (unmask restricted to PLATFORM_ADMIN)
[PASS] export forbidden member 403 - HTTP 403
[PASS] export audit TENANT_EXPORTED - audit list contains TENANT_EXPORTED
[PASS] offboard success - status=TERMINATED deletedAt=2026-11-08T12:19:10.429Z
[PASS] tenant detail after offboard 404 - HTTP 404
[PASS] offboard repeated - HTTP 404 code=40400 (404/409 both allowed)
PASS=28  FAIL=0  SKIP=0
result: PASS
```

旧环境（无 export API）：

```
[PASS] preflight gateway
[PASS] login admin
[PASS] tenant create
[SKIP] export api availability - GET /api/tenants/{id}/export -> HTTP 500 (v8 export API not deployed)
[SKIP] ... (其余 24 条)
PASS=3  FAIL=0  SKIP=25
result: PASS
```

v10 平台身份环境：

```
[PASS] v10 api availability - workflow HTTP 200 users HTTP 200
[PASS] platform user create - id=... tempPasswordLen=16
[PASS] platform user disabled login 401 - HTTP 401
[PASS] sso provider list no secret - providers=1 hasClientSecret=true, secret not exposed
[PASS] sso provider public list disabled - disabled provider not exposed (gateway provider cache expired after ~0s)
[PASS] login tenant selection required - tenants=e2e-v10-a-...,e2e-v10-b-...
[SKIP] auth tenants list contains other - docs/20 section 3 lists candidates by identical password_hash (salt included); ...
[PASS] switch tenant - switch to tenantB (...) succeeded
[PASS] workflow structure - states=10 transitions=29
PASS=33  FAIL=0  SKIP=1
result: PASS
```

旧环境（无 v10 API）：

```
[PASS] preflight gateway
[PASS] login admin
[SKIP] v10 api availability - v10 API not deployed; workflow HTTP 500, platform users HTTP 500
[SKIP] ... (其余 31 条)
PASS=2  FAIL=0  SKIP=32
result: PASS
```

v11 平台审计环境：

```
[PASS] v11 api availability - HTTP 200 total=...
[PASS] platform audits cross tenant - total=... tenantA=... tenantB=... tenantName=...
[PASS] filter tenantId - items=... all for tenantA
[PASS] filter action - items=... action=CREATED
[PASS] pagination page size - page0=2 page1=2 total=...
[PASS] sorting at desc - items=...
[PASS] platform audits tenant admin 403 - HTTP 403
[PASS] csv export structure - HTTP 200 contentType=text/csv attachment=true
[PASS] platform audit exported - items=1
PASS=24  FAIL=0  SKIP=0
result: PASS
```

旧环境（无 v11 API）：

```
[PASS] preflight gateway
[PASS] login admin
[SKIP] v11 api availability - GET /api/platform/audits -> HTTP 500 (v11 audit center not deployed)
[SKIP] ... (其余 21 条)
PASS=2  FAIL=0  SKIP=22
result: PASS
```

## 常见问题

| 现象 | 处理 |
|---|---|
| 大量 SKIP 且提示 gateway 不可达 | 先 `pwsh ./scripts/run-local.ps1` 或 `kubectl -n <ns> port-forward svc/gateway 30080:8080`，等健康检查通过 |
| `v2 api availability` SKIP 且列出缺失接口 | 部署仍是 v1：需重新构建/发布 tenant-service v2（task-8）与 mcp-service v2（task-10） |
| `org delete ... 409` FAIL 且返回 200 | 删除保护缺失：检查组织删除是否校验 `childCount/accountCount` |
| `tenant delete ACTIVE 409` FAIL | 软删未校验 ACTIVE 状态 |
| `tools/list v2 tool count` FAIL（例如 20 vs 22） | 契约口径不一致，确认 task-10 的 8 个新工具；必要时 `-ExpectedToolCount 20` |
| `v3 api availability` SKIP（gateway v3 marker） | 部署的 gateway 仍是 v2：需要重新构建/发布 gateway（task-15） |
| `tenant login ORG_ADMIN` SKIP（tenant login channel） | tenant-service 未部署 `POST /internal/auth/authenticate`（v3 收尾中），部署后重跑 |
| `ORG_ADMIN ... 403` FAIL 且返回 200 | RBAC 子树校验缺失：检查 `SecurityScope`/`TenantAccess` 对 orgUnitId 的路径前缀判定 |
| `MEMBER account list only self` FAIL | MEMBER 可见范围错误：账号列表未按本人过滤 |
| `lockout after 5 failures` SKIP | 锁定策略为可选实现（docs/13 §2.2），不算失败 |
| `e2e` 中 approval decision FAIL | 确认 approval-service 已启动且 approver 账号可用；看 `.run/logs/approval-service.err.log` |
| `tenant active` 超时 | 检查 provisioning 链路（approval → agent-service）日志；租户可能停在 `PROVISIONING` |
| CLI query SKIP | 未构建 fat jar：`pwsh ./scripts/build.ps1 -Module tenancy-cli` |
| MCP 401/403 | 确认 `-ApiKey` 与 `TENANCY_SECURITY_API_KEYS`（默认 `dev-mcp-key`）一致 |
| `v5 api availability` SKIP 且列出 4 个路径 | 部署仍是 v4：需发布 v5 的 gateway（`/api/mcp/**`、`/api/me/**`、`/api/platform/roles`）、tenant-service（入驻/导入/RBAC/purge）、agent-service（技能中心/catalog）、mcp-service（key/审计/外部服务器） |
| `import template org/accounts` SKIP | tenant-service v5 未部署导入接口；确认 `GET /api/tenants/{tid}/import-templates/*.csv` 返回 CSV |
| `onboarding auto activated` SKIP | 合规申请落 `PENDING_MANUAL`：agent-service 不可达导致 risk-review 降级为 MEDIUM，或返回风险不是 `LOW`；先确认 agent-service 健康 |
| `onboarding pending visible` FAIL | 非合规申请创建的租户未出现在列表：检查 tenantCode 是否唯一、`GET /api/tenants?keyword=` 是否可查 |
| `skill upload draft` FAIL（HTTP 415/400） | multipart 字段名必须是 `file`（`POST /api/skills/upload`），zip 需含 `SKILL.md`（frontmatter 必填 id/name/version/type/description） |
| `skill duplicate upload 409` FAIL（HTTP 200） | 技能包唯一键需按 `(id, version)` 或 id 去重，确认上传冲突返回 409/40900 |
| `tenant account platform roles 403` SKIP | 导入账号登录未成功（A 组失败）：先修 `imported account login` |
| `purge internal token` SKIP 且提示 gateway 未暴露 | 网关按设计不代理 `/internal/**`：加 `-TenantServiceUrl http://localhost:8081`（k8s 用 `kubectl port-forward svc/tenant-service 8081:8081`） |
| `purge internal token` FAIL（401） | `X-Internal-Token` 与服务端 `TENANCY_INTERNAL_TOKEN` 不一致（默认 `dev-internal-token`） |
| `mcp external server ...` SKIP | mcp-service v5 外部服务器接口未部署；确认 `GET/POST /api/mcp/external-servers` |
| 入驻 POST 返回 429 | 公开入驻限流 5 次/分钟；脚本已随机 `X-Forwarded-For` 并自动等待 60s 重试一次，仍失败时稍后重跑 |
| `scim api availability` SKIP（tokenApi 404/500） | tenant-service 未部署 SCIM 管理 API：确认 `GET/POST /api/platform/scim-tokens` 可用（平台管理员 JWT） |
| `scim no token 401` FAIL（无 SCIM Error schema） | `/scim/**` 未加入 gateway/tenant-service 白名单，请求被 JWT 过滤器拦截；docs/16 §7.1 要求白名单 + SCIM Error |
| `scim service provider config` FAIL（schemas 为空） | PS 5.1 对 `application/scim+json` 响应的 `Content` 是 byte[]：脚本已按 UTF-8 解码；若自研客户端解析为空，检查是否正确处理 byte[]/编码 |
| `scim user create` FAIL（HTTP 400/409） | User 映射需 `userName` 唯一（→ accounts.username）；检查 `emails[0].value`、`roles[0].value` 是否合法 |
| `scim user patch active false` FAIL（仍 true） | PATCH 需支持 `{schemas:[PatchOp], Operations:[{op:'replace',path:'active',value:false}]}` → accounts.status=DISABLED |
| `scim group delete` FAIL（非 204/409） | Group→org_units；有成员/子节点时应返回 409（允许分支），无成员时应 204 |
| `scim group delete conflict branch 409` FAIL | 409 响应必须是 SCIM Error schema（schemas 含 `...2.0:Error`、status=409、detail） |
| `scim token revoked 401` FAIL | 撤销后旧 token 仍可用：检查 `scim_tokens.status=REVOKED` 是否在认证时校验 |
| `scim tenant cleanup` FAIL | 租户软删失败：先确认 SCIM 创建的 group/org 不会阻塞软删，或脚本 cleanup 已尽力删除 group/member user |
| `skill versions api availability` SKIP（versions 500） | agent-service 已能 upload/update 但版本表/接口未部署：确认 `GET /api/skills/{id}/versions` 返回 200，且 `skill_packages` 迁移已执行 |
| `skill update new version` FAIL（409 不可编辑） | `PUT` 只能在最新版本为 `DRAFT/REJECTED` 时创建新版本；技能若已审核通过需先基于版本新建草稿，或改用新的技能 id |
| `skill debug hit` FAIL（matchedRule 不含 E2E-SKILL-001） | 上传的 v1 `rules.yaml` 需含 `E2E-SKILL-001` 规则且 `when.plan eq PRO`；检查 zip 是否覆盖 SKILL.md/rules.yaml 两个条目 |
| `skill version detail 404` FAIL（HTTP 500） | 版本详情接口未部署或 `skill_packages` 表缺列；确认 `GET /api/skills/{id}/versions/{version}` |
| `skill debug version 404` FAIL（HTTP 200） | 不存在版本应返回 404/54400，而不是回退到最新版本 |
| `v6 api availability` SKIP（oidc status 401/404） | gateway 未部署 OIDC：确认 `/api/auth/oidc/**` 已加入 gateway 白名单并返回 200 |
| `oidc authorize disabled` FAIL（HTTP 302） | OIDC 被配置为启用（`TENANCY_OIDC_ENABLED=true`）：脚本会改为断言 enabled=true；如只想验证未配置路径，请清空 OIDC env 后重启 gateway |
| `mfa ...` 全部 SKIP 且 mfaProbe 404/500 | tenant-service v6 未部署 `/api/me/mfa`；部署后重跑 |
| `mfa verify` FAIL（401） | 本机与后端时钟偏差超过 ±30s：同步系统时间（UTC）后重跑；脚本已尝试 ±1 窗口 |
| `login mfaRequired` FAIL（无 challengeToken） | `/internal/auth/authenticate` 未返回 `mfaRequired`；确认租户账号 MFA 已 enabled 且 gateway 透传该响应 |
| `mfa recovery login` SKIP | 恢复码流程按实现可选；若后端未支持恢复码，断言为 SKIP（不算失败），单次使用断言同样 SKIP |
| `quota api availability` SKIP（404/500） | tenant-service v7 未部署 quota API：确认 `GET /api/tenants/{id}/quota` 返回 200，且 V8 迁移已执行（`quota_org_units`） |
| `quota fields` FAIL（`quotaOrgUnits` 缺失/默认 50） | 建租户请求里的 `quotaOrgUnits` 未持久化：确认 V8 迁移回填与 `TenantCreateRequest` 已加字段 |
| `org over quota 409`/`account over quota 409` FAIL（HTTP 200） | 强制点缺失：`POST org-units` 需校验 `usedOrgUnits >= limit`，`POST accounts` 需校验 `usedSeats >= limit`（409 `QUOTA_EXCEEDED`） |
| `import org/account quota enforcement` FAIL（failed=0） | 导入未逐行检查配额：超限行应记 `failed`（不中断整批），重复行记 `skipped` |
| `quota update`/`quota reflects update` FAIL | `PUT /api/tenants/{id}` 未接受 `quotaOrgUnits/quotaSeats`，或 quota API 仍用旧上限；确认 DRAFT 状态可更新 |
| `rpm 429` SKIP | 网关租户 RPM 限流未启用（docs/17 §5 默认不启用）：确认 `quota_api_rpm=3` 且网关内部 quota 缓存生效 |
| `rpm 429` FAIL（前 4 次未限流但后续出现） | 限流窗口/计数口径不同：检查是否只对携带 `tid` 的 JWT 计数、平台管理员不计数 |
| `unlimited quota fields` FAIL（limit 非空） | 建租户未传配额时应存 NULL（<=0 也视为不限）；检查 `TenantCreateRequest` 默认值与回填逻辑 |
| `export api availability` SKIP（404/500） | tenant-service v8 导出接口未部署：确认 `GET /api/tenants/{id}/export?mask=true` 返回 JSON bundle 且带 `Content-Disposition` |
| `export masked structure` FAIL（缺 orgUnits/accounts/rating/audits） | 导出 bundle 字段不全：按 docs/18 §2 补齐 `tenant/orgUnits/accounts/rating/audits`，rating 含 current/history |
| `export masked pii` FAIL | 掩码未生效或明文泄漏：确认默认 `mask=true`、`PiiMask` 作用于 email/phone，且导出正文不含原始 PII |
| `export unmasked admin` FAIL（仍掩码） | 平台管理员 `mask=false` 未返回明文：检查权限分支与 `mask` 参数解析 |
| `export unmasked tenant admin`/`auditor` FAIL（200 明文） | `mask=false` 未限制为 PLATFORM_ADMIN：非平台管理员应 403 或保持掩码 |
| `export forbidden member 403`/`org admin 403` FAIL（200） | 导出权限未收紧：仅 PLATFORM_ADMIN / TENANT_ADMIN / AUDITOR 可读，ORG_ADMIN/MEMBER 403 |
| `export audit TENANT_EXPORTED` FAIL | 导出未写审计：每次导出应记录 `TENANT_EXPORTED`（mask 与条目数，不含 PII） |
| `offboard reason required` FAIL（空 reason 被接受） | 离户 `reason` 未校验：需 400/40001 且租户状态不变 |
| `offboard success` FAIL | 离户未返回 `TERMINATED` + `deletedAt`：检查 terminate→软删→审计流程 |
| `tenant detail after offboard 404`/`export after offboard 404` FAIL | 离户后未软删（仍可读）：软删后详情与导出一律 404 |
| `offboard repeated` FAIL（非 404/409） | 重复离户应 404 或 409，不允许 200 或 500 |
| `offboard audit TENANT_OFFBOARDED` SKIP | 软删后无审计读取 API（租户审计 404、无平台审计接口）：审计已在服务端记录，必要时通过 DB/保留期审计核对 |
| `offboard invalid state 409` FAIL | 非 ACTIVE/SUSPENDED 状态应拒绝离户（409），检查状态机校验 |
| `v10 api availability` SKIP（workflow/users 均 404/500） | tenant-service v9 未部署平台用户/SSO/工作流：确认 `GET /api/workflows/tenant-lifecycle`、`GET /api/platform/users` 可用 |
| `platform user create` FAIL（无 temporaryPassword） | 创建未传密码时应返回一次性 `temporaryPassword`；检查 `PlatformUserView.temporaryPassword` |
| `platform user disabled login 401` FAIL（仍可登录） | 禁用状态未参与认证：`/internal/platform-users/authenticate` 应拒绝 DISABLED |
| `platform user login after reset` FAIL | 重置密码应返回新临时密码且立即生效；确认 reset-password 写回 password_hash |
| `sso provider list no secret` FAIL | 管理端列表泄漏 `clientSecret`：只应暴露 `hasClientSecret` |
| `sso provider test unreachable` FAIL（200 valid=true） | 不可达 issuer 的 discovery 应失败（400/40001 或 valid=false） |
| `sso provider public list enabled/disabled` FAIL | 公开列表只含 enabled 且不含 secret；网关对 provider 有 60s 缓存，脚本已轮询 75s，仍失败说明缓存/过滤实现异常 |
| `login tenant selection required` FAIL | 双租户同 username+password 未返回 `tenantSelectionRequired`：检查 `authenticate(tenantCode=null)` 多匹配逻辑 |
| `auth tenants list contains other` SKIP | 候选按 `password_hash`（含随机盐）匹配，独立创建的账号盐不同；若要求按明文密码匹配需后端调整 |
| `switch tenant` FAIL | `/api/auth/switch-tenant` 未换发 JWT：检查当前账号凭据校验与目标租户同 username 账号 |
| `workflow structure` FAIL | `states<8` 或 `transitions=0`：检查 `TenantLifecycleWorkflowCatalog` 是否由状态机/技能聚合 |
| `v11 api availability` SKIP（404/500） | tenant-service v11 未部署平台审计：确认 `GET /api/platform/audits` 与 `/export` 可用，且网关代理 `/api/platform/audits/**` |
| `v11 api availability` FAIL（admin 403） | 权限拦截未放行平台管理员：检查 `@RequirePermission("audit:read")` 与平台受众校验/种子权限 |
| `platform audits cross tenant` FAIL（缺 tenantCode/tenantName） | 聚合 SQL 未 join tenants：结果需带 `tenantId/tenantCode/tenantName` |
| `filter tenantId/action/keyword` FAIL | 过滤参数未生效或关键字未覆盖 tenantCode/name/detail；检查查询条件与 LIKE 绑定 |
| `sorting at desc` FAIL | 排序不是 `at DESC`：检查 `ORDER BY at DESC` 与分页稳定性 |
| `csv export structure` FAIL | 导出未返回 `text/csv` + `Content-Disposition: attachment`；检查转义与 ≤10000 行限制 |
| `platform audit exported` FAIL | 导出未写审计 `PLATFORM_AUDIT_EXPORTED`（不含明细） |
| `platform audits tenant admin 403`/`auditor 403` FAIL（200） | 平台审计未限制为 PLATFORM_ADMIN：租户管理员/审计员只能走租户级接口 |
