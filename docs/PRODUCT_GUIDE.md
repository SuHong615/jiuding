# 九鼎 · 多租户智能中枢 — 产品指引

> **产品名**：九鼎 · 多租户智能中枢（Jiuding · Multi-Tenant Intelligence Hub）
> **工程名**：`jiuding` ｜ **版本**：0.9.0（预发布 / Reference Implementation）
> **许可证**：Apache License 2.0 ｜ **作者**：suhong

---

## 0. 先读这一段：产品定位与「非生产级」声明

**九鼎不是开箱即用的生产级 SaaS。** 它是一套可运行、可验证、可二次开发的**多租户中心参考实现**，用于：

- 学习/验证「租户全生命周期 + 审批自动化 + Agent/Skill 规则 + MCP 开放接口 + 多租户 RBAC」的完整工程闭环；
- 作为企业内部多租户平台的技术底座原型，在此基础上做产品化加固；
- 演示 Agentic 工作流、Skill 规则引擎、MCP 工具生态与运营控制台的协作方式。

**已知产品设计缺口（0.9.0 诚实清单）**：

| 类别 | 现状 | 生产化需要 |
|---|---|---|
| 多租户数据隔离 | 共享 schema + 应用层 fail-closed 过滤 | 数据库 RLS / 独立 schema / 每租户加密密钥 |
| 身份 | 本地账号 + OIDC/SSO + MFA + SCIM | 多 IdP、企业目录深度映射、会话/令牌撤销 |
| 权限 | 五档 RBAC + 可配置角色权限矩阵（端点仍以角色校验为主） | 端点级权限码强制、ABAC、审批流 |
| 工作流 | 内置审批引擎（单级/简化 L2 会签） | 可编排 BPMN、并行分支、补偿事务 |
| 计费/配额 | 字段级配额记录 | 实时计量、账单、配额强约束 |
| 数据生命周期 | 定时清理 + purge | 归档、跨区域复制、备份恢复演练 |
| 高可用 | k3s 2 副本 + PDB/HPA | 多区域、故障演练、容量模型 |
| 可观测性 | 仅健康检查（明确暂缓） | Metrics/Tracing/日志平台/告警 |
| 测试 | 473 自动化测试 + 6 套 E2E | 性能/安全/混沌测试、覆盖率门禁 |

> 结论：**适合演示、评估、二次开发起点；不建议直接承载真实生产租户与敏感数据。**

---

## 1. 产品是什么

九鼎把「租户」当作一等公民，围绕租户提供：

1. **租户全生命周期**：申请入驻 → 采集/资料 → 风险审查 → 审批 → 开通 → 运营 → 停用/软删；
2. **组织与账号**：多级组织树、账号目录、导入/导出、账号安全（锁定/改密/MFA/登录审计）；
3. **评级体系**：五维评分 + 规则 Skill 调整 + 历史趋势 + 周期刷新；
4. **审批自动化**：规则决策 → 自动通过 / 人工节点 / SLA 升级 / 审计时间线；
5. **Skill 中心**：技能的编辑、审核、版本、调试、启用与热加载；
6. **Agent 运行时**：基于 AgentScope 的 ReActAgent（风险审查/准入/策略/开通），运行轨迹可读；
7. **MCP 开放接口**：22 个工具 + 资源/提示词；按人 API Key、调用审计、外部 MCP 服务器配置；
8. **运营控制台**：开发者工具极简风格（Codex-inspired）前端（租户/组织/账号/评级/审批/技能/Agent/MCP/权限/入驻/SCIM）；
9. **CLI 与自动化**：`jiuding`（兼容 `tenancy` 别名）命令行覆盖主要运维动作；
10. **企业身份**：OIDC/SSO、MFA（TOTP+恢复码）、SCIM 2.0 用户/组织同步。

## 2. 目标用户与典型场景

| 角色 | 场景 |
|---|---|
| 平台运营方 | 管理全部租户、审批、评级、角色权限、审计 |
| 租户管理员 | 管理本租户组织/账号/评级，配置 MFA/SCIM |
| 组织管理员 | 在授权子树内管理组织与成员 |
| 审计员 | 跨租户只读、审计导出、登录历史 |
| 成员 | 只读本人/本组织信息 |
| 集成方 | 通过 MCP / SCIM / REST 接入 |

## 3. 能力地图

```
登录/身份 ── 平台账号｜租户账号｜OIDC/SSO｜MFA｜SCIM
租户中心 ── 列表/详情/生命周期/评级/软删/自助入驻
身份域   ── 组织树（物化路径/防环/深度）｜账号目录｜导入｜账号安全
审批域   ── 工作流/规则自动决策/人工审批/SLA/审计时间线
Agent域  ── AgentScope 运行时/4 类 Agent/Skill 规则引擎/运行轨迹
技能中心 ── 上传/内容/代码/审查/调试/版本/启用（卡片 + 六页签）
MCP域    ── JSON-RPC/SSE/22 工具/按人 Key/审计/外部服务器
平台域   ── 权限角色/审计导出/PII 掩码/数据生命周期
交付     ── Docker Compose｜k3s（生产 overlay）｜CLI｜E2E 脚本
```

## 4. 快速体验

### 4.1 Docker Compose（最短路径）
```bash
cd deploy
cp .env.example .env
docker compose -f docker-compose.yml -f docker-compose.local.yml up -d --build
# 前端 http://localhost:8088 · 网关 http://localhost:8080
```

