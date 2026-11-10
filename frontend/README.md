# 多租户中心 · 前端控制台

Vite 5 + React 18 + TypeScript + react-router-dom@6，手写 CSS，**不依赖 antd / element / 图表库**。
所有数据均来自真实后端 API（网关 8080），无 mock 数据；后端未启动时页面显示可读错误态并支持重试。

## 开发与构建

```bash
npm install --registry=https://registry.npmmirror.com
npm run dev        # http://localhost:5173（/api、/mcp 代理到 http://localhost:8080）
npm run build      # tsc --noEmit && vite build → dist/
npm run preview    # 预览构建产物
```

Node 版本：>= 18（本机验证 Node 25 / npm 11）。开发环境依赖网关 `http://localhost:8080`。

## 页面与路由

| 路由 | 页面 | 数据来源 |
|---|---|---|
| `/login` | 登录（预置账号可点选填充） | `POST /api/auth/login` |
| `/` | 运营总览：KPI、状态分布 SVG 条形图、服务健康、最近审批 | `/api/platform/overview`、`/api/platform/health`、`/api/tenants/overview`、`/api/approvals` |
| `/tenants` | 租户列表：状态/计划筛选、关键词搜索、分页、新建/编辑抽屉、评级列、查看/编辑/停用(启用)/删除（软删，仅非 ACTIVE） | `/api/tenants`、`DELETE /api/tenants/{id}` |
| `/tenants/:id` | 租户详情 Tabs（`?tab=` 同步）：概览 / 组织架构 / 账号 / 评级 / 审批 / 审计；提交/停用/启用/终止 | `/api/tenants/{id}`、`/audits`、`/submit` 等 |
| `/tenants/:id?tab=org` | 组织架构：左树右详情，新增子部门/编辑/移动/删除（有子节点或成员时禁用），成员数徽章 | `/api/tenants/{tenantId}/org-units*` |
| `/tenants/:id?tab=accounts` | 账号：筛选 + 分页表格，新建/编辑抽屉、启停、重置密码（一次性临时密码）、软删 | `/api/tenants/{tenantId}/accounts*` |
| `/tenants/:id?tab=rating` | 评级：等级大徽标 + 五维条 + 重新评估 + 人工覆盖（仅平台管理员）+ 历史 | `/api/tenants/{tenantId}/ratings*` |
| `/collection` | 采集：CSV 拖拽上传、内联 JSON 批量导入、任务列表与错误明细 | `/api/tenants/collect*` |
| `/approvals` | 审批中心：待办 / 全部、状态筛选、分页 | `/api/approvals/pending`、`/api/approvals` |
| `/approvals/:id` | 审批详情：流程节点、timeline、通过/驳回（驳回必填意见）、SLA 升级 | `/api/approvals/{id}/decisions`、`/escalate` |
| `/skills` | 技能卡片列表、热加载 | `/api/skills`、`/api/skills/reload` |
| `/skills/:id` | 技能详情：manifest、规则表格、运行 Playground（findings/decision/trace） | `/api/skills/{id}`、`/{id}/run` |
| `/agents` | Agent 运行列表、发起运行抽屉 | `/api/agents/runs`、`/api/agents/overview` |
| `/agents/:runId` | ReAct 步骤时间线、输出 JSON（RUNNING 时自动轮询） | `/api/agents/runs/{runId}` |
| `/mcp` | 14 个工具目录 + JSON-RPC 控制台（X-API-Key / Mcp-Session-Id） | `POST /mcp`、`GET /mcp/info` |

## 主题

整体遵循 `docs/12-ui-design-spec.md`：白底 `#ffffff`、正文 `#0d0d0d`、1px `#e5e5e5` 边框、8px 圆角、
无渐变/无彩色阴影、紧凑表格与黑色主按钮。`src/index.css` 的 `:root` 为唯一 token 源。

## v3 账号登录与权限

