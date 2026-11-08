# 变更日志（Changelog）

本项目遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/) 与语义化版本（0.x 阶段 API 可能变化）。

## [Unreleased]

### Planned
- 产品化加固：多租户数据面隔离（RLS / 独立 schema）、备份恢复、压测与容量模型
- OIDC 多 IdP、refresh token、平台用户 MFA
- SCIM 增量同步日志、Token 轮换策略
- 可观测性（明确暂缓）

## [0.9.0] - 2026-11-08

### Added
- 多租户中心：租户生命周期、审批自动化、租户采集、Skill 规则引擎、MCP 服务、Agent 运行时、CLI、开发者工具极简风格（Codex-inspired）控制台
- 组织/账号/评级（五维评级、周期刷新）、租户账号登录、五档 RBAC
- 账号安全：锁定/解锁、本人改密、登录审计（IP/UA）、审计 CSV 导出、PII 掩码、登录限流
- 技能中心：卡片分块 + 单 Skill 六页签（内容/代码/审查/调试/版本/启用），上传/审核/版本/调试/热加载
- MCP 控制台：获取配置、按人 API Key、调用审计、外部 MCP 服务器
- 自助入驻：登录页申请 → Agent 合规审核 → 自动开通/人工决策
- 角色权限：43 权限 / 11 组 / 5 内置角色 + 自定义角色与权限矩阵
- 数据生命周期：保留策略、每日清理、内部 purge 端点
- 身份：OIDC/SSO（可配置 IdP）、MFA（TOTP + 恢复码）、SCIM 2.0（Users/Groups/Token）
- 部署：Docker Compose、k3s 生产 overlay（多副本/PDB/HPA/CronJob/零中断验证）
- 文档：工程指引、架构、API、数据模型、Skill/MCP 规范、测试、验收、开源指南

### Security
- 默认密钥仅用于本地演示，禁止用于生产；部署时必须覆盖 `TENANCY_SECURITY_JWT_SECRET`、`TENANCY_INTERNAL_TOKEN`、`TENANCY_SECURITY_API_KEYS`、数据库口令与 `MCP_DB_PASSWORD`
- K8s 改为 `gen-secrets.sh` 随机生成 Secret；`01-secrets.yaml` 仅保留 `CHANGE_ME_*` 模板
- 新增 `scripts/scan-secrets.ps1` 与 CI secret-scan；补充演示凭据清单（`SECURITY.md`）

### Legal
- Apache-2.0 许可、NOTICE、第三方许可证文本（`LICENSES/`）、`docs/LEGAL.md` 法律风险自查
- 商标/非关联声明（九鼎/Jiuding、Codex-inspired）；设计文档更名为 `docs/12-ui-design-spec.md`

### Known Limitations
- **非生产级**：单区域参考实现，未做数据面隔离与灾备，未做容量/安全认证
- 部分工作流与 MCP 联邦为演示级实现