### 4.2 Kubernetes（k3s，已验证）
```sh
sh deploy/k8s/deploy.sh
sh deploy/k8s/publish.sh start
# 前端 http://localhost:30088 · 网关 http://localhost:30080
```

### 4.3 本地进程（开发调试）
```powershell
mvn -B -DskipTests install
pwsh scripts/run-local.ps1
cd frontend; npm install; npm run dev
```

### 4.4 演示账号（**仅本地**）
| 用户 | 密码 | 角色 |
|---|---|---|
| admin | Admin@123 | PLATFORM_ADMIN |
| approver | Approver@123 | APPROVER |
| operator | Operator@123 | TENANT_ADMIN（t-acme） |
| auditor | Auditor@123 | AUDITOR |

> 生产/公网部署前必须删除或强制改密，并替换所有默认密钥（见 `SECURITY.md`）。

## 5. 关键流程

### 5.1 租户自助入驻
登录页「申请入驻」→ 填企业信息 → 后台 Agent 风险审查：
- 合规章（LOW 且非 ENTERPRISE）→ 自动开通（ACTIVATED）；
- 不合规 → 生成申请记录并进入人工审批（PENDING_MANUAL）；
- 申请编号可随时查询状态。

### 5.2 技能中心（单 Skill 页签）
`技能` → 卡片网格（分页）→ 点开：
- **内容**：SKILL.md 与 manifest；
- **代码**：rules.yaml 编辑/保存/另存版本；
- **审查**：送审、通过/驳回、历史；
- **调试**：输入 JSON → 决策/动作/命中规则/耗时（不落库）；
- **版本**：任意版本查看、基于版本新建；
- **启用**：启停与热加载。

### 5.3 MCP 接入
控制台「MCP」→「获取 MCP」→ 生成个人 API Key（仅显示一次）→ 客户端配置：
```json
{
  "mcpServers": {
    "jiuding": {
      "url": "http://<host>:30080/mcp",
      "headers": { "X-API-Key": "tk_xxx" }
    }
  }
}
```
每次 `tools/call` 都会写入调用审计（工具/成败/耗时/IP/归属人），可按人追溯。

### 5.4 企业身份
- **OIDC/SSO**：配置 `TENANCY_OIDC_*` 后登录页出现「企业 SSO 登录」；
- **MFA**：右上角菜单 →「两步验证」→ 绑定 TOTP（8 个一次性恢复码）；
- **SCIM**：平台管理员「SCIM 配置」→ 生成 Token → IdP 侧配置 `https://<host>/scim/v2`。

## 6. 架构与模块

| 模块 | 职责 |
|---|---|
| `agent-kernel` | AgentScope Java 封装（ReActAgent/Toolkit/ScriptedModel/OpenAI 兼容） |
| `tenancy-common` | 统一响应/错误码/JWT/AuthFilter/租户上下文/安全配置 |
| `tenant-service` | 租户/组织/账号/评级/入驻/导入/账号安全/MFA/SCIM/RBAC/生命周期 |
| `approval-service` | 审批工作流、自动决策、SLA、审计 |
| `agent-service` | Skill 加载与规则引擎、Agent 运行时、技能中心 |
| `mcp-service` | MCP JSON-RPC/SSE、22 工具、API Key/审计/外部 MCP |
| `gateway` | 登录（平台/租户/SSO/MFA）、限流、路由代理、平台聚合 |
| `tenancy-cli` | `jiuding` CLI（兼容 `tenancy`） |
| `frontend` | React + TS 控制台（开发者工具极简风格（Codex-inspired）） |
| `skills/` | 5 个内置技能包（INTAKE/RISK/APPROVAL/PROVISIONING/RATING） |

数据：PostgreSQL（tenant_db / approval_db / agent_db / mcp_db），Flyway 迁移；开发可用 H2。

## 7. 配置与密钥

所有配置见 `deploy/.env.example` 与各服务 `application.yml`；**生产必须覆盖**：
`TENANCY_SECURITY_JWT_SECRET`、`TENANCY_INTERNAL_TOKEN`、`TENANCY_SECURITY_API_KEYS`、`POSTGRES_PASSWORD`、`MCP_DB_PASSWORD`，以及 OIDC/SCIM 相关凭据。

## 8. 验证与质量

```powershell
mvn -B -f pom.xml test                 # 473 自动化测试
cd frontend; npm run build              # 前端构建
pwsh scripts/verify.ps1 -BaseUrl http://localhost:30080   # 17 项统一验证
```
E2E 覆盖 v1~v6：租户链路、组织/账号/评级、租户登录与 RBAC、账号安全、技能/MCP/入驻/权限/生命周期、技能版本与调试、OIDC/MFA、SCIM。

## 9. 路线图（0.9 → 1.0）

1. **安全与隔离**：DB RLS/独立 schema、密钥管理、审计归档、渗透测试
2. **可用性**：备份恢复、多区域、容量与压测、故障演练
3. **身份企业化**：多 IdP、令牌撤销、SCIM 增量同步与重试
4. **工作流产品化**：可视化编排、并行/补偿、SLA 策略配置
5. **体验**：设计系统统一、响应式、可访问性、i18n
6. **生态**：MCP 市场/注册中心、Skill 包签名与供应链校验

## 10. 许可与致谢

- 自研代码/文档：**Apache License 2.0**，作者 **suhong**
- 第三方组件：保留其各自许可证（见 `THIRD_PARTY_LICENSES.md` 与 `NOTICE`）
- 感谢 AgentScope、Spring Boot、React、Vite、PostgreSQL、Flyway、H2、picocli 等开源社区
