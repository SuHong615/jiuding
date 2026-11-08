# 第三方许可证文本（Third-Party License Texts）

本目录收集本项目直接/间接依赖的第三方许可证文本与说明，供源码分发与二进制分发合规使用。

| 文件 | 适用组件（示例） | 说明 |
|---|---|---|
| `Apache-2.0.txt` | Spring Boot/Framework、Jackson、Flyway、picocli、AgentScope、k3s/Kubernetes | 与根目录 `LICENSE` 相同 |
| `MPL-2.0.txt` | H2 Database | MPL-2.0；本项目未修改 H2 源码，仅作为依赖使用 |
| `MIT.txt` | React、React DOM、React Router、Vite 及其 MIT 依赖 | 具体版权人见 `THIRD_PARTY_LICENSES.md` |
| `BSD-2-Clause.txt` | nginx、PostgreSQL JDBC Driver（BSD-2） | 二进制分发需保留版权与许可声明 |
| `PostgreSQL.txt` | PostgreSQL 数据库 | PostgreSQL License |
| `Temurin-GPLv2-CPE.md` | Eclipse Temurin JRE（基础镜像） | GPLv2 + Classpath Exception；含源码获取方式 |
| `EPL-1.0.txt` | Logback（EPL-1.0 / LGPL-2.1 双许可） | 如分发二进制请同时保留 EPL 声明 |

> 说明：本目录用于降低合规风险，不构成法律意见。若你修改了任何第三方组件，请按其许可证要求公开修改部分并更新本目录。
