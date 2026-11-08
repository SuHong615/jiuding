# 仓库结构与目录规范（Repository Structure）

> 工程名：`jiuding` ｜ 产品名：九鼎 · 多租户智能中枢 ｜ 包路径：`com.suhong.jiuding.*`

## 1. 顶层目录（当前）

```
jiuding/
├─ pom.xml                     # Maven 聚合（groupId com.suhong.jiuding, artifactId jiuding）
├─ agent-kernel/               # AgentScope Java 封装（ReActAgent/Toolkit/Model）
├─ tenancy-common/             # 公共：响应/错误码/JWT/AuthFilter/租户上下文/安全配置
├─ tenant-service/             # 租户/组织/账号/评级/入驻/导入/MFA/SCIM/RBAC/生命周期
├─ approval-service/           # 审批工作流/自动决策/SLA/审计
├─ agent-service/              # Skill 注册与规则引擎/Agent 运行时/技能中心
├─ mcp-service/                # MCP JSON-RPC/SSE/工具/API Key/审计/外部服务器
├─ gateway/                    # 登录（平台/租户/SSO/MFA）/限流/路由/平台聚合
├─ tenancy-cli/                # CLI（命令名 jiuding，兼容别名 tenancy）
├─ frontend/                   # React + TS 控制台（开发者工具极简风格（Codex-inspired））
├─ skills/                     # 5 个内置技能包（SKILL.md + rules.yaml + examples）
├─ deploy/                     # docker-compose/Dockerfile/postgres init/k8s 清单与脚本
│  └─ k8s/                     # k3s 清单、prod overlay、零中断/镜像更新脚本
├─ scripts/                    # build/test/run-local/stop-local/verify/env
├─ tests/                      # v1~v6 E2E 与 MCP 冒烟脚本
├─ integration/                # Lead 集成验证台（start/stop/verify）
├─ docs/                       # 架构/API/数据/规范/验收/产品/开源指引
│  ├─ assets/                  # 截图
│  ├─ PRODUCT_GUIDE.md         # 产品指引（定位/流程/路线图/非生产级声明）
│  ├─ OPEN_SOURCE_GUIDE.md     # GitHub 提交与开源合规清单
│  └─ REPO_STRUCTURE.md        # 本文件
├─ .github/workflows/ci.yml    # CI：后端测试/前端构建/许可证头校验
├─ LICENSE / NOTICE / THIRD_PARTY_LICENSES.md
├─ README.md / CONTRIBUTING.md / SECURITY.md / CODE_OF_CONDUCT.md / CHANGELOG.md
└─ .gitignore / .editorconfig
```

## 2. 命名与代码规范

| 项 | 规范 |
|---|---|
| Maven groupId | `com.suhong.jiuding` |
| Maven 根 artifactId | `jiuding`（版本 `0.9.0-SNAPSHOT`） |
| Java 包 | `com.suhong.jiuding.<module>`，如 `com.suhong.jiuding.tenant` |
| 源文件头 | Apache-2.0 头 + `@author suhong`（CI 校验） |
| 前端包名 | `jiuding-console` |
| CLI 根命令 | `jiuding`（`aliases = {"tenancy"}` 兼容旧脚本） |
| 数据库迁移 | Flyway `V<number>__<desc>.sql`，已发布迁移不可修改 |
| 文档 | `docs/NN-主题.md`；产品/仓库类文档用大写文件名 |

## 3. 不入库内容（`.gitignore` 已覆盖）

- 构建产物：`target/`、`frontend/dist/`、`frontend/node_modules/`
- 本地数据：`data/`、`*.db`、`*.log`
- 密钥：`.env`、`.env.*`（仅保留 `.env.example`）、kubeconfig、`*.pem`、`*.key`
- IDE/OS：`.idea/`、`*.iml`、`.vscode/`（可按需保留共享配置）、`.DS_Store`

## 4. 本次开源化重构记录（2026-11-08）

1. **产品/工程命名**：`tenancy-center` → 产品「九鼎 · 多租户智能中枢」、工程 `jiuding`。
2. **Java 包路径**：`com.tenancy.*` → `com.suhong.jiuding.*`（462 个源文件移动+引用替换）。
3. **Maven 坐标**：`groupId com.suhong.jiuding`、根 `artifactId jiuding`、版本 `0.9.0-SNAPSHOT`。
4. **CLI**：根命令 `jiuding`，保留 `tenancy` 别名。
5. **源码头**：全部自研源码添加 Apache-2.0 头与 `@author suhong`（587 个文件）。
6. **许可证件**：`LICENSE`、`NOTICE`、`THIRD_PARTY_LICENSES.md`。
7. **仓库治理**：`CONTRIBUTING`、`SECURITY`、`CODE_OF_CONDUCT`、`CHANGELOG`、CI、`.gitignore`、`.editorconfig`。
8. **文档**：新增产品指引、开源提交指引、本结构说明；README 重写。

> 部署命名（镜像 `tenancy/*`、k8s namespace `tenancy-center`、数据库 `tenant_db`）暂未改名：它们是运行环境的稳定标识，改名会破坏已有部署与脚本；在 1.0 前可统一切换为 `jiuding-*`（见路线图）。

## 5. 建议的后续目录演进

- `deploy/k8s/base|overlays`（Kustomize 化，替代当前脚本式 prod overlay）
- `docs/adr/`（架构决策记录，ADR-0001...）
- `tests/contract/`（前后端契约测试，OpenAPI 生成）
- `packages/`（若前端与 CLI 进一步组件化）
- `SECURITY.md` 扩展为漏洞披露流程 + SBOM（CycloneDX）
