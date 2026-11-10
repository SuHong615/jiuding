# 部署指南（Docker Compose）

`deploy/` 提供多租户中心的**一键容器化部署**：PostgreSQL 16 + Redis 7 + 5 个 Spring Boot 服务 + 前端 nginx。
本仓库开发机没有 Docker Engine，因此该目录以「静态正确」交付：YAML/路径/健康检查均可在目标机器直接使用；
本地无 Docker 时的进程级验证请用 `scripts/run-local.ps1` + `scripts/verify.ps1`。

## 1. 前置条件

- Docker Engine 24+（含 Compose v2，`docker compose version` 可用）
- 首次构建需要外网访问 Maven Central / npm registry（或已配置镜像）
- 仓库完整（根 `pom.xml` 列出全部模块；构建上下文为仓库根目录）

## 2. 一键启动 / 停止 / 重置

```bash
cd tenancy-center/deploy

# 可选：复制并修改环境变量
cp .env.example .env

# 构建并后台启动（首次约 5-10 分钟，取决于网络与镜像缓存）
docker compose --env-file .env up -d --build

# 查看状态（所有服务应为 healthy）
docker compose ps

# 跟踪日志
docker compose logs -f gateway

# 停止（保留数据）
docker compose down

# 彻底重置（删除 PostgreSQL 数据卷，数据库初始化脚本会重新执行）
docker compose down -v
```

## 3. 端口与服务

| 服务 | 容器端口 | 宿主机端口 | 说明 |
|---|---|---|---|
| gateway | 8080 | **8080** | 唯一对外 API 入口（JWT/代理/聚合/MCP 转发） |
| frontend | 80 | **8088** | 前端 SPA（nginx 反代 `/api`、`/mcp` 到 gateway） |
| tenant-service | 8081 | 不发布 | 租户生命周期 + 采集 |
| approval-service | 8082 | 不发布 | 审批工作流 |
| agent-service | 8083 | 不发布 | Agent + Skill 规则引擎 |
| mcp-service | 8084 | 不发布 | MCP JSON-RPC |
| postgres | 5432 | 不发布 | 四库：tenant_db / approval_db / agent_db / mcp_db |
| redis | 6379 | 不发布 | 内部缓存（无密码，healthcheck = `redis-cli ping`） |

访问：前端 <http://localhost:8088>，API <http://localhost:8080>。

## 4. 默认账号与密钥

| 项 | 值（`deploy/.env` 可覆盖） |
|---|---|
| 平台管理员 | `admin` / `Admin@123` |
| 审批人 | `approver` / `Approver@123` |
| 租户管理员 | `operator` / `Operator@123`（tenant `t-acme`） |
| 审计员 | `auditor` / `Auditor@123` |
| MCP X-API-Key | `dev-mcp-key`（`TENANCY_MCP_API_KEY`） |
| 服务间内部 token | `dev-internal-token`（`TENANCY_INTERNAL_TOKEN`） |
| JWT 密钥 | `TENANCY_JWT_SECRET`（≥16 字符，生产必须修改） |

## 5. 冒烟验证

```bash
# 网关健康
curl -fsS http://localhost:8080/actuator/health

# 登录拿 token
curl -s -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"Admin@123"}'

# MCP 工具清单
curl -s -X POST http://localhost:8080/mcp \
  -H "Content-Type: application/json" -H "X-API-Key: dev-mcp-key" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}'
```

仓库内另有两个更完整的脚本（可在宿主机跑，也可在容器起好后跑）：

```powershell
pwsh ./tests/mcp-smoke.ps1 -BaseUrl http://localhost:8080
pwsh ./tests/e2e.ps1 -BaseUrl http://localhost:8080
```

## 6. 环境变量一览

服务通过 Spring relaxed binding 读取，主要项：

| 变量 | 作用 |
|---|---|
| `TENANT_DB_URL/USER/PASSWORD` | tenant-service 数据源（docker profile） |
| `APPROVAL_DB_URL/USER/PASSWORD` | approval-service 数据源 |
| `AGENT_DB_URL/USER/PASSWORD` | agent-service 数据源 |
| `SPRING_DATASOURCE_URL/USERNAME/PASSWORD` | 标准 Spring 兜底（compose 同时设置） |
| `TENANT_SERVICE_URL` / `APPROVAL_SERVICE_URL` / `AGENT_SERVICE_URL` / `MCP_SERVICE_URL` | 服务间调用地址（compose 内使用容器 DNS） |
| `TENANCY_TENANT_URL` / `TENANCY_APPROVAL_URL` / `TENANCY_AGENT_URL` | mcp-service 使用的下游地址别名 |
| `TENANCY_SECURITY_JWT_SECRET` | 共享 JWT 签名密钥 |
| `TENANCY_SECURITY_INTERNAL_TOKEN` | `/internal/**` 服务间调用 token |
| `TENANCY_SECURITY_API_KEYS` | MCP X-API-Key 白名单（逗号分隔） |
| `TENANCY_SKILLS_DIR` | agent-service 技能目录（镜像内 `/app/skills`） |
| `OPENAI_BASE_URL/API_KEY/MODEL` | 可选：OpenAI 兼容模型；为空时使用离线 scripted 模型 |

## 7. 排障

| 现象 | 处理 |
|---|---|
| `postgres` 一直 unhealthy | `docker compose logs postgres`；确认数据卷未残留旧密码（`down -v` 重置） |
| 服务 unhealthy / 重启 | 多为数据库未就绪或迁移失败，查看 `docker compose logs <service>` |
| 前端 8088 打开但 API 502 | gateway 未 healthy；先等 `docker compose ps` 全部 healthy |
| 端口冲突（8080/8088） | 修改 `docker-compose.yml` 中 gateway/ frontend 的 `ports` 映射左侧 |
| 构建时 Maven 下载失败 | 目标机器需可访问 Maven 镜像，或预先 `mvn -B -DskipTests install` 灌入构建缓存 |
| 修改了 `skills/` | 重建 agent-service：`docker compose up -d --build agent-service` |

## 8. 与本地进程模式的区别

| | Docker Compose | 本地进程（无 Docker） |
|---|---|---|
| 数据库 | PostgreSQL 16（container） | H2 文件库（dev profile） |
| 启动 | `docker compose up -d --build` | `pwsh scripts/run-local.ps1` |
| 前端 | nginx 8088（静态构建产物） | Vite dev server 5173 |
| 验证 | `tests/e2e.ps1 -BaseUrl http://localhost:8080` | `pwsh scripts/verify.ps1` |
