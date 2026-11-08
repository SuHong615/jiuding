# 10 · 验收与测试实测报告（v3）

> 执行时间：2026-11-08（Asia/Shanghai）｜执行者：Lead（集成验证）
> 环境：Windows 10 / JDK 17.0.19 / Maven 3.9.9 / Node 25.2.1 / WSL2 Docker 26.1.5 + Compose 2.27 / k3s v1.30.6

## 1. 结论

| 维度 | 结果 |
|---|---|
| 后端编译（9 模块） | ✅ BUILD SUCCESS |
| 自动化测试 | ✅ **473 个测试全部通过**（0 失败 / 0 错误 / 0 跳过） |
| v1 端到端 E2E | ✅ **16/16 PASS**（`integration/verify.ps1`，k8s） |
| v2 身份域 E2E | ✅ **31/31 PASS**（`tests/e2e-v2.ps1`：组织/账号/评级/软删） |
| v3 租户登录/RBAC E2E | ✅ **42/42 PASS**（`tests/e2e-v3.ps1`：租户账号登录、ORG_ADMIN 子树、MEMBER 只读、auditor 跨租户、锁定） |
| MCP 协议冒烟 | ✅ v1 **8/8** + v2 **7/7**（**22 工具**） |
| 交付验证脚本 | ✅ **17/17 PASS**（`scripts/verify.ps1`：静态 + v1/v2/v3/v5/技能版本/v6/v6B E2E + MCP） |
| **v5 技能/MCP/入驻/RBAC E2E** | ✅ **46/46 PASS**（技能中心、按人 MCP Key+审计、自助入驻、组织/账号导入、角色权限、生命周期） |\n| **v5.1 技能版本/调试 E2E** | ✅ **13/13 PASS**（卡片管理后端：版本列表/旧版本详情/调试命中与未命中/404） |\n| **v6 OIDC/MFA E2E** | ✅ **25/25 PASS**（OIDC 禁用态；MFA setup→登录挑战→/login/mfa→恢复码一次性→禁用→审计） |\n| **v6B SCIM E2E** | ✅ **31/31 PASS**（SCIM Token、Users/Groups CRUD/filter/分页/错误 schema/撤销 401） |
| **v4 账号安全/合规 E2E** | ✅ **20/20 PASS**（`tests/e2e-v4.ps1`：锁定/解锁/改密/登录审计/CSV/掩码/评级 delta/限流） |
| **k8s 生产化** | ✅ 2 副本 + PDB/HPA + CronJob；滚动重启 **0 失败**；CronJob `refreshed=14` |
| 前端生产构建 | ✅ 0 TypeScript 错误；dist 349.7 KB（JS gzip 95.6 KB / CSS gzip 7.8 KB） |
| UI 风格 | ✅ developer-tool design tokens 生效（白底 `#ffffff` / 正文 `#0d0d0d` / 细边框 / 无渐变） |
| CLI | ✅ 59 测试全绿；新增 `auth login --tenant-code` / `auth whoami` |
| Docker Compose | ✅ 7 容器 healthy（v4 镜像），`scripts/verify.ps1` 13/13 PASS |
| Kubernetes（k3s） | ✅ 生产 overlay：6 服务 **2/2** 副本 + PDB/HPA + CronJob ×2；零中断；Flyway 至 tenant v5 / mcp v1 / agent v2 |

**结论：v1（多租户+审批自动化+采集+MCP）、v2（组织/账号/评级/开发者工具极简 UI）、v3（租户账号登录+五档 RBAC+评级周期化）、v4（账号安全/合规导出/评级增量/生产化）全部在 k3s 与 Docker Compose 双环境端到端验证通过。**

## 2. 测试明细（全量 473）

