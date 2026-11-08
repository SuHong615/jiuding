# GitHub 开源提交指引（Open Source Playbook）

> 目标：把 `jiuding`（九鼎 · 多租户智能中枢）以 **Apache License 2.0** 干净、合规地发布到 GitHub。
> 本文件是提交前检查清单与运营约定，配合 `LICENSE`、`NOTICE`、`THIRD_PARTY_LICENSES.md` 使用。

---

## 1. 命名与仓库元信息

| 项 | 建议值 |
|---|---|
| GitHub 仓库名 | `jiuding`（描述性备选：`jiuding-tenancy`） |
| 产品名 | 九鼎 · 多租户智能中枢 |
| 英文名 | Jiuding · Multi-Tenant Intelligence Hub |
| About 描述 | `Apache-2.0 multi-tenant hub: tenant lifecycle, agentic approvals, Skill center, MCP, RBAC, SSO/MFA/SCIM` |
| Topics | `multi-tenant` `saas` `agentscope` `mcp` `spring-boot` `react` `rbac` `scim` `oidc` `skill-engine` |
| 默认分支 | `main` |
| 首发版本 | `v0.9.0`（预发布，明确非生产级） |

> 若仓库名与本地目录不同，上传时可执行：
> `git init && git remote add origin git@github.com:<you>/jiuding.git`
> 本地目录名 `tenancy-center` 不影响 GitHub 仓库名。

## 2. 许可证与合规（必须）

- [x] 根目录 `LICENSE`（Apache-2.0 全文）
- [x] 根目录 `NOTICE`（版权 + 第三方组件摘要）
- [x] 根目录 `THIRD_PARTY_LICENSES.md`（逐组件许可证表）
- [x] 所有自研源码文件头部含 Apache-2.0 头 + `@author suhong`
- [x] 未修改第三方源码；未内嵌非兼容许可证代码
- [x] `package.json` 的 license 字段建议设为 `"license": "Apache-2.0"`（可在合并前补充）
- [x] CI 增加 license-header 检查（`.github/workflows/ci.yml`）

**合规红线**
1. 不要把第三方源码复制进本仓库（除非保留其 LICENSE/NOTICE 并隔离目录）。
2. 新增依赖必须在 PR 中更新 `THIRD_PARTY_LICENSES.md`。
3. 不要移除文件头；CI 会校验。
4. 商标：产品名「九鼎/Jiuding」不宣称已注册商标；不使用其它公司商标。

## 3. 提交前代码与仓库清理

- [ ] 确认 `.gitignore` 生效：`target/`、`node_modules/`、`frontend/dist/`、`data/`、`.env*`、`*.log`、kubeconfig、密钥文件不入库
- [ ] 删除本地运行产物：各模块 `target/`、`frontend/node_modules`、`frontend/dist`、`data/`、`deploy/.env`
- [ ] 检查敏感信息：`dev-internal-token`、`dev-mcp-key`、`Admin@123` 等仅作为**演示默认值**保留在 `.env.example`/文档中；生产值从未入库
- [ ] 如需彻底清理历史：`git filter-repo --path-glob '*.env' --invert-paths`（谨慎）
- [ ] 截图与素材：`docs/assets/*.png` 可公开（不含真实用户数据）
- [ ] 大文件：确认仓库 <100MB（GitHub 限制）；镜像不入库

## 4. 分支保护与协作

- `main`：要求 PR、至少 1 次 review、CI 全绿、线性历史（Squash merge）
- 开启 Dependabot（Maven/npm）与 Secret Scanning、CodeQL
- 启用 Issues/Discussions；使用 `.github/ISSUE_TEMPLATE/*`（bug / feature）
- 发布用 Tag + GitHub Release：`v0.9.0`，附带 CHANGELOG 摘要与许可证说明

## 5. CI/CD

`.github/workflows/ci.yml` 已包含：
1. 后端 `mvn -B test`（JDK 17）
2. 前端 `npm ci && npm run build`（Node 20）
3. 许可证头检查（Apache + `@author suhong`）

可选增强：
- 测试覆盖率报告（JaCoCo）与 badge
- Docker 构建/镜像扫描（Trivy）
- GitHub Pages 部署文档站（VitePress）

## 6. Release 检查清单

- [ ] `CHANGELOG.md` 更新（Added/Changed/Security/Known Limitations）
- [ ] 版本号：`pom.xml` 根版本、前端 `package.json`、CLI `VERSION` 一致（当前 0.9.0）
- [ ] README 的「非生产级」声明与 SECURITY 链接有效
- [ ] `mvn -B test` 全绿、`npm run build` 0 错误
- [ ] `scripts/verify.ps1` 在 k8s 通过（可选，本地环境）
- [ ] Release Notes 标注许可证与第三方组件
- [ ] 生成源码包（GitHub 自动）与可选二进制（jar/镜像）

## 7. 对外沟通话术（建议）

> 「九鼎（Jiuding）是一个 Apache-2.0 的多租户中心参考实现：把租户生命周期、Agent 审批、Skill 规则、MCP 开放接口、RBAC/SSO/MFA/SCIM 串成可运行的端到端工程。**当前 0.9.0 为预发布，非生产级**，欢迎用于学习、评估与二次开发。」

**不要**宣称：生产就绪、开箱即用、零信任、等保合规、已通过安全认证。

## 8. 维护者

- 作者/维护者：**suhong**
- 安全问题：见 `SECURITY.md`（私密报告）
- 行为准则：`CODE_OF_CONDUCT.md`
- 贡献流程：`CONTRIBUTING.md`
