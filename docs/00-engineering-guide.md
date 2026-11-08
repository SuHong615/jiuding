# 00 · 工程规范与本地环境（军团必读）

## 技术栈（已锁定，勿改）

| 层 | 选型 |
|---|---|
| 语言 | Java 17（`JAVA_HOME=C:\Program Files\Microsoft\jdk-17.0.19.10-hotspot`） |
| 构建 | Maven 3.9.9：`D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd` |
| 框架 | Spring Boot 3.3.5（servlet MVC，非 WebFlux） |
| Agent | **AgentScope 官方 Java 版** `io.agentscope:agentscope-core:2.0.4`（JDK17+），由 `agent-kernel` 模块封装 |
| DB | PostgreSQL 16（docker）/ H2 2.x（本地 dev profile）；Flyway 迁移；纯 JDBC + JdbcTemplate |
| 前端 | Vite 5 + React 18 + TypeScript（无重型 UI 库，自绘组件） |
| CLI | picocli 4.7.6 |
| 网关 | Spring Boot 自研代理路由（不引入 Spring Cloud） |
| MCP | JSON-RPC 2.0 手写实现（`/mcp`），协议修订 2025-06-18 形态 |

## 网络与仓库（重要）

- 本机 **Maven Central / npmjs 不可达**，已配置镜像：
  - Maven：`~/.m2/settings.xml` → 腾讯云 `https://mirrors.cloud.tencent.com/nexus/repository/maven-public/`，本地仓 `D:\deepseekHarness\env\m2repo`
  - npm：`npm config get registry` → `https://registry.npmmirror.com`
- **不要**修改 settings.xml / registry；**不要**升级/降级 Spring Boot 版本。
- 新增三方依赖前先确认镜像可解析（`mvn dependency:get -Dartifact=g:a:v`）。

## Docker 现状

本机**没有 Docker Engine 且当前用户非管理员**（WSL 仅有空壳 distro，无网络）。因此：
- 交付物必须包含可直接 `docker compose up` 的 `deploy/`（Dockerfile、compose、Postgres 初始化）；
- 本地验证走 **H2 dev profile + 进程级启动**，由 `scripts/` 与 `tests/` 完成；
- 代码不得依赖 Docker 才能编译/测试（`docker` profile 仅覆盖数据源与镜像构建）。

## 构建与验证命令

```powershell
$mvn = 'D:\deepseekHarness\env\apache-maven-3.9.9\bin\mvn.cmd'
& $mvn -B -f D:\deepseekHarness\tenancy-center\pom.xml -DskipTests install   # 全量编译
& $mvn -B -f D:\deepseekHarness\tenancy-center\pom.xml test                   # 全量单测
```

单模块：`& $mvn -B -f D:\deepseekHarness\tenancy-center\tenant-service\pom.xml test`

## 代码规范

1. Java 17 语法；**禁用 Lombok**（避免注解处理器风险）；DTO 用 `record`，持久层用显式 `JdbcTemplate`。
2. 构造器注入，禁止字段 `@Autowired`。
3. 统一响应：`com.suhong.jiuding.common.api.ApiResponse`；异常抛 `BizException(ErrorCode, msg)`。
4. **多租户失败关闭**：任何租户数据访问前 `TenantContext.require()`；SQL 必须显式带 `tenant_id`。
5. 时间统一 `Instant` / `LocalDateTime`（UTC 存储），对外 ISO-8601 字符串。
6. 每模块 `application.yml` 只放默认值 + 环境变量占位；`dev`（H2）与 `docker`（Postgres）两个 profile 必须都能起。
7. 端口占用：gateway 8080、tenant 8081、approval 8082、agent 8083、mcp 8084。禁止占用 3183（DSH GUI）。
8. 每个模块至少含：`*Application`、`pom.xml`（parent=tenancy-center）、`application.yml`、Flyway 迁移、核心单测（`@SpringBootTest` + MockMvc 或纯单元测试）。
9. 服务间调用用 `RestClient`（Boot 3.3），URL 通过 `@ConfigurationProperties` 或 `${TENANT_SERVICE_URL:http://localhost:8081}` 注入；**必须捕获异常并给出降级路径**，禁止让下游故障拖垮主链路（审批自动决策降级为本地规则）。
10. 提交前自检：`mvn -pl <module> test` 通过 + 关键接口用 curl 冒烟 + 更新 `docs/`。

## 写权限边界（并行开发纪律）

| 目录/模块 | 负责人 |
|---|---|
| `pom.xml`、`tenancy-common/`、`agent-kernel/`、`docs/02-05` | Lead |
| `docs/01/06/07/08`、评审 | architect |
| `tenant-service/` | tenant-svc |
| `approval-service/` | approval-svc |
| `agent-service/`、`skills/` | agent-svc |
| `mcp-service/`、`gateway/` | edge-svc |
| `frontend/` | web |
| `tenancy-cli/`、`deploy/`、`scripts/`、`tests/` | cli-devops |

只写自己 scope 内的文件；跨模块接口变更先改 `docs/02-api-contracts.md` 并在团队任务板记录。
