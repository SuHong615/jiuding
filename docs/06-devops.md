# 06 · DevOps 与交付手册

> 本文给出多租户中心平台的**两条运行路径**：本机（Windows / 无 Docker）的进程级验证路径，以及用户机器（有 Docker）的 `docker compose` 一键路径。架构背景见 `docs/01-architecture.md`，接口契约见 `docs/02-api-contracts.md`。

---

## 1. 环境矩阵

| 项 | 本机（开发/验证机） | 用户机器（Docker 部署） |
|---|---|---|
| OS | Windows 10/11 + PowerShell 7 | 任意支持 Docker Engine 的 Linux/macOS/Windows |
| JDK | 17（`C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`） | 镜像内 `eclipse-temurin:17-jre` |
| Maven | 3.9.9（`D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd`） | 构建阶段 `maven:3.9-eclipse-temurin-17` |
| Node | v25（仅前端 dev/build） | 构建阶段 `node:20-alpine`，运行阶段 `nginx:alpine` |
| 数据库 | H2 2.x 文件库（`dev` profile） | PostgreSQL 16（`postgres:16-alpine`，`docker` profile） |
| Docker | **无 Docker Engine，非管理员**，仅交付静态正确的 compose/Dockerfile | `docker compose up -d --build` |
| 依赖源 | Maven 腾讯云镜像（`~/.m2/settings.xml`，本地仓 `D:\deepseekHarness\env\m2repo`）；npm `https://registry.npmmirror.com` | 构建镜像时同样走镜像源（可传 `MAVEN_MIRROR`/`npm_config_registry` 构建参数） |
| 前端 | Vite dev server `:5173`（proxy `/api`、`/mcp` → `:8080`） | nginx `:8088`（静态 + 反代 `gateway:8080`） |
| 端口 | 8080/8081/8082/8083/8084/5173 | 宿主 8080（gateway）、8088（frontend）、5432（Postgres，可选） |

**约束**：本机**绝不能假设存在 Docker**；所有需要 Docker 的命令只写入文档，不进入构建脚本的强制路径。代码不得为 Docker 增加编译期依赖（`docker` profile 仅覆盖数据源与镜像构建）。

---

## 2. 端口与进程清单

| 组件 | 本机 | Docker（宿主） | 容器内 | 健康检查路径 |
|---|---|---|---|---|
| gateway | `localhost:8080` | `8080` | `8080` | `GET /actuator/health`、`GET /api/platform/health` |
| tenant-service | `localhost:8081` | 不映射 | `8081` | `GET /actuator/health` |
| approval-service | `localhost:8082` | 不映射 | `8082` | `GET /actuator/health` |
| agent-service | `localhost:8083` | 不映射 | `8083` | `GET /actuator/health` |
| mcp-service | `localhost:8084` | 不映射 | `8084` | `GET /actuator/health`、`GET /mcp/info` |
| frontend | Vite `5173` | `8088` | `80` | `GET /` |
| PostgreSQL | 不使用 | `5432`（可选暴露） | `5432` | `pg_isready -U tenancy` |

> **端口纪律**：禁止占用 `3183`（DSH GUI）。启动前用 `Get-NetTCPConnection -LocalPort 8080,8081,8082,8083,8084 -ErrorAction SilentlyContinue` 检查；若被占用，先停掉旧进程（见 §8）。

**启动依赖顺序**：tenant-service → approval-service → agent-service → mcp-service → gateway → frontend。网关是聚合方，最后启动体验最好；downstream 未起时网关仍可启动，只是 `/api/platform/health` 会显示 `DOWN`。

---

## 3. 目录与构建产物

```text
tenancy-center/
├── pom.xml                     # 聚合 POM（Boot 3.3.5 parent，Java 17）
├── docs/                       # 00-05 契约 + 01/06/07/08 架构与交付
├── tenancy-common/             # 共享库：ApiResponse/ErrorCode/AuthFilter/TenantContext
├── agent-kernel/               # AgentScope 2.0.4 封装：AgentKernel/ScriptedModel
├── tenant-service/             # 8081 + Flyway V1__init.sql
├── approval-service/           # 8082 + Flyway V1__init.sql
├── agent-service/              # 8083 + Flyway V1__init.sql
├── mcp-service/                # 8084
├── gateway/                    # 8080
├── frontend/                   # Vite 5 + React 18 + TS；nginx.conf 供 Docker
├── tenancy-cli/                # picocli fat jar
├── skills/                     # 4 个技能包（SKILL.md + rules.yaml + examples/）
├── deploy/                     # docker-compose.yml + 各服务 Dockerfile + postgres init + .env.example
├── scripts/                    # build/test/run-local/stop-local/verify/env .ps1
└── tests/                      # e2e.ps1、mcp-smoke.ps1、README
```