| 模块 | 测试数 | 覆盖重点 |
|---|---|---|
| `agent-kernel` | 2 | AgentScope 2.0.4 ReActAgent 工具调用通路 |
| `tenancy-common` | 18 | JWT（含 oid）/AuthFilter/内部令牌/租户头/失败关闭 |
| `tenant-service` | **166** | v3 86 + v4 16：登录事件审计（IP/UA/原因）、解锁、本人改密、审计 CSV 导出、PII 掩码、评级 delta/等级变化审计、内部 refresh、组织树/评级事务/软删 |
| `approval-service` | 35 | 工作流/自动决策/SLA/权限/下游重试 |
| `agent-service` | **80** | 规则引擎、5 个技能包（RATING 类型）、Agent 运行 |
| `mcp-service` | **45** | 22 工具 schema/调用映射、数组→对象 structuredContent、错误映射、SSE、鉴权 |
| `gateway` | **59** | 登录（平台 + 租户双通道）、登录限流（429/42900）、IP/UA 透传、代理、聚合、`/internal/**` 404 |
| `tenancy-cli` | **59** | 原有命令 + `auth login --tenant-code` / `auth whoami`、org/account/rating 命令 |

复现：`mvn -B -f pom.xml test`。

## 3. 业务链路实测（k8s NodePort 30080）

### 3.1 v1 链路（16/16）
登录 → 建租户 → 提交 → risk-review 评分 → approval-policy 路由 → provisioning → ACTIVE；MCP `tenant.get`；CLI 查询；总览聚合。

### 3.2 v2 身份域链路（31/31）
组织树（公司→部门→子部门，tree/flat 一致）；重复 code 409；有子节点/成员删除 409；移动重算 path/depth + 防环；账号一次性临时密码（无 hash/salt）、密码策略、启停、重置；评级 AAA/91（五维 + SKILL 调整）；软删 ACTIVE 409、DRAFT 成功、默认列表隐藏、`includeDeleted` 可见；脚本幂等（连跑两次均绿）。

### 3.3 v3 租户登录与 RBAC（42/42）

| 场景 | 实测结果 |
|---|---|
| 租户账号登录 | body `{username,password,tenantCode}`；租户 ACTIVE + 账号 ACTIVE；401 统一提示；连续 5 次失败 → LOCKED |
| JWT/Principal | JWT 含 `tid` + `oid`；principal 含 tenantId/orgUnitId/roles/authType=TENANT/mustChangePassword |
| ORG_ADMIN | 子树内建部门/账号成功；子树外建组织/账号 403；授予 TENANT_ADMIN 403；跨租户 403；租户列表仅本租户 |
| MEMBER | 账号列表仅本人（total=1）；组织只读；写/评级读/租户详情 403 |
| AUDITOR | 跨租户租户列表/详情/组织读 200；写/删除 403 |
| 错误口令 | 不区分租户/用户，统一 `invalid credentials` |

### 3.4 MCP（22 工具）
14 基础 + 8 身份域（org.tree/org.create/account.list/account.create/account.reset_password/rating.assess/rating.list/tenant.delete）；数组型结果统一包装为 `{count, items}` 满足 MCP object 约束。

### 3.5 UI（开发者工具极简风格）
构建产物 CSS 含 `#0d0d0d`/`#ffffff`、不含 `radial-gradient`；登录页双模式 + 租户代码；按五档角色收敛写操作；评级趋势 SVG；k8s nginx `GET /` 200、`POST /api/auth/login` 反代成功。

## 4. Kubernetes 部署实测

- 镜像：6 个镜像由 `deploy/k8s/update-images.sh` 构建并导入 containerd，滚动更新 6 个 Deployment；
- 数据：Postgres 存量库 Flyway 自动迁移 **v1→v2**（`flyway_schema_history` 两条 success），新增 `org_units`/`accounts`/`tenant_ratings` 与 `tenants` 评级/软删列；
- 访问：前端 `http://localhost:30088`、网关 `http://localhost:30080`、服务 NodePort 30081–30084；
- 手工验证：`tenant_db.tenants`/`org_units`/`accounts`/`tenant_ratings` 数据随 E2E 增长，Flyway v2 生效。

## 4.6 安全加固实测（独立评审后修复）

| 项 | 修复前 | 修复后（k8s 实测） |
|---|---|---|
| AUDITOR 只读 | 平台 auditor 无 tenantId → 全部 401 | 跨租户读 200，写 403 |
| API-Key 删除租户 | 可删除 DRAFT 租户 | 403（仅 JWT PLATFORM_ADMIN） |
| `/internal/**` 经网关 | 500（泄露内部路径） | 404 且不转发下游 |
| 评级 assess 原子性 | history 已写而 current CAS 冲突 | `@Transactional` 回滚，并发稳定 |
| 软删租户内部接口 | `/rating`、`/accounts/count` 200 | 404 |
| 账号软删残留 manager | 组织仍返回已删账号名 | 软删时置空 `manager_account_id` |

