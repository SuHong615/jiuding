# 第三方开源组件与许可证（Third-Party Licenses）

本仓库自研代码以 **Apache License 2.0** 发布（见 `LICENSE`）。以下为运行时/构建期依赖的第三方组件及其许可证。仓库不修改上述组件的源代码；如分发二进制，请保留其各自的许可证与 NOTICE 文件。

## 后端（Java）

| 组件 | 版本 | 许可证 | 项目地址 |
|---|---|---|---|
| AgentScope Java (`io.agentscope:agentscope-core`) | 2.0.4 | Apache-2.0 | https://github.com/agentscope-ai/agentscope-java |
| Spring Boot | 3.3.5 | Apache-2.0 | https://spring.io/projects/spring-boot |
| Spring Framework | 6.1.x | Apache-2.0 | https://spring.io/projects/spring-framework |
| Jackson | 2.17.x | Apache-2.0 | https://github.com/FasterXML/jackson |
| Flyway | 10.x | Apache-2.0 | https://github.com/flyway/flyway |
| H2 Database | 2.2.x | MPL-2.0 or EPL-1.0 | https://h2database.com |
| PostgreSQL JDBC Driver | 42.x | BSD-2-Clause | https://jdbc.postgresql.org |
| picocli | 4.x | Apache-2.0 | https://picocli.info |
| SLF4J / Logback | 2.x / 1.5.x | MIT / EPL-1.0 | https://www.slf4j.org |
| HikariCP | 5.x | Apache-2.0 | https://github.com/brettwooldridge/HikariCP |

## 前端（TypeScript）

| 组件 | 版本 | 许可证 | 项目地址 |
|---|---|---|---|
| React / React DOM | 18.x | MIT | https://react.dev |
| React Router | 6.x | MIT | https://reactrouter.com |
| Vite | 5.x | MIT | https://vitejs.dev |
| TypeScript | 5.x | Apache-2.0 | https://www.typescriptlang.org |

## 基础设施镜像

| 组件 | 许可证 | 说明 |
|---|---|---|
| PostgreSQL 16 Alpine | PostgreSQL License | 数据库 |
| nginx 1.27 Alpine | BSD-2-Clause | 前端静态托管 |
| Eclipse Temurin 17 JRE | GPL-2.0 with Classpath Exception | Java 运行时 |
| curlimages/curl（CronJob） | curl License | 定时任务调用内部接口 |
| k3s / Kubernetes | Apache-2.0 | 容器编排（部署环境，不随仓库分发） |

## 许可证合规说明

1. 本仓库的 `LICENSE` 仅覆盖自研代码与文档；第三方组件版权归其作者所有。
2. 若以二进制形式分发，请随发行物附带本文件与各第三方许可证副本（尤其是 MPL-2.0 的 H2）。
3. 修改第三方组件源码（本仓库当前不存在）需按对应许可证开源修改部分。
4. 如新增依赖，请在 PR 中同步更新本表与 `NOTICE`。