- 登录页支持可选「租户代码」：平台账号留空；租户成员填写租户管理员提供的代码（如 `acme`），登录失败统一提示。
- `Principal` 支持 `orgUnitId / mustChangePassword / authType`；顶栏展示当前角色 / 租户 / 组织，`mustChangePassword=true` 时显示顶部警示条。
- 按角色收敛 UI（后端仍强校验）：PLATFORM_ADMIN 全量；TENANT_ADMIN 本租户写操作（不可删除租户/覆盖评级）；
  ORG_ADMIN 子树内组织与账号写操作；AUDITOR / MEMBER 只读；MEMBER 账号页仅展示本人。
- 评级页新增最近 10 次综合分纯 SVG 趋势图，并保留当前五维条与评级历史。

## v4 安全与合规

- 账号页：`LOCKED` 账号行显示「解锁」；本人账号可「修改密码」（旧密码 + 新密码 + 确认，前端策略校验，成功后清除 mustChangePassword）；
  新增「登录历史」抽屉（时间 / IP / User-Agent / 结果，分页）；「脱敏」开关请求携带 `mask=true` 并显示邮箱/手机掩码。
- 审计 Tab：按 `from/to` 日期范围「导出 CSV」，通过浏览器下载（优先使用 `Content-Disposition` 文件名）。
- 评级页：当前评级与历史显示 delta（↑ / ↓ / —）及等级变化高亮；趋势图保留最近 10 次。

## v5 技能中心 / Agent / MCP / 入驻 / 权限

- 技能中心：筛选（状态/来源/启用）、上传 zip、编辑草稿、提交审核、平台管理员审核（通过/驳回）、启停、删除、下载模板、审核历史。
- Agent：`/api/agents/catalog` 能力目录；运行列表展示 Agent 名称 + 目的 + 状态；详情「本次运行在做什么」+ 中文步骤 + 原始 JSON 折叠。
- MCP：获取 MCP 配置（endpoint + sampleConfig 复制）、我的 Key 新建（一次性明文）/撤销、调用审计分页筛选、外部 MCP 服务器 CRUD + 连通性检查（平台管理员）。
- 自助入驻：登录页「申请入驻」→ `/onboarding` 公开表单，提交后展示申请编号并支持按编号查询状态。
- 导入：组织/账号 CSV 导入（模板下载、导入结果、账号一次性临时密码表格复制）。
- 权限管理：平台管理员角色列表 + 权限矩阵保存 + 新建/删除自定义角色；导航按 `/api/me/permissions` 收敛（接口不可用时回退角色判断）。

## v5.1 技能卡片与六页签详情

- 技能中心 `/skills` 改为卡片网格（每页 9 个 + 分页器），卡片展示名称、描述、类型/状态/启用/来源徽章、最新版本与更新时间，
  快捷操作含打开详情、启用/停用、审核；顶部支持状态/来源/启用筛选与关键词搜索。
- 技能详情 `/skills/:id` 六个页签：内容（SKILL.md + manifest）、代码（rules.yaml 编辑/保存/另存新版本）、
  审查（提交/通过/驳回/审核历史/删除）、调试（JSON + 版本选择 → 决策/动作/命中规则/耗时/notes）、
  版本（列表/查看/基于版本新建）、启用（开关/当前生效版本/热加载说明）；`/versions`、`/debug` 接口未就绪时展示错误态。

## v6 SSO / MFA / SCIM

- 登录页：`GET /api/auth/oidc/status` 启用时显示「企业 SSO 登录」，跳转 `/api/auth/oidc/authorize?redirect=true`；
  新增公开路由 `/sso-callback` 解析 `#token=` / `#error=`，成功拉取 `/api/auth/me` 写入会话并跳首页。
- MFA 登录：登录响应 `mfaRequired` 时切换验证码步骤（支持恢复码），调用 `POST /api/auth/login/mfa` 完成登录。
- 顶栏「两步验证」入口 → `/security/mfa`：查询状态、绑定（secret/otpauth URL、一次性 8 个恢复码）、验证启用、停用。
- 平台管理员 `/scim`：SCIM Token 列表/生成（一次性明文）/撤销、端点 URL 复制、User/Group 字段映射说明；导航按权限收敛。

## v7 配额与用量

- 租户详情「概览」新增「配额与用量」卡片：`GET /api/tenants/{id}/quota`，展示席位/组织部门/存储/API RPM 的
  used/limit/remaining/percent、进度条与 OK / WARNING(≥80%) / EXCEEDED 状态色，含错误/空态兜底。