## 4.7 Docker Compose 全栈运行时验证（2026-11-08）

1. `kubectl -n tenancy-center scale deployment --all --replicas=0` 释放内存；
2. `docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --no-build`（本地覆盖发布 gateway 18080 / frontend 8088 / 服务 8081–8084）；
3. 结果：**7 个容器全部 healthy**（v3 镜像）；Postgres 三库 + Flyway v2；依赖健康检查顺序生效；
4. `scripts/verify.ps1 -BaseUrl http://localhost:18080` → **13/13 PASS**（v1/v2/v3 E2E + MCP）；
5. `docker compose down` 后恢复 k8s 副本，7 Pod Running。

> 修复记录：compose 重复 `AGENT_SERVICE_URL`；`eclipse-temurin:17-jre` 自带 curl/wget（去掉多余 apt 层）；nginx 仅监听 IPv4 导致 `localhost` 健康检查误报，改为 `http://127.0.0.1/healthz` 后 7/7 healthy。

## 4.9 v4 账号安全与合规实测（2026-11-08）

| 场景 | 实测结果 |
|---|---|
| 登录锁定 | 连续 5 次错误口令 → 账号 LOCKED，正确口令也被拒绝 |
| 解锁 | 管理员 `POST .../unlock` → ACTIVE、失败计数清零；重复解锁幂等 |
| 本人改密 | `change-password` 需旧密码；旧密码登录 401、新密码登录成功、`mustChangePassword=false` |
| 登录审计 | `login-events` 返回成功/失败事件，IP（来自 `X-Forwarded-For`）与 User-Agent 落库 |
| 审计导出 | `audits/export?format=csv` → `text/csv;charset=UTF-8`，包含 `ACCOUNT_UNLOCKED` / `PASSWORD_CHANGED` / `LOGIN_SUCCESS`，25 行 |
| PII 掩码 | `accounts?mask=true` → `c***@example.com`；AUDITOR 强制掩码 |
| 评级增量 | assess 返回 `scoreDelta`（AA/81/delta=0）；历史含 delta 字段 |
| 登录限流 | 同一 IP+用户名第 11 次 → HTTP 429 / code 42900 |

## 4.10 Kubernetes 生产化实测

- `deploy/k8s/prod/apply-prod.sh`：6 个无状态 Deployment 扩到 **2/2**；PDB `minAvailable=1` 全部 ALLOWED DISRUPTIONS=1；HPA gateway/tenant（CPU 70%，min1/max3）运行（当前 2%/12%）；
- tenant-service 关闭进程内评级定时器，改由 **CronJob `rating-refresh`**（19:00 UTC = 03:00 CST；timeZone 字段与最小化 apiserver tzdata 不兼容已规避）每日触发；
- 手动 Job 实测：`Complete 1/1`，响应 `{"refreshed":14,"skipped":4,"failed":0}`；
- `deploy/k8s/zero-downtime-check.sh 90`：gateway/tenant 滚动重启期间持续探活 **failures=0 → ZERO_DOWNTIME_OK**；
- 生产 rollout 后 `tests/e2e-v4.ps1` 再次 **20/20 PASS**。

## 4.11 v5 技能中心/MCP/入驻/RBAC 实测（2026-11-08）

| 场景 | 实测结果 |
|---|---|
| 技能中心 | 上传 zip → DRAFT → 送审 → APPROVED → enable → 列表可见+热加载；同名上传 409；REJECT 分支通过；模板 zip 1826B |
| Agent 可读性 | `/api/agents/catalog` 4 类；`risk-review` 运行返回 displayName/summary 与中文步骤标题 |
| MCP 凭据 | `POST /api/mcp/keys` 一次性返回 `tk_` 明文；`tools/list` 22 工具、`tools/call` 成功；审计写入 1 条；撤销后 401；外部 MCP 服务器 CRUD 通过 |
| 自助入驻 | 合规资料自动 `ACTIVATED`；非合规 `PENDING_MANUAL` 且审批可见；申请编号状态查询 |
| 导入 | 组织 2 条/账号 2 条 `imported`、重复导入 `skipped`；临时密码登录成功 |
| RBAC | `/api/me/permissions` 平台管理员 `*`；5 内置角色；自定义角色+权限矩阵回读；租户账号访问平台角色接口 403 |
| 生命周期 | `POST /internal/maintenance/purge` 返回 `{loginEvents,audits,collectJobs,tenants}`；无内部令牌 401；CronJob 手动执行成功 |
| 数据库 | tenant_db v5、mcp_db v1、agent_db v2 迁移成功；k3s 生产 2 副本 + PDB/HPA + 2 CronJob；零中断 failures=0 |

