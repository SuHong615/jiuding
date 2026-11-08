# 贡献指南（Contributing）

感谢关注 **九鼎 · 多租户智能中枢（Jiuding）**。本仓库当前定位为**参考实现 / 预发布（0.9.x）**，欢迎 Issue 与 PR。

## 开发环境
- JDK 17+、Maven 3.9+
- Node.js 20+（前端）
- Docker / k3s（可选，用于集成验证）
- 本地构建：`mvn -B -f pom.xml test`、`cd frontend && npm ci && npm run build`

## 分支与提交
- 主分支：`main`（受保护，PR 合并）
- 功能分支：`feat/<scope>-<short-desc>`；修复：`fix/<scope>-<short-desc>`
- 提交信息遵循 Conventional Commits：`feat(tenant): ...`、`fix(mcp): ...`、`docs: ...`
- 每个 PR 聚焦单一变更；附测试与文档更新

## 代码规范
- Java：包路径统一 `com.suhong.jiuding.*`；新文件必须包含 Apache-2.0 头与 `@author suhong`
- TypeScript/前端：与现有 开发者工具 UI 规范一致（`docs/12-ui-design-spec.md`）
- 禁止提交密钥、kubeconfig、`.env`、本地数据目录
- 数据库变更使用 Flyway 新版本（不得修改已发布迁移）

## 测试要求
- 后端：`mvn -B -f pom.xml test` 必须全绿
- 前端：`npm run build` 必须 0 错误
- 涉及接口/契约变更时，同步更新 `docs/02-api-contracts.md` 与 `tests/` 下 E2E

## 许可证
- 提交即表示同意以 **Apache License 2.0** 授权你的贡献（见 `LICENSE`）
- 新增第三方依赖需更新 `THIRD_PARTY_LICENSES.md` 与 `NOTICE`

## 安全问题
请勿在公开 Issue 中披露漏洞，按 `SECURITY.md` 私下联系维护者。
