# 第三方开源组件与许可证（Third-Party Licenses）

本项目自研代码与文档以 **Apache License 2.0** 发布（`LICENSE`）。以下为依赖组件、许可证、归属与分发义务。**本项目未修改任何第三方源码**；如你分发二进制/Docker 镜像，请按第 4 节履行义务。

> 许可证全文见 `LICENSES/`；版本以构建时 `pom.xml` / `frontend/package-lock.json` 为准。

## 1. 后端依赖（Java）

| 组件 | 许可证 | 版权/来源 | 说明 |
|---|---|---|---|
| AgentScope Java (`io.agentscope:agentscope-core`) | Apache-2.0 | AgentScopeTeam / Alibaba — https://github.com/agentscope-ai/agentscope-java | `agent-kernel` 为独立封装，未复制源码 |
| Spring Boot / Spring Framework | Apache-2.0 | Broadcom/Spring — https://spring.io | 应用框架 |
| Jackson | Apache-2.0 | FasterXML — https://github.com/FasterXML/jackson | JSON |
| Flyway（core / database-postgresql） | Apache-2.0 | Redgate — https://flywaydb.org | 数据库迁移 |
| H2 Database | MPL-2.0（或 EPL-1.0） | H2 项目 — https://h2database.com | 未修改源码 |
| PostgreSQL JDBC Driver | BSD-2-Clause | PostgreSQL Global Development Group | JDBC |
| picocli | Apache-2.0 | Remko Popma — https://picocli.info | CLI |
| HikariCP | Apache-2.0 | Brett Wooldridge | 连接池 |
| SLF4J | MIT | QOS.ch | 日志门面 |
| Logback | EPL-1.0 / LGPL-2.1 | QOS.ch | 日志实现（按 EPL-1.0 使用） |
| Jakarta Servlet / Bean Validation 等 | EPL-2.0 / Apache-2.0 | Eclipse / Jakarta EE | 由 Spring Boot 传递依赖 |

## 2. 前端依赖（TypeScript/Node）

| 组件 | 许可证 | 版权/来源 |
|---|---|---|
| React / React DOM | MIT | Meta Platforms, Inc. and affiliates — https://react.dev |
| React Router | MIT | Remix Software Inc. — https://reactrouter.com |
| Vite | MIT | Yuxi (Evan) You and Vite contributors — https://vitejs.dev |
| TypeScript | Apache-2.0 | Microsoft — https://www.typescriptlang.org |
| 其他 npm 依赖 | 以 `frontend/package-lock.json` 中各包 `license` 字段为准 | 均为 MIT/ISC/Apache-2.0/BSD 类；未发现 GPL/AGPL/SSPL |

## 3. 基础设施 / 运行时镜像

| 组件 | 许可证 | 版权/来源 | 分发义务 |
|---|---|---|---|
| PostgreSQL 16 Alpine | PostgreSQL License | PostgreSQL Global Development Group | 保留版权与许可声明 |
| nginx 1.27 Alpine | BSD-2-Clause | Igor Sysoev / NGINX, Inc. | 二进制分发保留版权与许可 |
| Eclipse Temurin 17 JRE | GPLv2 + Classpath Exception | Eclipse Adoptium | 提供源码获取方式（见 `LICENSES/Temurin-GPLv2-CPE.md`） |
| curlimages/curl（CronJob） | curl License | Daniel Stenberg / curl 项目 | 保留许可声明 |
| k3s / Kubernetes（部署环境） | Apache-2.0 | Rancher / CNCF | 不随本仓库分发 |

## 4. 分发义务清单（源码与二进制）

**源码分发（本仓库）**
1. 保留根目录 `LICENSE`、`NOTICE`、本文件与 `LICENSES/`。
2. 保留源文件版权/许可头；修改文件需显著标注。
3. 不删除依赖包内的版权与许可文件。

**二进制 / Docker 镜像分发**
1. 随附上述许可证文本与 `NOTICE`（可在镜像 `/licenses` 目录或发布页提供）。
2. H2（MPL-2.0）：提供许可文本及源码获取方式（未修改时可指向官方仓库）。
3. Temurin（GPLv2+CE）：提供源码获取方式（Adoptium）。
4. nginx（BSD-2）、PostgreSQL、curl：保留版权与许可声明。
5. React/Vite 等 MIT 组件：保留版权与许可声明（前端 bundle 内嵌其代码）。
6. EPL-1.0 组件（Logback）：保留 EPL 声明与源码获取方式。

## 5. 依赖清单查询方法

```bash
# 后端完整依赖树与许可证（需插件联网）
mvn -B dependency:tree
mvn -B org.codehaus.mojo:license-maven-plugin:2.4.0:aggregate-add-third-party

# 前端已安装依赖的许可证
cd frontend && npx license-checker --summary

# 扫描仓库中许可证头
grep -rL "Licensed under the Apache License, Version 2.0" --include='*.java' --include='*.ts' --include='*.tsx' .
```

## 6. 已知许可兼容性结论

- Apache-2.0 自研代码与 Apache-2.0 / MIT / BSD / PostgreSQL / MPL-2.0 / EPL-1.0 依赖**兼容**。
- MPL-2.0（H2）为文件级 copyleft：仅修改 H2 文件才需公开该文件源码；本项目未修改。
- GPLv2+CE（Temurin）通过 Classpath Exception 与本项目 Apache-2.0 代码链接，不构成传染。
- 未使用 AGPL/SSPL 组件；前端锁文件扫描未发现 GPL/AGPL/SSPL。

> 本文档不构成法律意见；商业发行前请做独立法律复核与依赖扫描。见 `docs/LEGAL.md`。
