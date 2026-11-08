# mcp-service（8084）

MCP Server：JSON-RPC 2.0 over HTTP + SSE，暴露 22 个租户/身份域/审批/技能/Agent 工具。

## 启动

```powershell
$env:Path='D:\deepseekHarness\env\apache-maven-3.9.9\bin;'+$env:Path
mvn -B -f mcp-service\pom.xml test
java -jar mcp-service\target\mcp-service-0.9.0-SNAPSHOT.jar
```

## 传输与认证

| 端点 | 说明 |
|---|---|
| `POST /mcp` | JSON-RPC 2.0 请求；`notifications/*` 返回 `202` 空响应 |
| `GET /mcp` | SSE 事件流；立即发送 `: connected`，每 15s 发送 `: ping`；响应头 `X-Accel-Buffering: no` |
| `GET /mcp/info` | 能力快照（server/protocolVersion/tools/resources/prompts/apiKeyRequired） |

认证：`X-API-Key`（静态 env key `TENANCY_SECURITY_API_KEYS`，默认 `dev-mcp-key`；或 v5 个人 key `tk_...`）
或 `Authorization: Bearer <jwt>`。v5 起 `tk_` 个人 key 先按 SHA-256 hash 查 `mcp_api_keys`（ACTIVE、未过期、未撤销），
命中后以内嵌 JWT 透传给共享 `AuthFilter`，并每 `tools/call` 写 `mcp_call_audits`；静态 key 兼容（审计 `key_prefix=static`）。
由 `tenancy-common` 的 `AuthFilter` 统一鉴权。`initialize` 生成并回显 `Mcp-Session-Id`；后续请求带则原样回显。
响应回显 `X-Request-Id`。

## v5 控制台 API（mcp_db，经网关 `/api/mcp/**`）

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/mcp/config` | MCP 接入配置：endpoint/transport/protocolVersion/sampleConfig/keyHint/docs |
| GET | `/api/mcp/keys[?all=true]` | 本人 key 列表；平台管理员 `all=true` 全量 |
| POST | `/api/mcp/keys` | `{name,expiresInDays?}` → 仅本次返回明文 `apiKey`（`tk_…`，DB 只存 hash） |
| DELETE | `/api/mcp/keys/{id}` | 撤销（本人或平台管理员） |
| GET | `/api/mcp/audits?page=&size=&tool=&success=` | 调用审计（本人；平台管理员全量） |
| GET/POST/DELETE | `/api/mcp/external-servers[/{id}]` | 外部 MCP 服务器（写操作仅平台管理员） |
| POST | `/api/mcp/external-servers/{id}/check` | 连通性探测（记录 `last_check_at/last_check_ok`） |

数据表（Flyway `V1__mcp_credentials.sql`）：`mcp_api_keys`、`mcp_call_audits`、`mcp_external_servers`。
密钥仅存 SHA-256 hash；明文只在创建响应出现一次；撤销/过期后 `/mcp` 返回 401。

## 环境变量

| 变量 | 默认 |
|---|---|
| `SERVER_PORT` | `8084` |
| `TENANCY_SECURITY_API_KEYS` | `dev-mcp-key` |
| `TENANCY_SECURITY_JWT_SECRET` | `tenancy-center-dev-secret-change-me-please`（与其他服务一致） |
| `TENANT_SERVICE_URL` / `APPROVAL_SERVICE_URL` / `AGENT_SERVICE_URL` | `http://localhost:8081/8082/8083` |
| `MCP_DOWNSTREAM_API_KEY` | `dev-mcp-key`（调用方无 Authorization 时用于下游） |
| `TENANCY_MCP_TOOL_TIMEOUT_MS` / `TENANCY_MCP_SKILL_TIMEOUT_MS` | `10000` / `30000` |
| `TENANCY_MCP_SSE_HEARTBEAT_MS` / `TENANCY_MCP_SSE_TIMEOUT_MS` | `15000` / `3600000` |
| `MCP_DB_URL` | `jdbc:h2:file:./data/mcp;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE`（docker/k8s 用 `jdbc:postgresql://postgres:5432/mcp_db`） |
| `MCP_DB_USER` / `MCP_DB_PASSWORD` | `sa` / 空（docker/k8s 用 `tenancy`/`tenancy`） |
| `MCP_PUBLIC_ENDPOINT` / `MCP_DOCS_URL` | `/mcp` / `docs/05-mcp-spec.md`（`/api/mcp/config` 展示用） |
| `TENANCY_MCP_EXTERNAL_CHECK_TIMEOUT_MS` | `5000`（外部 MCP 连通性探测超时） |

## 工具（22）

基础 14 个：`tenant.create`、`tenant.list`、`tenant.get`、`tenant.submit`、`tenant.suspend`、`tenant.activate`、
`collection.import`、`approval.list_pending`、`approval.get`、`approval.decide`、
`skill.list`、`skill.run`、`agent.run`、`platform.overview`（参数契约见 `docs/05-mcp-spec.md`）。

身份域扩展 8 个（docs/11 §6，`tenantId` 注入 `X-Tenant-Id`）：`org.tree`、`org.create`、
`account.list`、`account.create`、`account.reset_password`、`rating.assess`、`rating.list`、`tenant.delete`。

成功：`{content:[{type:"text",text:"<json>"}], structuredContent:<data>, isError:false}`。
下游失败（非 2xx / `code != 0` / 不可达）：`isError:true` + `structuredContent:{errorCode,message}`，HTTP 仍为 200。
协议错误使用 JSON-RPC 标准码：`-32700 / -32600 / -32601 / -32602 / -32603`。

## Resources / Prompts

- Resources：`tenancy://tenants`、`tenancy://tenants/{id}`、`tenancy://skills`、`tenancy://approvals/pending`
- Prompts：`tenant_review(tenantId)`、`approval_brief(approvalId)`

## 示例

```powershell
curl.exe -s http://localhost:8084/mcp/info -H "X-API-Key: dev-mcp-key"
curl.exe -s -X POST http://localhost:8084/mcp -H "X-API-Key: dev-mcp-key" -H "Content-Type: application/json" `
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"tenant.list","arguments":{"size":5}}}'
```

## 设计要点

- 下游身份：透传调用方 `Authorization`；无 Bearer 时用 `X-API-Key`（调用方 key 或 `MCP_DOWNSTREAM_API_KEY`）。
- `platform.overview` 对每个下游独立降级，仅当全部不可达时返回 `isError:true`。
- SSE 心跳线程为守护线程，连接断开自动摘除；`X-API-Key` 拒斥时由过滤器返回 `401 + ApiResponse{code:40100}`。
