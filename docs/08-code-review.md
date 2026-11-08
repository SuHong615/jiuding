# 08 · 代码评审手册（Checklist）

> 本手册是多租户中心平台的**评审门禁**：任何合并/验收前，评审人按本清单逐项核对并记录结论。评审对象为 8 个 Maven 模块 + 前端 + 部署脚本；契约以 `docs/02`、`docs/03`、`docs/04`、`docs/05` 为准，架构约束以 `docs/01`、`docs/00` 为准。测试执行见 `docs/07`。

---

## 1. 评审流程与权限边界

1. **自检**：作者在提测前跑通 `mvn -f <module>/pom.xml test` 与相关 E2E，并在评审说明中贴出命令与结果。
2. **范围核对**：只允许修改自己 scope 的文件（`docs/00` 写权限表）；跨模块接口变更必须先改 `docs/02` 并在任务板记录，禁止"顺手改契约"。
3. **双人评审**：涉及认证、租户隔离、SQL、降级、MCP 认证的改动必须由 Lead 或 architect 二次评审。
4. **结论**：每项标记 `PASS` / `FAIL` / `N/A`；任一 `阻断` 项 `FAIL` 不得合并。评审记录写入 PR 描述或任务板。
5. **修复复验**：`FAIL` 项修复后由同一评审人复验，不允许作者自行关闭。

**严重级别**

| 级别 | 含义 | 处理 |
|---|---|---|
| 阻断（Blocker） | 数据泄漏、认证绕过、5xx 未降级、契约破坏 | 必须修复后才能合并 |
| 严重（Major） | 错误码不一致、超时缺失、幂等缺失、测试缺失 | 本次迭代内修复 |
| 建议（Minor） | 命名、日志、注释、可读性 | 可排期 |

---

## 2. 通用清单（所有模块）

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-GEN-01 | 构建 | `mvn -B -DskipTests install` 成功；无新增未声明依赖 | 阻断 |
| CR-GEN-02 | 技术栈约束 | Java 17 语法；**无 Lombok**；Spring Boot 版本未擅自升级/降级 | 阻断 |
| CR-GEN-03 | 依赖注入 | 构造器注入；无字段 `@Autowired` | 建议 |
| CR-GEN-04 | DTO | 使用 `record`；校验注解（`@NotBlank`/`@Email`）与契约一致 | 严重 |
| CR-GEN-05 | 统一响应 | Controller 返回 `ApiResponse`；异常抛 `BizException(ErrorCode, msg)`，不手写 JSON 错误 | 阻断 |
| CR-GEN-06 | 配置 | `application.yml` 只有默认值 + env 占位；`dev`/`docker` 两个 profile 都能起 | 严重 |
| CR-GEN-07 | 时间 | 存 UTC；对外 ISO-8601；不混用 `Date`/`LocalDateTime` 语义 | 建议 |
| CR-GEN-08 | 日志 | 关键写操作有 INFO 日志；异常记录 `requestId`；不打印密码/token/密钥 | 严重 |
| CR-GEN-09 | 资源 | 线程池/连接/流有界且关闭；无 `new Thread` 裸用 | 严重 |
| CR-GEN-10 | 测试 | 每模块 ≥4 测试类（agent-service ≥5）；新增分支有对应用例 | 严重 |

---

## 3. 多租户隔离专项（最高优先级）

> 原则：**fail-closed**。缺租户上下文 = 拒绝，绝不"查全部"。

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-TEN-01 | 上下文入口 | 所有租户数据方法入口调用 `TenantContext.require()`；不存在"先查再判断"的写法 | 阻断 |
| CR-TEN-02 | SQL 条件 | 所有 `SELECT/UPDATE/DELETE` 的 `WHERE` 显式含 `tenant_id = ?`；无 `WHERE 1=1` 兜底 | 阻断 |
| CR-TEN-03 | 平台管理员分支 | 跨租户查询收敛到命名 `*ForPlatform` 的方法，且在 Controller 层做角色判断；分支可枚举、可审计 | 阻断 |
| CR-TEN-04 | 租户头校验 | `X-Tenant-Id` 与 JWT `tid` 不一致且非 `PLATFORM_ADMIN`/`SYS_ADMIN` 必须 403；不得忽略 | 阻断 |
| CR-TEN-05 | 路径参数越权 | `GET/PUT /api/tenants/{id}` 在返回前校验目标租户归属；不能仅靠前端过滤 | 阻断 |
| CR-TEN-06 | 采集隔离 | 批量导入/CSV 的每条记录在落库前绑定当前租户或显式平台管理员权限 | 阻断 |
| CR-TEN-07 | 内部接口 | `/internal/**` 校验 `X-Internal-Token`；网关不路由该前缀；不得暴露为公开 API | 阻断 |
| CR-TEN-08 | 隔离测试 | 至少 1 个"租户 A 读不到租户 B"的自动化用例（TC-COM-08/10、TC-TEN-17） | 严重 |
| CR-TEN-09 | 审计 | 所有租户写操作写 `tenant_audits`；`actor` 来自 Principal，不取请求体 | 严重 |
| CR-TEN-10 | 异常信息 | 跨租户/不存在资源返回一致错误，不泄漏"资源存在" | 建议 |