| 产物 | 命令 | 位置 |
|---|---|---|
| 各服务可执行 jar | `mvn -B -DskipTests install` | `<module>/target/*.jar` |
| CLI fat jar | `mvn -f tenancy-cli/pom.xml package` | `tenancy-cli/target/tenancy-cli-*.jar` |
| 前端静态资源 | `npm run build` | `frontend/dist/` |
| 本地运行痕迹 | `scripts/run-local.ps1` | `.run/*.pid`、`.run/*.log` |
| H2 数据 | 首次启动自动创建 | `tenant-service/data/tenant`、`approval-service/data/approval`、`agent-service/data/agent` |

---

## 4. 本地无 Docker 验证路径（本机唯一可执行路径）

### 4.1 一次性准备

```powershell
# 1) 环境变量（JAVA_HOME、MAVEN、npm 镜像由 env.ps1 固定）
D:\deepseekHarness\tenancy-center\scripts\env.ps1

# 2) 校验工具链
& 'C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot\bin\java.exe' -version
& 'D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd' -v
node -v; npm -v
npm config get registry          # 期望 https://registry.npmmirror.com
```

### 4.2 构建 + 单测

```powershell
$mvn = 'D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd'

# 全量构建（跳过测试，确认编译链路）
& $mvn -B -f D:\deepseekHarness\tenancy-center\pom.xml -DskipTests install

# 全量单测（模块级见 docs/07）
& $mvn -B -f D:\deepseekHarness\tenancy-center\pom.xml test

# 前端
Push-Location D:\deepseekHarness\tenancy-center\frontend
npm ci            # 首次；无 lock 文件时 npm install
npm run build     # 期望生成 dist/
Pop-Location

# CLI
& $mvn -B -f D:\deepseekHarness\tenancy-center\tenancy-cli\pom.xml package
```

预期：`BUILD SUCCESS`，根 POM 聚合模块全部 `SUCCESS`；前端 `dist/index.html` 与 `dist/assets/*.js|css` 存在；CLI jar 可执行 `--help`。

### 4.3 启动（进程级）

```powershell
# 按依赖顺序后台启动 5 个 Spring Boot 服务 + Vite dev，PID/日志写 .run/
D:\deepseekHarness\tenancy-center\scripts\run-local.ps1
```

`run-local.ps1` 依次执行（profile 均为 `dev`，H2 文件库）：

| 顺序 | 服务 | 启动方式 | 关键 env |
|---|---|---|---|
| 1 | tenant-service | `java -jar tenant-service/target/*.jar` | `SERVER_PORT=8081` |
| 2 | approval-service | `java -jar approval-service/target/*.jar` | `SERVER_PORT=8082`、`AGENT_SERVICE_URL=http://localhost:8083`、`TENANT_SERVICE_URL=http://localhost:8081` |
| 3 | agent-service | `java -jar agent-service/target/*.jar` | `SERVER_PORT=8083`、`TENANCY_SKILLS_DIR=../skills`、`TENANT_SERVICE_URL=http://localhost:8081` |
| 4 | mcp-service | `java -jar mcp-service/target/*.jar` | `SERVER_PORT=8084`、下游 URL、`TENANCY_SECURITY_API_KEYS=dev-mcp-key` |
| 5 | gateway | `java -jar gateway/target/*.jar` | `SERVER_PORT=8080`、各下游 URL、`TENANCY_USERS`（可选） |
| 6 | frontend | `npm run dev` | Vite proxy → `http://localhost:8080` |

手动等价命令（用于排障单起某个服务）：

```powershell
$env:SPRING_PROFILES_ACTIVE = 'dev'
$env:SERVER_PORT = '8081'
java -jar D:\deepseekHarness\tenancy-center\tenant-service\target\tenant-service-0.9.0-SNAPSHOT.jar
# 其它服务同理，改端口与 env
```

