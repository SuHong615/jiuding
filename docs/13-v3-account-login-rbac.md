# 13 · v3：租户账号登录、细粒度 RBAC、评级周期化

> 状态：**已完成并验收**（2026-11-08）｜Owner：Lead｜关联：docs/11、docs/12、docs/02
> 验收：303 自动化测试全绿；v3 E2E 42/42 PASS；`scripts/verify.ps1` 13/13 PASS；k3s 与 Docker Compose 双环境实测。

## 1. 目标

v2 已交付组织/账号/评级管理，但租户账号只是"身份目录"，无法登录；ORG_ADMIN/MEMBER 的子树/本人权限未生效。v3 补齐 RBAC 闭环：

| # | 目标 | 验收结果 |
|---|---|---|
| G1 | **租户账号可登录**：平台用户 + 租户账号双通道；JWT 携带 tenantId/orgUnitId | ✅ E2E 实测 |
| G2 | **细粒度 RBAC**：ORG_ADMIN 子树、MEMBER 只读本人/本组织、AUDITOR 跨租户只读 | ✅ 角色矩阵 42 项断言 |
| G3 | **评级周期化**：每日定时复核 ACTIVE 租户并写历史 | ✅ 调度方法测试 |
| G4 | **docker compose 全栈运行时验证** | ✅ 7 容器 healthy + 13/13 PASS |

## 2. 认证设计

### 2.1 登录请求
```http
POST /api/auth/login
{ "username": "alice", "password": "...", "tenantCode": "acme" }   // tenantCode 可选
```

解析顺序：
1. `tenantCode` 为空 → 先匹配平台预置用户（admin/approver/operator/auditor）；
2. 平台用户不匹配（或显式给了 tenantCode）→ 调 tenant-service 内部认证。

### 2.2 tenant-service 内部认证
```http
POST /internal/auth/authenticate          # X-Internal-Token 保护
{ "tenantCode": "acme", "username": "alice", "password": "..." }
→ 200 { accountId, tenantId, tenantCode, username, displayName,
        role, orgUnitId, orgUnitPath, mustChangePassword }
→ 401 { errorCode: 40100, message: "invalid credentials" }   # 不区分用户名/密码/租户错误
```
规则：
- 租户必须存在、未软删且 `status=ACTIVE`；账号必须 `status=ACTIVE`；
- PBKDF2-HMAC-SHA256(210k) 常量时间校验；
- 失败累计 `failed_attempts`，≥5 置 `LOCKED` 并审计 `ACCOUNT_LOCKED`；成功清零并更新 `last_login_at`；
- 成功/失败均写审计（不记录密码）；响应不含 hash/salt。

### 2.3 JWT 与 Principal
- 新增 claim `oid`（organization unit id）；
- `Principal` 增加 `orgUnitId`（5 参构造保持兼容）；`TenantScope` 增加 `orgUnitId`；`AuthFilter` 注入；
- 登录响应 principal：`{userId, username, tenantId, roles, orgUnitId, mustChangePassword, authType}`；
- 角色映射：平台 `PLATFORM_ADMIN/APPROVER/AUDITOR`；租户账号 `TENANT_ADMIN/ORG_ADMIN/MEMBER/AUDITOR`。

## 3. RBAC 矩阵（tenant-service 强校验）

| 操作 | PLATFORM_ADMIN | TENANT_ADMIN | ORG_ADMIN（本子树） | AUDITOR | MEMBER |
|---|---|---|---|---|---|
| 租户列表/详情 | ✅ 全部 | ✅ 本租户 | ✅ 本租户(只读) | ✅ 跨租户只读 | ❌ |
| 租户编辑/生命周期 | ✅ | ✅ 本租户 | ❌ | ❌ | ❌ |
| 租户删除 | ✅ 非 ACTIVE | ❌ | ❌ | ❌ | ❌ |
| 组织树读 | ✅ | ✅ | ✅ 子树 | ✅ 只读 | ✅ 本组织子树 |
| 组织增/改/移/删 | ✅ | ✅ | ✅ 子树内（不可建根、不可移出） | ❌ | ❌ |
| 账号列表 | ✅ | ✅ 本租户 | ✅ 子树内 | ✅ 只读 | ✅ 仅本人 |
| 账号增/改/启停/重置/删 | ✅ | ✅ | ✅ 子树内；仅可授 MEMBER/AUDITOR | ❌ | ❌ |
| 评级读 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 评级评估 | ✅ | ✅ | ❌ | ❌ | ❌ |
| 评级覆盖 | ✅ | ❌ | ❌ | ❌ | ❌ |

实现要点：
- `SecurityScope` 基于 `TenantScope.roles` + `orgUnitId` 解析；ORG_ADMIN/MEMBER 的组织范围用 `org_units.path` 前缀判定；
- 越权统一 403（不泄露存在性）；写路径统一 `requireWrite`；删除租户仍要求 JWT PLATFORM_ADMIN。

## 4. 评级周期化

- 配置：`tenancy.rating.auto-refresh-enabled`（默认 true）、`tenancy.rating.refresh-cron`（默认 `0 0 3 * * *`）；
- `@Scheduled` 调用 `RatingRefreshService.refreshActive(limit=200)`：对 ACTIVE 且未软删租户逐个 `assess(actor=system)`，单租户失败记 WARN 不中断；
- 历史 append-only；审计 action=`RATING_REFRESHED`；
- 测试直接调用 refresh 方法（不等待 cron），断言只处理 ACTIVE、失败隔离。

## 5. 前端

1. 登录页：新增可选「租户代码」；平台账号/租户成员双模式；登录失败统一提示；
2. 权限感知：按角色隐藏/禁用操作（新建/编辑/删除/评估/覆盖）；MEMBER 只读且账号页仅本人；
3. `mustChangePassword=true` 时顶部提示条；顶栏展示角色/租户/组织；
4. 评级页新增最近 10 次评分 SVG 趋势图 + 五维对比；
5. `api/types.ts` Principal 增加 orgUnitId/mustChangePassword/authType。

## 6. CLI

- `tenancy auth login --tenant-code <code>`（写入配置）；
- `tenancy auth whoami` 显示 username/tenantId/role/orgUnitId（从 token 解析）；
- 退出码：认证失败 3、下游不可达 5；其余命令不变。

## 7. compose 全栈运行时验证

1. `kubectl -n tenancy-center scale deployment --all --replicas=0`（释放 ~2.4 GiB）；
2. `cd deploy && docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --no-build`（使用已构建的 `tenancy/*:1.0.0` 镜像）；
3. `scripts/verify.ps1 -BaseUrl http://localhost:18080` → **13/13 PASS**；
4. `docker compose down` 并恢复 k8s 副本。

## 8. 验收结果（2026-11-08）

- `mvn -B -f pom.xml test` → **303 tests** 全绿（tenant 86 / gateway 26 / cli 59）；
- `tests/e2e-v3.ps1` → **42/42 PASS**（租户登录、ORG_ADMIN 子树/越权 403、MEMBER 只读、auditor 跨租户、错误口令统一 401、5 次失败锁定）；
- `scripts/verify.ps1` → **13/13 PASS**（v1/v2/v3 E2E + MCP）；
- k3s 7 Pod Running；Compose 7 容器 healthy；
- 前端 `npm run build` 0 错误；截图见 `docs/assets/ui-login-v3.png`。