## 4.12 v5.1 技能中心 UI/版本/调试实测（2026-11-08）

- 技能中心列表改为**卡片网格分块 + 分页**（每页 9 张），单 Skill 详情为六页签：内容 / 代码 / 审查 / 调试 / 版本 / 启用；前端构建 0 错误，无头浏览器页签与交互测试 14/14 PASS。
- 后端 `GET /api/skills/{id}/versions`、`GET .../versions/{version}`、`POST .../debug` 实测：版本 v1.0.0/v1.1.0 排序与详情正确；debug 命中返回 `decision=APPROVE/AUTO_APPROVE、matchedRules、durationMs`，未命中返回 `matched=false` 条目；未知版本 404/54400。
- E2E：`tests/e2e-skill.ps1` **13/13 PASS**。

## 4.13 v6 阶段 A（OIDC/MFA）实测（2026-11-08）

| 场景 | 实测结果 |
|---|---|
| OIDC 未启用 | `/api/auth/oidc/status` → `enabled=false`；`authorize` 404/40400；`callback` 400/40001 |
| OIDC 启用（测试） | stub IdP 覆盖 discovery/authorize/PKCE/state/token/userinfo/角色映射；gateway 单测 53/53 |
| MFA 绑定 | 租户账号登录 → `/api/me/mfa/setup`（secret+otpauth+8 恢复码）→ verify 启用 |
| MFA 登录 | 密码步返回 `mfaRequired + challengeToken` → `POST /api/auth/login/mfa` 换 JWT；错误码 401 |
| 恢复码 | 一次性消费，二次使用 401 |
| 禁用/审计 | disable 后恢复密码登录；审计 `MFA_ENROLLED/MFA_VERIFIED/MFA_DISABLED` |
| E2E | `tests/e2e-v6.ps1` **25/25 PASS** |

## 4.14 v6 阶段 B（SCIM 2.0）实测（2026-11-08）

| 场景 | 实测结果 |
|---|---|
| SCIM Token | 平台管理员创建（`scim_` 明文仅一次、sha256 存储、prefix 展示）→ 列表 → 撤销；撤销后 SCIM 401 |
| 认证 | 无 token / 坏 token → 401 + SCIM Error schema；`ServiceProviderConfig` 200（patch=true） |
| Users | POST/GET/list/filter(`userName eq`)/GET id/PATCH `active=false`/PUT displayName/分页(startIndex=999 越界)/DELETE(204)/删除后 404 |
| Groups | POST/GET/filter(`displayName eq`)/PATCH（改名+成员）/DELETE（有成员 → 409 + SCIM Error） |
| 错误 schema | 401/404/409 均为 RFC7644 `{schemas,status,detail}` |
| 迁移 | tenant_db Flyway **V7**（scim_tokens）；全量测试 166 |
| E2E | `tests/e2e-v6b.ps1` **31/31 PASS** |

## 5. 架构审查与最佳实践落实（docs/11、docs/13）

| 主题 | 决策 |
|---|---|
| 限界上下文 | 组织/账号/评级归 tenant-service，预留拆分为 identity-service / OIDC+SCIM |
| 身份模型 | 平台 JWT 用户与租户账号目录分离；租户账号 JWT 携带 tid/oid |
| 组织树 | 邻接表 + 物化路径；事务内子树 path 重写；深度≤6；防环 |
| 账号安全 | PBKDF2-HMAC-SHA256 210k + 随机盐；一次性临时密码；5 次失败锁定；响应无 hash |
| 评级 | 五维加权 + Skill 调整；历史 append-only；人工覆盖需 reason；每日定时刷新 |
| 删除 | 软删 + 默认过滤；ACTIVE 先终止；平台管理员专属；全量审计 |
| API | 资源嵌套 REST、统一分页/错误码、幂等状态变更、乐观锁 |
| UI | developer-tool design tokens + 五档角色权限 UI + 可访问性 |