**反例（直接 FAIL）**

```java
// 反例 1：缺 tenant 条件
jdbc.query("SELECT * FROM tenants WHERE status = ?", ...);

// 反例 2：先查后判断归属
TenantRow row = findById(id);
if (!row.tenantId().equals(TenantContext.get().tenantId())) throw ...; // 已读全量，且 get() 可能为 null

// 反例 3：把请求体 actor 写审计
audit.setActor(request.actor()); // 应取 Principal.username()

// 反例 4：平台管理员分支散落各处
if (principal.hasRole("PLATFORM_ADMIN")) { /* 任意 SQL */ }
```

---

## 4. 认证与授权专项（防绕过）

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-AUTH-01 | 白名单最小化 | 仅 `/actuator/health`、`/actuator/info`、`/api/auth/login`、`/error`、`/v3/api-docs/**`、`/swagger-ui/**`、`/favicon.ico`；新增白名单必须有理由 | 阻断 |
| CR-AUTH-02 | 网关白名单 | 网关自身只放行 `/api/auth/login`、`/actuator/health`；其余一律验 JWT | 阻断 |
| CR-AUTH-03 | JWT 校验 | 校验签名（`MessageDigest.isEqual` 常数时间比较）+ `exp`；算法固定 HS256，不信任 header `alg` | 阻断 |
| CR-AUTH-04 | 密钥管理 | `JWT_SECRET` 来自 env；默认值只允许 dev；启动日志对默认值 WARN | 阻断 |
| CR-AUTH-05 | 密码处理 | 仅内存比较；不打印、不落库、不进日志；`TENANCY_USERS` 覆盖时同样规则 | 严重 |
| CR-AUTH-06 | API Key | 仅 env 注入；不在代码/配置样例里放真实 key；比较使用常数时间或集合包含（原型可接受，生产改哈希） | 严重 |
| CR-AUTH-07 | 角色校验 | 审批决策仅 `APPROVER`/`PLATFORM_ADMIN`；驳回必须有 comment；越权 403 | 阻断 |
| CR-AUTH-08 | 请求头信任 | `X-User-Id`/`X-Roles` 仅由网关注入，业务服务不直接信任客户端传入 | 阻断 |
| CR-AUTH-09 | MCP 认证 | `/mcp` 校验 `X-API-Key` 或 Bearer；`/mcp/info` 可公开但不得含密钥 | 阻断 |
| CR-AUTH-10 | 资源级授权 | 审批单/租户/采集任务按归属校验；不能"知道 id 就能操作" | 阻断 |
| CR-AUTH-11 | CORS | 生产不得 `allowedOriginPatterns=*` + `allowCredentials=true`；原型需在文档标注风险 | 严重 |
| CR-AUTH-12 | 安全测试 | 无 token 401、错 token 401、跨租户 403、越权决策 403 均有用例 | 严重 |

**常见绕过手法（评审时必须主动验证）**

1. 大小写/前后空格路径绕过：`/API/TENANTS`、`/actuator/health/..;/api/...`。
2. 双重编码/`;` 路径参数污染（Tomcat 默认拒绝，但需确认无自定义路由）。
3. `X-Tenant-Id` 空字符串：`AuthFilter` 视为"未提供"，应回落到 token tenantId 而不是放行。
4. 内部接口经网关暴露：检查网关路由表**不存在** `/internal/**`。
5. MCP `tools/call` 透传时把 API Key 换成下游内部 token 造成的权限放大。

---