### 4.4 冒烟检查

```powershell
# 服务健康（逐个）
1..5 | ForEach-Object { Invoke-RestMethod "http://localhost:808$($_ - 1)/actuator/health" }

# 网关聚合健康（任一服务未起时该项 DOWN，但接口本身 200）
Invoke-RestMethod http://localhost:8080/api/platform/health | ConvertTo-Json -Depth 5

# 登录拿 token
$login = Invoke-RestMethod -Method Post http://localhost:8080/api/auth/login `
  -ContentType 'application/json' `
  -Body '{"username":"admin","password":"Admin@123"}'
$token = $login.data.token
Invoke-RestMethod http://localhost:8080/api/auth/me -Headers @{ Authorization = "Bearer $token" }
```

### 4.5 端到端验证

```powershell
# 业务 E2E：登录→建租户→提交→审批→MCP→CLI（见 docs/02 §6）
D:\deepseekHarness\tenancy-center\scripts\verify.ps1
D:\deepseekHarness\tenancy-center\tests\e2e.ps1

# MCP 协议冒烟：initialize / tools/list / tools/call
D:\deepseekHarness\tenancy-center\tests\mcp-smoke.ps1

# 期望输出：PASS/FAIL 汇总；存在 FAIL 时退出码非 0
```

### 4.6 重置与停止

```powershell
# 停止全部本地进程（读 .run/*.pid）
D:\deepseekHarness\tenancy-center\scripts\stop-local.ps1

# 清空 H2 数据（下一次启动自动重建表结构）
Remove-Item -Recurse -Force D:\deepseekHarness\tenancy-center\tenant-service\data
Remove-Item -Recurse -Force D:\deepseekHarness\tenancy-center\approval-service\data
Remove-Item -Recurse -Force D:\deepseekHarness\tenancy-center\agent-service\data
Remove-Item -Recurse -Force D:\deepseekHarness\tenancy-center\.run

# 端口残留排查
Get-NetTCPConnection -LocalPort 8080,8081,8082,8083,8084 -ErrorAction SilentlyContinue |
  Select-Object LocalPort,OwningProcess
```

---

## 5. Docker Compose 路径（用户机器执行）

### 5.1 首次部署

```bash
cd tenancy-center
cp deploy/.env.example deploy/.env
# 编辑 deploy/.env：至少覆盖 JWT_SECRET、TENANCY_INTERNAL_TOKEN、TENANCY_SECURITY_API_KEYS
docker compose -f deploy/docker-compose.yml up -d --build
docker compose -f deploy/docker-compose.yml ps
```

期望：7 个容器全部 `healthy`/`running`（`postgres`、`tenant-service`、`approval-service`、`agent-service`、`mcp-service`、`gateway`、`frontend`）；`postgres` 首次启动执行 `deploy/postgres/init/01-databases.sql` 创建 `tenant_db`、`approval_db`、`agent_db`。

### 5.2 访问

| 入口 | 地址 |
|---|---|
| 运营控制台 | `http://localhost:8088` |
| 网关 API | `http://localhost:8080/api/...` |
| MCP | `http://localhost:8080/mcp`（`X-API-Key: dev-mcp-key`） |
| 健康 | `http://localhost:8080/api/platform/health` |

默认账号（`docs/02` §1）：`admin/Admin@123`、`approver/Approver@123`、`operator/Operator@123`、`auditor/Auditor@123`。

### 5.3 日常运维

```bash
# 日志
docker compose -f deploy/docker-compose.yml logs -f gateway
docker compose -f deploy/docker-compose.yml logs -f agent-service

# 重启单服务（代码未变时无需 --build）
docker compose -f deploy/docker-compose.yml restart agent-service

# 更新镜像（代码变更后）
docker compose -f deploy/docker-compose.yml up -d --build

# 停止（保留数据卷）
docker compose -f deploy/docker-compose.yml down

# 彻底重置（删卷，数据丢失）
docker compose -f deploy/docker-compose.yml down -v
```

### 5.4 compose 结构说明

```text
deploy/
├── docker-compose.yml        # 7 容器 + bridge 网络 tenancy-net + 2 卷
├── .env.example              # JWT_SECRET / TENANCY_INTERNAL_TOKEN / TENANCY_SECURITY_API_KEYS / OPENAI_*（可选）
├── postgres/init/01-databases.sql   # 创建 3 库
├── tenant-service/Dockerfile
├── approval-service/Dockerfile
├── agent-service/Dockerfile
├── mcp-service/Dockerfile
├── gateway/Dockerfile
└── frontend/Dockerfile       # node:20-alpine 构建 → nginx:alpine，使用 frontend/nginx.conf
```