## 6. 环境约束与已知限制

1. Docker 位于 WSL2（Engine 26.1.5 / Compose 2.27），k3s 单节点；Windows 仅能访问 docker publish 端口，k8s 用 `deploy/k8s/publish.sh` 发布 NodePort。
2. 本机 8080/5173 被其他应用占用：进程级用 gateway 18080、前端 15173；k8s 用 30080–30088。
3. 五档角色已全部落地强校验（PLATFORM_ADMIN / TENANT_ADMIN / ORG_ADMIN 子树 / AUDITOR 跨租户只读 / MEMBER 只读本人）；剩余演进：OIDC/SSO、MFA、SCIM。
4. 租户账号为平台内身份目录，未接入企业 IdP；`username` 软删后不可复用。
5. OpenAI 兼容模型通道未实测（默认 scripted）；L2 会签简化为单审批。
6. PowerShell 5.1 客户端解码无 charset 的 JSON 可能显示乱码，服务端 UTF-8 正常。
7. 组织树深度语义：root depth=0、MAX_DEPTH=6（最多 7 层），以 `docs/13` 注释为准。
8. 登录限流为进程内内存态，多副本部署时各副本独立计数（生产水平扩容建议接入 Redis 集中式限流）。
9. CLI 已补齐 v4/v5 命令（解锁/改密/登录历史/审计导出/评级 delta；技能/MCP key/入驻/导入/角色权限）。
10. 自助入驻限流为单实例内存计数（多副本不共享）；自动激活直接置 ACTIVE 并 best-effort 写审批记录。
11. 技能上传包存储于 agent_db（`skill_packages`），运行时热加载；大文件附件（>1MB）不支持。
12. OIDC 默认关闭，需配置 issuer/client-id/client-secret 后启用；PKCE verifier 为 gateway 进程内存储（多副本需共享存储）。
13. SCIM 2.0 已实现（V7，Users/Groups/Token）；增量同步日志与 token 轮换提醒待后续。\n14. 前端 v6 UI（SSO/MFA/SCIM 配置页）已交付；OIDC 需真实 IdP 配置后才能端到端 SSO 登录。

## 7. 复现命令

```powershell
mvn -B -f D:\deepseekHarness\tenancy-center\pom.xml test          # 473 tests
cd D:\deepseekHarness\tenancy-center\frontend; npm run build
# k8s 更新与验收
wsl -d opencode-docker -e /bin/sh /mnt/d/deepseekHarness/tenancy-center/deploy/k8s/update-images.sh
.\integration\verify.ps1 -BaseUrl http://localhost:30080 -TenantUrl http://localhost:30081 -ApprovalUrl http://localhost:30082 -AgentUrl http://localhost:30083 -McpUrl http://localhost:30084
.\tests\e2e-v2.ps1 -BaseUrl http://localhost:30080
.\tests\e2e-v3.ps1 -BaseUrl http://localhost:30080
.\tests\e2e-v4.ps1 -BaseUrl http://localhost:30080
.\scripts\verify.ps1 -BaseUrl http://localhost:30080
# 生产化 overlay
sh deploy/k8s/prod/apply-prod.sh
sh deploy/k8s/zero-downtime-check.sh 90
```

## 8. 交付规模（v5.1 + v6A/B）

- 后端 Java：330+ 文件；前端 60+ TS/TSX 文件
- 技能：5 个内置 + 上传/审核/版本/调试/启停；MCP 工具 22 个；RBAC 43 权限/5 内置角色
- 安全：OIDC/SSO（可配置 IdP）、MFA（TOTP+恢复码）、SCIM 2.0 同步、账号锁定/改密/登录审计/限流
- 文档：17 份（新增 docs/16 v6 SSO/MFA/SCIM）
- 测试：473 自动化测试 + 31 v2 E2E + 42 v3 E2E + 20 v4 E2E + 46 v5 E2E + 13 技能版本 E2E + 25 v6 E2E + 31 v6B SCIM E2E + 15 MCP 断言
- 生产化：2 副本 + PDB ×6 + HPA ×2（min2/max3）+ CronJob ×2；零中断验证通过
- UI 截图：`docs/assets/ui-login-v3.png`、`ui-tenants.png`、`ui-org.png`