## 5. SQL 与数据访问专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-SQL-01 | 参数化 | 全部使用 `JdbcTemplate` 参数绑定（`?` 或 `NamedParameterJdbcTemplate`）；禁止字符串拼接 SQL | 阻断 |
| CR-SQL-02 | 排序/列名 | 动态排序字段用白名单映射，禁止直接拼 `ORDER BY ${}` | 阻断 |
| CR-SQL-03 | LIKE | 用户输入经转义/参数绑定；通配符行为在文档说明 | 严重 |
| CR-SQL-04 | 迁移兼容 | Flyway SQL 为 ANSI 子集；无 `jsonb`/`serial`/`identity`/`ON CONFLICT` 等方言；H2 与 Postgres 都能执行 | 阻断 |
| CR-SQL-05 | 主键/时间 | `VARCHAR(36)` UUID；`TIMESTAMP`；不使用自增序列 | 严重 |
| CR-SQL-06 | 唯一约束 | `tenant_code` 唯一约束存在；重复插入映射为 `CONFLICT(40900)` 而非 500 | 严重 |
| CR-SQL-07 | 乐观锁 | `tenants`/`approvals` 的 `version` 在更新时校验；冲突返回 409 | 严重 |
| CR-SQL-08 | 事务 | 状态迁移 + 审计/节点写入同一事务；`@Transactional` 加在正确层级 | 严重 |
| CR-SQL-09 | 大字段 | `attributes_json`/`errors_json`/`steps_json` 有长度上限与截断策略 | 建议 |
| CR-SQL-10 | 索引 | 查询条件（`status`、`tenant_code`、`agent_type`）有索引；不重复建索引 | 建议 |

**反例**

```java
// FAIL：拼接 SQL
String sql = "SELECT * FROM tenants WHERE name LIKE '%" + keyword + "%'";
// PASS：参数绑定 + 租户条件
jdbc.query("SELECT * FROM tenants WHERE tenant_id = ? AND name LIKE ?",
        mapper, tenantId, "%" + escapeLike(keyword) + "%");
```

---

## 6. 超时、降级与韧性专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-RES-01 | 超时设置 | 所有跨服务调用显式超时：普通 10s、`skill.run` 透传 30s、MCP 10s；异常捕获 | 阻断 |
| CR-RES-02 | 降级路径 | approval-service 不可达 → 提交保持 `SUBMITTED` + 审计；agent-service 不可达 → 本地规则兜底；下游失败不 5xx | 阻断 |
| CR-RES-03 | 降级可观测 | 降级写审计/timeline/WARN 日志，包含原因与目标服务 | 严重 |
| CR-RES-04 | 幂等 | `internal status` 同值重复调用幂等；`escalate` 以 `escalated` 幂等；采集重复导入全部 `duplicated` | 阻断 |
| CR-RES-05 | 重试边界 | 只对可重试错误重试；重试有次数/退避上限；不开无界循环 | 严重 |
| CR-RES-06 | 状态悬挂 | 审批 `PROVISIONING` 失败可重试；不因下游失败丢状态 | 严重 |
| CR-RES-07 | 定时任务 | `@Scheduled(30s)` 扫描有界、单次失败不影响后续；多实例部署需说明（原型单实例） | 严重 |
| CR-RES-08 | 线程池 | 采集线程池有界队列 + 拒绝策略；不阻塞 HTTP 线程 | 严重 |
| CR-RES-09 | 网关聚合 | 单服务 DOWN 不影响 `/api/platform/health` 整体 200；延迟/超时可控 | 严重 |
| CR-RES-10 | SSE 透传 | 逐块转发不缓冲；异常时正确关闭连接；`X-Accel-Buffering: no` | 严重 |
| CR-RES-11 | 降级测试 | TC-TEN-07、TC-APP-05/13、E2E-08/09 有自动化覆盖 | 严重 |
| CR-RES-12 | 超时/降级文档 | README 或 `docs/06` 排障表可查 | 建议 |

**反例**

```java
// FAIL：把下游异常直接抛出，导致主链路 5xx
restClient.post().uri(approvalUrl).body(req).retrieve().body(ApprovalView.class);
// PASS：捕获并降级
try {
    approvalClient.create(req);
    tenant.status(RISK_REVIEW);
} catch (Exception e) {
    log.warn("approval-service unavailable, degrade to SUBMITTED: {}", e.toString());
    audit(tenantId, "SUBMIT_DEGRADED");
}
```

---