- 每个 Spring 服务 Dockerfile 为**多阶段**：`maven:3.9-eclipse-temurin-17` 构建（`mvn -B -pl <module> -am -DskipTests package`）→ `eclipse-temurin:17-jre` 运行；以非 root 用户运行；`HEALTHCHECK` 调 `/actuator/health`（JRE 镜像内需安装 `curl`，或在 Dockerfile 中显式 `apt-get install -y curl`；健康检查命令必须在镜像内存在）。
- `depends_on` 使用 `condition: service_healthy`：`postgres` 健康后才起业务服务；`gateway` 依赖四个后端服务健康；`frontend` 依赖 `gateway` 健康。
- 网络：单一 `bridge` 网络 `tenancy-net`，服务间用容器名互访（`http://tenant-service:8081`）。
- 卷：`tenancy-pgdata`（数据库数据）、`tenancy-skills`（技能包只读挂载到 agent-service `/app/skills`）。

**本机限制**：本机没有 Docker Engine，以上命令**不在本机执行**；交付前只做静态检查（§7.3）。

---

## 6. 环境变量表

### 6.1 全局/公共

| 变量 | 适用 | 默认值 | 说明 |
|---|---|---|---|
| `SPRING_PROFILES_ACTIVE` | 全部 Spring 服务 | `dev`（本地）/`docker`（compose） | H2 vs Postgres |
| `SERVER_PORT` | 全部 Spring 服务 | 各自端口（8080-8084） | 本地可覆盖，避免端口冲突 |
| `TENANCY_SECURITY_JWT_SECRET` | gateway + 全部服务 | `tenancy-center-dev-secret-change-me-please` | HS256 密钥，≥16 字符；**生产必须覆盖** |
| `TENANCY_SECURITY_TOKEN_TTL_MINUTES` | gateway | `120` | JWT 有效期（分钟） |
| `TENANCY_SECURITY_API_KEYS` | 全部服务 | `dev-mcp-key`（mcp）/空（其它） | MCP/机器调用 Key，逗号分隔列表；compose 中由 `.env` 的 `TENANCY_SECURITY_API_KEYS` 注入 |
| `TENANCY_INTERNAL_TOKEN` | tenant-service / approval-service / agent-service | `dev-internal-token` | 内部接口 `/internal/**` 校验凭证 |
| `TENANCY_USERS` | gateway | 内置 4 用户 | JSON 数组覆盖，格式见 `docs/02` §1 |
| `TENANCY_SKILLS_DIR` | agent-service | `../skills`（本地）/`/app/skills`（docker） | 技能包目录 |
| `TENANCY_AGENT_MODEL_PROVIDER` | agent-service | `scripted` | `scripted` 或 `openai` |
| `OPENAI_BASE_URL` | agent-service（可选） | 空 | provider=openai 时必填，OpenAI 兼容端点 |
| `OPENAI_API_KEY` | agent-service（可选） | 空 | provider=openai 时必填 |
| `OPENAI_MODEL` | agent-service（可选） | 空 | 模型名 |
| `TZ` | 容器 | `UTC` | 时间戳统一 UTC 存储/输出 |
| `LOG_LEVEL` / `LOGGING_LEVEL_COM_TENANCY` | 全部 | `INFO` | 排障时设 `DEBUG` |

### 6.2 数据库

| 变量 | 默认（dev） | Docker 值 |
|---|---|---|
| `TENANT_DB_URL` | `jdbc:h2:file:./data/tenant` | `jdbc:postgresql://postgres:5432/tenant_db` |
| `TENANT_DB_USER` | `sa` | `tenancy` |
| `TENANT_DB_PASSWORD` | 空 | `tenancy` |
| `APPROVAL_DB_URL` | `jdbc:h2:file:./data/approval` | `jdbc:postgresql://postgres:5432/approval_db` |
| `APPROVAL_DB_USER` | `sa` | `tenancy` |
| `APPROVAL_DB_PASSWORD` | 空 | `tenancy` |
| `AGENT_DB_URL` | `jdbc:h2:file:./data/agent` | `jdbc:postgresql://postgres:5432/agent_db` |
| `AGENT_DB_USER` | `sa` | `tenancy` |
| `AGENT_DB_PASSWORD` | 空 | `tenancy` |