- 新建/编辑租户表单新增 `quotaOrgUnits`（默认 50），并支持修改 seats/orgUnits/storageGb/apiRpm。
- 账号创建、组织创建与 CSV 导入失败时识别后端 `QUOTA_EXCEEDED`，展示友好配额提示（导入错误行同步转换）。

## v8 导出 / 离户

- 租户详情头部「导出数据」：默认脱敏 JSON（Content-Disposition 文件名，回退 `tenant-<code>-export.json`）；
  平台管理员可切换「导出明文」并二次确认。
- 「离户」danger 按钮仅平台管理员可见（ACTIVE/SUSPENDED），需填写原因并输入租户 code，调用
  `POST /api/tenants/{id}/offboard`，成功后返回租户列表。

## v10 企业登录 / 租户切换 / 平台管理 / 工作流

- 登录页移除预置账号卡片：账号密码 + 可选租户代码；`tenantSelectionRequired` 时展示租户选择；
  SSO 按钮通过 `GET /api/auth/oidc/providers` 按启用 Provider 渲染。
- 顶栏租户切换器：`GET /api/auth/tenants` 多条时显示，切换调用 `POST /api/auth/switch-tenant` 并刷新。
- 平台管理：平台用户页（列表/新建/编辑/启停/重置密码）、SSO 配置页（Provider CRUD/启停/测试/公开入口）、
  MCP 凭据与调用审计入口。
- 工作流页：租户生命周期阶段 + 活动 + Skill/Agent + 产出；优先 `/api/workflows/tenant-lifecycle`，失败/空数据回退内置模板。
- MCP 导航置顶；页面顶部提供获取配置 / API Keys / 调用审计 / 外部 MCP 服务器（右侧抽屉）入口。

## v11 平台审计中心

- 平台「审计中心」`/platform/audits`（仅平台管理员）：租户下拉、动作输入（候选 datalist）、from/to 日期、关键词筛选；
  分页表格展示时间 / 租户 code+name / 操作人 / 动作 / 详情摘要（截断），含加载/空/错误态。
- 「导出 CSV」带当前筛选条件下载（文件名为服务端 Content-Disposition，回退 `platform-audits-<date>.csv`）。
- 平台导航按 `/api/me/permissions` 与平台管理员角色显隐。

## v12 工作流编排

- 工作流页保留生命周期只读视图；新增「流程定义」区（仅平台管理员）：
  - 列表 code / name / version / status / 更新时间，操作含编辑、校验、发布、删除；
  - 新建/编辑抽屉：code + name + definition JSON（nodes 支持 id/type AUTO_CHECK|HUMAN|PARALLEL|SLA、skill/role/slaHours/next）+ 节点预览表；
  - 校验结果弹窗展示 errors/warnings；发布后审批引擎按新版本执行（后端未就绪时保留默认流程）。
- 调用 `/api/approval-workflows`（列表/CRUD/validate/publish/delete）。

## 工程约定

- `src/api/client.ts`：统一 fetch 封装，解析 `{code,message,data}`，`code !== 0` 抛 `ApiError`；
  自动携带 `Authorization`，租户内用户自动带 `X-Tenant-Id`；**401 清 token 并跳 `/login`**（登录接口除外）；
  `mcpRequest` / `mcpInfo` 单独处理 MCP 原始 JSON-RPC 响应。
- 每页均有 loading / empty / error 三态（`AsyncBlock`），操作结果通过自实现 toast 提示。
- 中文界面，侧边栏 + 顶栏 + 内容区，卡片/表格/徽章/抽屉/弹窗，>=1280 优先的响应式布局。
- `nginx.conf`：SPA fallback + `/api` `/mcp` 反代 `gateway:8080`，MCP SSE 关闭缓冲。

## Docker

`deploy/docker-compose.yml` 中的 frontend 服务使用本目录构建：`node:20-alpine` 构建 dist → `nginx:alpine`，
并挂载 `frontend/nginx.conf` 到 `/etc/nginx/conf.d/default.conf`，对外端口 `8088`。