## 7. 错误码与契约一致性专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-ERR-01 | 枚举完整 | 只使用 `tenancy-common` 的 `ErrorCode`；不新增魔法数字 | 阻断 |
| CR-ERR-02 | HTTP 映射 | `UNAUTHORIZED→401`、`FORBIDDEN/TENANT_SUSPENDED→403`、`NOT_FOUND→404`、`CONFLICT→409`、`QUOTA_EXCEEDED→429`、`UPSTREAM_ERROR→502`、其余业务→400、未预期→500 | 严重 |
| CR-ERR-03 | 响应结构 | 成功/失败均为 `ApiResponse{code,message,data,traceId,timestamp}`；`data` 失败时为 null | 阻断 |
| CR-ERR-04 | 错误信息 | 面向用户可读、不泄漏堆栈/SQL/内部路径 | 严重 |
| CR-ERR-05 | MCP 映射 | 协议错误 `-32600/-32601/-32602/-32603`；业务错误 `isError:true + structuredContent.errorCode`；不抛 500 | 阻断 |
| CR-ERR-06 | CLI 退出码 | `0` 成功 / `2` 参数 / `3` 认证 / `4` 业务 / `5` 网络，映射稳定 | 严重 |
| CR-ERR-07 | 字段一致 | DTO 字段与 `docs/02` 完全一致（新增字段需同步契约） | 阻断 |
| CR-ERR-08 | 状态枚举 | 状态机枚举值、流转规则与 `docs/02`/`docs/03` 一致；非法流转用 `APPROVAL_INVALID_STATE(54000)` | 阻断 |
| CR-ERR-09 | 400 vs 500 | 参数/状态错误不得落到 500；`GlobalExceptionHandler` 兜底 | 严重 |
| CR-ERR-10 | traceId | 响应头/响应体回显 `X-Request-Id`，缺失时生成 | 建议 |

**错误码速查（与 `docs/01` §8.3 一致）**

| 场景 | code | HTTP |
|---|---|---|
| 参数校验失败 / CSV 格式错 | 40001 | 400 |
| 凭证缺失/无效/租户上下文缺失 | 40100 | 401 |
| 权限不足/跨租户 | 40300 | 403 |
| 租户已停用 | 40301 | 403 |
| 资源不存在 | 40400 | 404 |
| 唯一冲突/重复提交 | 40900 | 409 |
| 配额超限 | 42900 | 429 |
| 状态机非法流转 | 54000 | 400 |
| 技能不存在 | 54400 | 404 |
| Agent 失败 | 53000 | 500 |
| 下游不可达且不可降级 | 50200 | 502 |
| 未预期异常 | 50000 | 500 |

---

## 8. Agent / Skill 专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-AGT-01 | 封装边界 | 业务模块不 import `io.agentscope.*`；AgentScope 只出现在 `agent-kernel` | 严重 |
| CR-AGT-02 | 确定性 | 默认 `ScriptedModel` 可离线运行；业务判定由规则引擎产出，不由脚本硬编码 | 阻断 |
| CR-AGT-03 | 双通道 | `provider=openai` 时使用 env 配置；未配置时默认 scripted；切换不改业务代码 | 严重 |
| CR-AGT-04 | 工具安全 | 工具入参 schema 固定；租户输入不拼进 System Prompt；JSON schema 校验输出 | 阻断 |
| CR-AGT-05 | 规则引擎 | 无条件组合/运算符覆盖单测；单规则异常不中断（`verdict=WARN`）；`matches` 正则长度 ≤256 | 严重 |
| CR-AGT-06 | 动作枚举 | 只允许 7 个动作：`addScore/addFinding/setDecision/setRiskLevel/requireRole/setSlaHours/addTag`；未知动作告警 | 严重 |
| CR-AGT-07 | Skill 加载 | 只读加载 `TENANCY_SKILLS_DIR`；路径穿越防护；frontmatter 必填校验；热加载失败不崩溃 | 阻断 |
| CR-AGT-08 | 持久化 | `agent_runs` 记录 model/tokens/duration/steps；`rule_hits` 记录 skill/rule/matched | 严重 |
| CR-AGT-09 | 超时/步数 | ReAct `maxIters` 有上限（默认 6）；运行超时终止并落状态 | 严重 |
| CR-AGT-10 | 注入防护 | Skill 只读；无反射/脚本执行；Agent 输出经 schema 校验后才写业务状态 | 阻断 |
| CR-AGT-11 | 序列化 | 规则引擎/Skill 元数据用 Jackson（不引 snakeyaml 之外的解析器）；YAML 解析异常有兜底 | 严重 |