> 具体 H2 URL 参数（`MODE=PostgreSQL` 等）以各模块 `application.yml` 为准；迁移脚本使用 ANSI 子集，保证两套数据源都能执行。

### 6.3 服务间地址

| 变量 | 默认（本地） | Docker |
|---|---|---|
| `TENANT_SERVICE_URL` | `http://localhost:8081` | `http://tenant-service:8081` |
| `APPROVAL_SERVICE_URL` | `http://localhost:8082` | `http://approval-service:8082` |
| `AGENT_SERVICE_URL` | `http://localhost:8083` | `http://agent-service:8083` |
| `MCP_SERVICE_URL` | `http://localhost:8084` | `http://mcp-service:8084` |

> `.env.example` 中的 `JWT_SECRET`、`INTERNAL_TOKEN`、`MCP_API_KEY` 是 compose 的变量别名，需在 compose 内映射为上面的 `TENANCY_SECURITY_JWT_SECRET`、`TENANCY_INTERNAL_TOKEN`、`TENANCY_SECURITY_API_KEYS`（评审时核对映射，见 `docs/08`）。

---

## 7. 构建、校验与排障命令

### 7.1 构建/测试脚本

| 脚本 | 作用 | 关键行为 |
|---|---|---|
| `scripts/env.ps1` | 导出 `JAVA_HOME`、Maven、npm 配置 | 只设置当前会话，幂等 |
| `scripts/build.ps1` | 全量构建 | `mvn -B -f pom.xml -DskipTests install` |
| `scripts/test.ps1` | 全量测试 | `mvn -B -f pom.xml test`；失败非 0 退出 |
| `scripts/run-local.ps1` | 本地启动 | 按顺序起 5 服务 + Vite，PID/日志写 `.run/` |
| `scripts/stop-local.ps1` | 本地停止 | 读 `.run/*.pid` 优雅停止，清理残留端口 |
| `scripts/verify.ps1` | E2E 验收 | 登录→建租户→提交→审批→MCP→CLI，PASS/FAIL 汇总 |
| `tests/e2e.ps1` | E2E（可独立执行） | 断言 `docs/02` §6 七步链路 |
| `tests/mcp-smoke.ps1` | MCP 协议冒烟 | `initialize`/`tools/list`/`tools/call` |

### 7.2 脚本/配置静态校验（无 Docker 时必做）

```powershell
# PowerShell 脚本语法检查（无需执行）
Get-ChildItem D:\deepseekHarness\tenancy-center\scripts,D:\deepseekHarness\tenancy-center\tests -Filter *.ps1 -Recurse |
  ForEach-Object {
    $errors = $null
    [System.Management.Automation.Language.Parser]::ParseFile($_.FullName, [ref]$null, [ref]$errors) | Out-Null
    if ($errors) { Write-Host "FAIL $($_.Name): $($errors[0].Message)" } else { Write-Host "OK   $($_.Name)" }
  }

# compose/Dockerfile 无法在本机执行，做文本级检查
Get-Content D:\deepseekHarness\tenancy-center\deploy\docker-compose.yml -Raw   # 缩进/端口/健康检查人工核对
Select-String -Path D:\deepseekHarness\tenancy-center\deploy\**\Dockerfile -Pattern 'FROM|USER|HEALTHCHECK|EXPOSE'
```

### 7.3 排障手册

