# 22 · 九鼎 v1 功能版发布说明（Release Notes）

> 版本：`0.9.0`（功能版 / Reference Release）｜日期：2026-11-09
> 工程：`jiuding` ｜ 产品：九鼎 · 多租户智能中枢 ｜ 许可：Apache-2.0 ｜ 作者：suhong

## 1. 发布范围

本轮（v1 ~ v11）功能开发已全部完成并通过测试与部署验证：

| 版本 | 主题 | 状态 |
|---|---|---|
| v1 | 多租户中心、审批自动化、租户采集、MCP、Agent/Skill、CLI、Codex 风格控制台 | ✅ |
| v2 | 组织树、账号目录、五维评级、软删除 | ✅ |
| v3 | 租户账号登录、五档 RBAC | ✅ |
| v4 | 账号安全（锁定/改密/登录审计）、审计导出、PII 掩码、登录限流、k8s 生产化 | ✅ |
| v5 | 技能中心（上传/审核/版本/调试）、MCP 凭据/审计/外部服务器、自助入驻、导入、角色权限、数据生命周期 | ✅ |
| v6 | OIDC/SSO、MFA（TOTP+恢复码）、SCIM 2.0、OIDC 动态 Provider | ✅ |
| v7 | 配额/用量强制、网关租户 API RPM、PostgreSQL 备份/恢复脚本 | ✅ |
| v8 | 租户数据导出（脱敏/明文）、离户（Offboarding） | ✅ |
| v9 | 端点级权限强制（注解 + 拦截器，Phase 1/2） | ✅ |
| v10 | 平台账号管理、多租户登录选择与免密切换、MCP 控制台重构、租户生命周期工作流视图 | ✅ |
| v11 | 跨租户平台审计中心（查询/过滤/分页/CSV 导出） | ✅ |

## 2. 验证数据

- 自动化测试：**601 全绿**（tenant 256 / gateway 87 / agent 80 / mcp 45 / cli 78 / approval 35 / common 18 / kernel 2）
- E2E：v1 16/16、v2 31/31、v3 42/42、v4 20/20、v5 46/46、技能版本/调试 13/13、v6 25/25、v6b SCIM 31/31、v7 23/23、v8 27 PASS/1 SKIP、v10 34/34、v11 24/24
- 统一验证：`scripts/verify.ps1` 覆盖 v1~v11 + MCP，最近一次目标 21/21（技能链曾因滚动重启瞬断，单独重跑 13/13）
- 部署：k3s 7 Deployment Running（2/2 以上）、PDB/HPA/CronJob、Flyway tenant_db V10
- 仓库：GitHub `SuHong615/jiuding`，Apache-2.0，587+ 源文件授权头

## 3. 访问与演示

- 控制台：**http://localhost:30088**（k3s NodePort）
- 网关 / API / MCP：http://localhost:30080（MCP `/mcp`）
- 演示账号：admin/Admin@123、approver/Approver@123、operator/Operator@123（t-acme）、auditor/Auditor@123
- 公网/共享前必须替换所有演示密钥与账号（见 `SECURITY.md`）

## 4. 主要体验路径

1. 登录（账号密码；SSO 按启用的 OIDC Provider 显示；无预置账号卡片）
2. 平台 → 平台用户 / SSO 配置 / 审计中心 / 权限管理 / MCP 凭据 / MCP 调用审计
3. 租户 → 详情：概览（配额/评级/趋势）、组织、账号、评级、审批、审计；导出数据、离户
4. 技能中心：卡片网格 → 单技能六页签（内容/代码/审查/调试/版本/启用）
5. 工作流：租户生命周期 10 状态、29 条迁移、Skill 活动映射
6. 多租户：同一人同用户名+同密码可跨租户，登录选择租户，右上角切换（免密）
7. MCP：获取配置、按人 Key、调用审计、外部 MCP 服务器（右侧抽屉）

## 5. 已知边界（非本次范围）

| 项 | 说明 |
|---|---|
| PostgreSQL RLS / 独立 schema | **未实现**；当前为共享 schema + 应用层 fail-closed 隔离 |
| 工作流可编排 | 已提供只读生命周期视图与内置引擎；**可视化编排/并行补偿/SLA 策略配置未实现** |
| 真实企业 IdP | OIDC 已按标准实现并可配置，但**需真实 issuer/client 联调**（本环境无凭据） |
| 平台账号初始化 | 平台用户表初始为空，admin 走本地预置回退；生产应初始化后移除回退 |
| 缓存/限流多副本 | 权限缓存、SSO Provider 缓存、登录/租户 RPM 均为进程内（多副本不共享） |
| 可观测性 | 按需求**明确暂不做** |
| 容量/故障演练 | 未做压测、混沌与多区域演练 |
| SCIM 直写历史账号 | password_fingerprint 为空，改密后才参与免密切换 |

## 6. 升级与迁移注意

- tenant_db Flyway 至 **V10**（V4 入驻/导入、V5 RBAC、V6 MFA、V7 SCIM、V8 配额、V9 平台身份、V10 密码指纹）
- 开源化时 SQL 迁移加入许可证头会改变 **Flyway checksum**；从旧构建升级需 `flyway repair` 或重建库
- k8s 首次部署使用 `deploy/k8s/gen-secrets.sh` 生成随机 Secret，不要直接 apply `01-secrets.yaml` 模板

## 7. 后续（生产化路线）

1. 数据面隔离：RLS / 独立 schema / 每租户密钥
2. 工作流产品化：可视化编排、并行/补偿、SLA 策略
3. 身份企业化：真实 IdP 联调、多 IdP、令牌撤销、SCIM 增量同步
4. 高可用：备份恢复演练、故障切换、容量压测
5. 可观测性（若后续需要再立项）