---

## 9. 前端专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-FE-01 | Token 存储 | `localStorage` 存 token；401 统一跳 `/login` 并清 token | 严重 |
| CR-FE-02 | XSS | 不使用 `dangerouslySetInnerHTML`；渲染后端字符串走 React 转义 | 阻断 |
| CR-FE-03 | API 客户端 | 统一 fetch 封装：拆 `ApiResponse`、统一错误提示、带 `Authorization`/`X-Request-Id` | 严重 |
| CR-FE-04 | 三态 | 每页 loading/empty/error 可见；错误可重试 | 严重 |
| CR-FE-05 | 构建 | `npm run build` 无 TS 错误；无重型 UI 库（antd/element 等） | 阻断 |
| CR-FE-06 | 路由守卫 | 未登录访问受保护路由跳登录；登录后回跳 | 严重 |
| CR-FE-07 | 敏感信息 | 不在前端硬编码密钥/内部地址；不展示 JWT 原文 | 严重 |
| CR-FE-08 | nginx | `nginx.conf` 有 SPA fallback 与 `/api`、`/mcp` 反代；SSE 关闭缓冲 | 严重 |

---

## 10. 部署与脚本专项

| ID | 检查项 | 通过标准 | 级别 |
|---|---|---|---|
| CR-OPS-01 | compose 语法 | YAML 可解析；7 容器、端口 8080/8088、`depends_on: condition: service_healthy`、网络/卷齐全 | 阻断 |
| CR-OPS-02 | Dockerfile | 多阶段构建；非 root；`HEALTHCHECK` 命令在镜像内存在（JRE 镜像需装 curl 或改用可用命令） | 阻断 |
| CR-OPS-03 | 初始化 | `deploy/postgres/init/01-databases.sql` 创建三库；挂载路径正确 | 严重 |
| CR-OPS-04 | 密钥 | `.env.example` 只含占位符；真实 `.env` 不入库；默认密钥有 WARN | 阻断 |
| CR-OPS-05 | 脚本健壮性 | `.ps1` 语法检查通过；幂等（可重复执行）；UTF-8 无 BOM；失败非 0 退出 | 严重 |
| CR-OPS-06 | 本机事实 | 文档明确"本机无 Docker"，compose 命令只作交付说明，不进入本地强制构建 | 严重 |
| CR-OPS-07 | 端口纪律 | 不占用 3183；端口冲突有检测/提示 | 严重 |
| CR-OPS-08 | 数据清理 | H2 `data/`、`.run/` 在忽略清单；不提交数据库文件与日志 | 建议 |

---

## 11. 评审记录模板

```text
模块/PR：
评审人：            日期：
范围核对（scope 内？）：PASS / FAIL
G1 构建：           PASS / FAIL（命令与结果）
G2 单测：           PASS / FAIL（Tests run / Failures / Errors）
G3 契约测试：        PASS / FAIL
阻断项：CR-TEN-__ ✅/❌、CR-AUTH-__、CR-SQL-__、CR-RES-__、CR-ERR-__
严重项：……
建议项：……
结论：通过 / 修复后通过 / 拒绝（理由）
复验人：            日期：
```

**签字条件**：§3（多租户）、§4（认证）、§5（SQL）、§6（降级）、§7（错误码）五组清单**全部 PASS**，且 G1～G3 通过，方可进入 E2E 验收（`docs/07` §4）。

---

## 12. 高频缺陷 Top 10（历史经验，评审重点）

1. 新增查询忘记 `tenant_id` 条件（最常见，直接 P0）。
2. 平台管理员分支写成"所有角色都能跨租户"。
3. 跨服务调用没有 try/catch，下游挂了主链路 500。
4. 状态机用 `if/else` 散落各处，非法流转未拦截。
5. 错误码不一致（业务 400 变成 500，或直接返回 `INTERNAL`）。
6. Flyway SQL 用了 `ON CONFLICT`/`jsonb`，H2 起不来。
7. CSV 解析未限制大小/行数，内存打爆。
8. MCP 下游失败直接抛 500，没有 `isError:true`。
9. 前端 401 未统一跳转，页面卡死。
10. 脚本/配置与本机事实不符（假设 Docker 存在、写死绝对路径）。