| 现象 | 可能原因 | 处理 |
|---|---|---|
| `Port 8080 already in use` | 上一轮进程未停 | `Get-NetTCPConnection -LocalPort 8080` → `Stop-Process -Id <pid>`，或先跑 `stop-local.ps1` |
| 登录 401 / `invalid signature` | JWT secret 不一致（网关与服务不同 env）或 token 过期 | 统一 `TENANCY_SECURITY_JWT_SECRET`；重新登录 |
| 403 `tenant header does not match token` | `X-Tenant-Id` 与 JWT `tid` 不一致且非 PLATFORM_ADMIN | 去掉租户头或使用 `admin`；检查网关透传是否覆盖了头 |
| 403 `authentication required`（MCP） | 缺 `X-API-Key` 或 key 不在允许列表 | 带 `X-API-Key: dev-mcp-key`；核对 `TENANCY_SECURITY_API_KEYS` |
| 提交租户返回 5xx | approval-service 未起 | 预期应降级为 `SUBMITTED`；若 5xx 属缺陷，查 approval 服务日志与 `APPROVAL_SERVICE_URL` |
| 审批一直 `PENDING_APPROVAL` | 无审批人操作或 SLA 调度未触发 | 用 `approver` 决策；查 `@Scheduled` 日志与 `sla_due_at` |
| Agent 运行报 `NoSuchMethodError`（Jackson/Reactor） | AgentScope 2.0.4 传递依赖被 Boot 3.3.5 版本管理覆盖 | 跑 `mvn -B -pl agent-kernel -am test`；必要时在根 POM 局部固定依赖版本；**禁止升级/降级 Boot** |
| Agent 运行报工具参数校验失败 | `ToolUseBlock` 只带 `input` 未带 `content`（参数 JSON），或工具 schema 与入参不一致 | 已修复（2026-11-08）：脚本模型须同时写入 `content`；新增工具用 `FunctionTool` 声明 schema 注册；回归见 `docs/07` AG-KRN-01 |
| H2 `Database may be already in use` | 同一 `./data/*` 被两个进程打开 | 停掉重复进程；或改 `TENANT_DB_URL` 指向不同文件 |
| H2 启动即报 SQL 语法错 | Flyway 用了 Postgres 方言 | 迁移改 ANSI 子集；见 `docs/03` |
| `docker compose up` 后容器 unhealthy | 健康检查命令不存在（JRE 镜像无 curl）或启动超时 | Dockerfile 装 `curl`；提高 `start_period`；`docker compose logs <svc>` |
| `docker compose` Postgres 无库 | init 脚本未执行（卷已存在） | `down -v` 重建；确认 `01-databases.sql` 挂载路径 |
| 前端 `/api` 404 | Vite proxy 未指向 8080 或网关未起 | 检查 `vite.config.ts` 的 `server.proxy`；先起网关 |
| SSE 流被缓冲、事件滞后 | 反向代理开启缓冲 | 服务响应加 `X-Accel-Buffering: no`；nginx 配 `proxy_buffering off` |
| 中文输出乱码 | PowerShell 控制台编码非 UTF-8 | `chcp 65001`；脚本文件保存为 UTF-8 无 BOM |
| Maven 依赖下载失败 | 镜像不可达/未配置 | 核对 `~/.m2/settings.xml` 镜像与 `D:\deepseekHarness\env\m2repo`；`mvn dependency:get -Dartifact=g:a:v` 验证 |

### 7.4 镜像构建说明（静态交付）

| 镜像 | 基础镜像 | 构建要点 |
|---|---|---|
| `tenancy/tenant-service` 等 5 个 | `maven:3.9-eclipse-temurin-17` → `eclipse-temurin:17-jre` | 多阶段；先 `COPY pom.xml` 预热依赖；`mvn -B -pl <module> -am -DskipTests package`；非 root 用户；`HEALTHCHECK` 打 `/actuator/health` |
| `tenancy/frontend` | `node:20-alpine` → `nginx:alpine` | `npm ci && npm run build`；`COPY frontend/nginx.conf /etc/nginx/conf.d/default.conf`；`HEALTHCHECK wget -qO- http://localhost/` |
| `postgres:16-alpine` | 官方镜像 | 挂载 `deploy/postgres/init/01-databases.sql` 到 `/docker-entrypoint-initdb.d/` |

构建上下文约定：compose 位于 `deploy/`，各服务 Dockerfile 的 `context` 指向仓库根（或对应模块），`dockerfile` 指向 `deploy/<module>/Dockerfile`；`COPY` 路径必须与 context 一致（评审时核对）。

---

## 8. 发布检查清单

