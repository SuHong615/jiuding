# 05 · MCP 服务规范

MCP Server 由 `mcp-service`（8084）实现，协议为 **JSON-RPC 2.0 over HTTP**，遵循 MCP `2025-06-18` 修订形态（带 `initialize` 握手与 `Mcp-Session-Id`）。
（注：MCP `2026-07-28` 修订改为无状态单端点；本实现保留 `initialize` 以兼容当前主流 SDK/客户端，同时所有方法在无会话时也可直接调用，便于 curl 调试。）

## 传输

| 项 | 约定 |
|---|---|
| 端点 | `POST /mcp`（请求-响应）、`GET /mcp`（SSE 事件流）、`GET /mcp/info`（能力快照，非协议） |
| 认证 | `X-API-Key: <key>`（默认 `dev-mcp-key`）或 `Authorization: Bearer <jwt>` |
| 请求头 | `Content-Type: application/json`；`Accept: application/json, text/event-stream`；`MCP-Protocol-Version: 2025-06-18`（可选） |
| 会话 | `initialize` 响应返回 `Mcp-Session-Id`；后续请求带 `Mcp-Session-Id`（缺失时按无状态处理） |
| SSE | `GET /mcp` 返回 `text/event-stream`，事件 `data: <json-rpc message>`；15s 心跳注释 `: ping` |
| 通知 | 客户端 `notifications/initialized` → `202 Accepted` 空响应 |

## 方法

### initialize
```json
{"jsonrpc":"2.0","id":1,"method":"initialize","params":{
  "protocolVersion":"2025-06-18","capabilities":{},"clientInfo":{"name":"cli","version":"1.0"}}}
→ {"jsonrpc":"2.0","id":1,"result":{
  "protocolVersion":"2025-06-18",
  "capabilities":{"tools":{"listChanged":false},"resources":{"subscribe":false},"prompts":{}},
  "serverInfo":{"name":"tenancy-mcp","version":"1.0.0"},
  "instructions":"多租户中心 MCP 服务：租户、审批、Skill、Agent、采集"}}
```

### 其它方法
- `ping` → `{}`
- `tools/list` → `{tools:[{name,description,inputSchema}]}`
- `tools/call` → `params:{name,arguments}` → `{content:[{type:"text",text:"<json>"}],structuredContent:{},isError:false}`
- `resources/list` → `{resources:[{uri,name,description,mimeType}]}`
- `resources/read` → `params:{uri}` → `{contents:[{uri,mimeType,text}]}`
- `prompts/list` → `{prompts:[{name,description,arguments:[{name,description,required}]}]}`
- `prompts/get` → `params:{name,arguments}` → `{description,messages:[{role:"user",content:{type:"text",text}}]}`

错误：JSON-RPC 标准码（`-32600/-32601/-32602/-32603`），业务失败用 `isError:true` + `structuredContent.errorCode`。

## Tools（22 个）

> 基础 14 个（租户/采集/审批/Skill/Agent/平台）；另有 8 个身份域扩展工具（组织/账号/评级/删除租户），
> 由 docs/11-tenant-identity-org-rating.md §6 定义 REST 契约，MCP 层透传并将 `tenantId` 注入 `X-Tenant-Id`。

| name | 说明 | arguments |
|---|---|---|
| `tenant.create` | 创建租户 | `tenantCode*, name*, contactEmail, plan, region, isolationMode, quotaSeats, quotaStorageGb, quotaApiRpm, attributes(object), autoSubmit(bool)` |
| `tenant.list` | 分页查询 | `status, plan, keyword, page, size` |
| `tenant.get` | 详情 | `tenantId*` |
| `tenant.submit` | 提交审批 | `tenantId*` |
| `tenant.suspend` | 停用 | `tenantId*, reason` |
| `tenant.activate` | 启用 | `tenantId*` |
| `collection.import` | 批量采集 | `sourceName*, mode(MANUAL_BATCH/API_PULL), autoSubmit(bool), records(array)` |
| `approval.list_pending` | 待办审批 | — |
| `approval.get` | 审批详情 | `approvalId*` |
| `approval.decide` | 审批决策 | `approvalId*, decision(APPROVE/REJECT)*, comment` |
| `skill.list` | 技能列表 | `type` |
| `skill.run` | 执行技能 | `skillId*, tenantId, input(object)` |
| `agent.run` | 运行 Agent | `agentType*, tenantId, input(object), maxSteps` |
| `platform.overview` | 平台总览 | — |
| `org.tree` | 组织树（tree/flat） | `tenantId*, format(tree/flat)` → `GET /api/tenants/{tenantId}/org-units?format=` |
| `org.create` | 创建组织节点 | `tenantId*, parentId, code*, name*, type(COMPANY/BRANCH/DEPARTMENT/TEAM)*, sortOrder, managerAccountId` → `POST .../org-units` |
| `account.list` | 租户账号分页 | `tenantId*, orgUnitId, role, status, keyword, page, size` → `GET .../accounts` |
| `account.create` | 创建账号 | `tenantId*, username*, displayName*, email, phone, orgUnitId, role(TENANT_ADMIN/ORG_ADMIN/MEMBER/AUDITOR)*, initialPassword` → `POST .../accounts` |
| `account.reset_password` | 重置密码（一次性临时密码） | `tenantId*, accountId*, newPassword` → `POST .../accounts/{accountId}/reset-password` |
| `rating.assess` | 触发评级评估 | `tenantId*, note` → `POST .../ratings/assess` |
| `rating.list` | 评级历史（倒序） | `tenantId*, page, size` → `GET .../ratings` |
| `tenant.delete` | 软删除租户（仅平台管理员，非 ACTIVE） | `tenantId*` → `DELETE /api/tenants/{tenantId}` |

## Resources

| uri | 说明 |
|---|---|
| `tenancy://tenants` | 租户列表（JSON） |
| `tenancy://tenants/{id}` | 租户详情 |
| `tenancy://skills` | Skill 清单 |
| `tenancy://approvals/pending` | 待办审批 |

## Prompts

| name | 参数 | 用途 |
|---|---|---|
| `tenant_review` | `tenantId` | 生成租户审查简报（注入 risk-review skill 指令 + 租户快照） |
| `approval_brief` | `approvalId` | 生成审批摘要供人工决策 |

## 实现约束

- 所有工具调用透传到下游服务 REST（`TENANCY_TENANT_URL` / `TENANCY_APPROVAL_URL` / `TENANCY_AGENT_URL`），透传 JWT 或使用内部 token；
- 身份域工具（`org.*` / `account.*` / `rating.*` / `tenant.delete`）把 arguments 中的 `tenantId` 同时注入
  `X-Tenant-Id` 请求头，下游按 fail-closed 校验租户上下文（平台管理员可跨租户；越权返回 403）；
- 下游失败映射为 `isError:true`，`structuredContent={errorCode,message}`，不抛 500；
- `tools/call` 单次超时 10s；`skill.run` 透传超时 30s；
- 响应必须回显 `X-Request-Id`；SSE 需 `X-Accel-Buffering: no` 以适配 nginx；
- `structuredContent` 必须是 JSON **对象**：下游返回裸数组时（如 `org.tree`、`approval.list_pending`）统一包装为
  `{"count": n, "items": [...]}`，`content[0].text` 仍保留原始 JSON；
- `/mcp/info` 返回 `{server, protocolVersion, tools, resources, prompts, apiKeyRequired}`，供前端 MCP 控制台展示。