- [ ] `mvn -B test` 全绿；根 POM 聚合模块无 skipped 的失败模块。
- [ ] `npm run build` 成功，`frontend/dist/` 存在且 nginx.conf 语法正确。
- [ ] `scripts/verify.ps1` 输出 PASS，覆盖 `docs/02` §6 七步链路。
- [ ] `tests/mcp-smoke.ps1` 通过（`initialize`/`tools/list` 14 个工具/`tools/call`）。
- [ ] 所有 `.ps1` 通过 §7.2 语法检查；`deploy/` 人工核对 YAML 缩进、端口、健康检查命令。
- [ ] 默认密钥仅在 dev 生效；`deploy/.env.example` 提示必须覆盖 `JWT_SECRET`、`TENANCY_INTERNAL_TOKEN`、`TENANCY_SECURITY_API_KEYS`。
- [ ] 端口不冲突（8080/8081/8082/8083/8084/5173/8088），不占用 3183。
- [ ] H2 数据目录已加入忽略清单，不提交数据库文件与 `.run/`。
- [ ] 文档与实现一致：接口路径、表名、枚举与 `docs/02/03` 对照无差异。

---

## 9. 本机 Docker / Kubernetes 实测环境（2026-11-08 更新）

### 9.1 环境事实（实测）

| 项 | 结论 |
|---|---|
| Windows 侧 | 无 `docker`/`kubectl` CLI，非管理员 |
| WSL2 发行版 `opencode-docker` | Alpine 3.20 rootfs，**Docker Engine 26.1.5 + containerd 1.7.17 + Compose v2.27**，dockerd 常驻 |
| Docker 访问方式 | `wsl -d opencode-docker -e /bin/sh -c "docker ..."`（仓库挂载于 `/mnt/d`） |
| 容器网络 | 可访问 Maven Central / 腾讯镜像 / npmmirror（Windows 主机不可达 Maven Central） |
| k3s | **v1.30.6+k3s1**（Lead 安装并启动，非 systemd：`nohup k3s server --disable traefik`），节点 `Ready`，CoreDNS/local-path/metrics-server 正常 |
| 内存 | WSL VM 7.8 GiB；k8s namespace 实测占用约 2.4 GiB，与 dockerd/opencode 容器共存正常 |

### 9.2 新增交付物

| 路径 | 说明 |
|---|---|
| `deploy/docker-compose.local.yml` | 本机覆盖：gateway 发布 `18080`（8080 被 opencode 占用）、JVM 堆上限、PG `shared_buffers` 调小 |
| `deploy/k8s/00-08*.yaml` | namespace/secret/PG(PVC)/5 服务/frontend 全量清单；所有服务 NodePort 暴露便于本地验证 |
| `deploy/k8s/deploy.sh` | 将 docker 镜像 `save \| k3s ctr images import` 后 apply 并等待 rollout（无需镜像仓库） |
| `deploy/k8s/publish.sh` | 用 socat 容器把 NodePort 发布到 docker 网桥，使 **Windows 可经 localhost 访问**（WSL 原始监听不会被自动转发） |
| `deploy/k8s/portforward.sh` | `kubectl port-forward --address 0.0.0.0` 备选方案（仅 WSL 内可用） |
| `deploy/k8s/runtime.Dockerfile`、`frontend-runtime.Dockerfile` | 基于预构建 jar/dist 的快速运行时镜像（本机 Maven Central 受限时的兜底） |
| `deploy/dockerfiles/*.Dockerfile` | 已加 BuildKit `--mount=type=cache,target=/root/.m2` / `/root/.npm`，跨构建复用依赖缓存 |

### 9.3 NodePort / 访问矩阵

| 服务 | 集群端口 | NodePort | Windows 访问（publish 后） |
|---|---|---|---|
| gateway | 8080 | 30080 | `http://localhost:30080`（API + `/mcp`） |
| tenant-service | 8081 | 30081 | `http://localhost:30081` |
| approval-service | 8082 | 30082 | `http://localhost:30082` |
| agent-service | 8083 | 30083 | `http://localhost:30083` |
| mcp-service | 8084 | 30084 | `http://localhost:30084` |
| frontend | 80 | 30088 | `http://localhost:30088` |

### 9.4 k8s 实测结果

- 7 个 Pod 全部 `Running`（postgres + 5 服务 + frontend），滚动更新完成、就绪探针通过；
- `integration/verify.ps1`（k8s 端口）：**16/16 PASS**，`tenants=1`（新库，证明命中集群）；
- `scripts/verify.ps1 -BaseUrl http://localhost:30080`：**10/10 PASS**（含 E2E 与 MCP 冒烟）；
- K8s 内 Postgres 校验：`tenant_db.tenants=1`、`approval_db.approvals=1 (ACTIVE)`、`agent_db.skill_registry=4`；
- 前端经 nginx：`GET /` 200、`POST /api/auth/login` 200（反代 gateway Service 正常）。

### 9.5 docker compose 全栈验证（2026-11-08 已完成）

- 在 WSL Docker 上实际 `docker compose up`（`--no-build` 使用已构建镜像），**7 容器全部启动、6 healthy**，Postgres 三库 + Flyway v2 迁移成功；
- `scripts/verify.ps1 -BaseUrl http://localhost:18080` → **12/12 PASS**（v1/v2 E2E + MCP）；
- 坑与修复：`eclipse-temurin:17-jre`（Ubuntu 26.04）自带 curl/wget，无需 apt 安装；compose 内重复 `AGENT_SERVICE_URL` 已清理；本地覆盖发布 18080/8088/8081–8084；
- 验证后 `docker compose down` 并恢复 k8s 副本（7 Pod Running）。

### 9.6 生产 Dockerfile 构建校验

| Dockerfile | 结果 | 说明 |
|---|---|---|
| `deploy/dockerfiles/tenant-service.Dockerfile` | ✅ 构建成功（357MB） | 多阶段 Maven 构建 + BuildKit `/root/.m2` 缓存；非 root + HEALTHCHECK |
| `deploy/dockerfiles/frontend.Dockerfile` | ✅ 构建成功（63MB） | node:20 构建 → nginx:alpine；新增 `ARG NPM_REGISTRY`（默认 npmmirror）适配受限网络 |

### 9.7 生产化 overlay（v4，已实测）

| 项 | 配置/结果 |
|---|---|
| 副本 | tenant/approval/agent/mcp/gateway/frontend 均 **2/2 Running**（postgres 1/1） |
| PDB | 6 个 `minAvailable=1`，ALLOWED DISRUPTIONS=1 |
| HPA | gateway/tenant，CPU 70%，min1/max3，当前 2%/12% |
| 评级刷新 | 关闭进程内定时器，改 CronJob `rating-refresh`（19:00 UTC = 03:00 CST）；手动 Job `Complete 1/1`，`{"refreshed":14,"skipped":4,"failed":0}` |
| 零中断 | `zero-downtime-check.sh 90`：滚动重启 gateway/tenant 期间持续探活 **failures=0** |
| 应用方式 | `sh deploy/k8s/prod/apply-prod.sh`（幂等） |

> 注意：CronJob 的 `timeZone` 在最小化 k3s apiserver 上可能因缺 tzdata 报 `unknown time zone`，本清单用 UTC 19:00 表达 03:00 CST。

### 9.8 v5 运行依赖变化（已实测）

| 项 | 变更 |
|---|---|
| 数据库 | 新增 **mcp_db**（mcp-service 凭据/审计/外部服务器）；tenant_db Flyway v5、agent_db v2 |
| Postgres 连接 | `max_connections=300`、`shared_buffers=256MB`（多副本 × Hikari 池叠加） |
| mcp-service | 新增 JDBC/Flyway/PG 依赖；env `MCP_DB_URL/MCP_DB_USER/MCP_DB_PASSWORD`（k8s 由 `tenancy-secrets.MCP_DB_PASSWORD` 注入） |
| CronJob | `rating-refresh`（19:00 UTC）+ `maintenance-purge`（04:00 UTC，内部 token 调 `/internal/maintenance/purge`） |
| 网关 | 关闭 multipart 自动解析（代理上传）；`/mcp/**` 交由 mcp-service 鉴权；新增 `/api/mcp|me|platform/roles|platform/permissions|public` 代理前缀 |
| v6 安全 | 新增 `/api/auth/oidc/**`、`/api/auth/login/mfa` 白名单；`/scim/**` 白名单透传（tenant-service 校验 SCIM token）；`/api/platform/scim-tokens/**` 代理 |
| tenant_db | Flyway 至 **V7**（V4 onboarding/import、V5 RBAC、V6 MFA、V7 SCIM）；agent_db V2；mcp_db V1 |

其余 4 个后端 Dockerfile 与 tenant-service 同构（仅模块名/端口不同），在 compose 构建中途因内存预算中止；k8s 运行时验证使用同源 jar 的 `deploy/k8s/runtime.Dockerfile` 镜像，构建与运行均已证实。

