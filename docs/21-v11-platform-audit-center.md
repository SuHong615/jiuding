# 21 · v11：平台审计中心与治理增强

> 状态：实施中（2026-11-09）｜Owner：Lead

## 1. 目标

平台管理员目前只能逐个租户查看审计；MCP 审计已迁到平台，但**租户操作审计缺少跨租户视图**。v11 提供：

| # | 能力 | 验收 |
|---|---|---|
| G1 | 跨租户审计查询：租户/动作/时间范围/关键字/分页 | 平台管理员 |
| G2 | 跨租户审计 CSV 导出 | attachment + 转义 |
| G3 | 前端「平台 → 审计中心」：筛选 + 表格 + 导出 | 0 构建错误 |
| G4 | 网关代理 `/api/platform/audits/**` | 200 |
| G5 | E2E v11 + verify 门控 | 全绿 |

## 2. API（tenant-service，平台管理员）

```
GET /api/platform/audits?tenantId=&action=&from=&to=&keyword=&page=0&size=20
→ PageResult<{
    id, tenantId, tenantCode, tenantName, at, actor, action, detail
  }>
GET /api/platform/audits/export?tenantId=&action=&from=&to=&keyword=&format=csv
→ text/csv attachment（≤10000 行）
```
- 权限：仅 PLATFORM_ADMIN（`@RequirePermission("audit:read")` + 平台受众校验；租户管理员/审计员只能走原租户级接口）；
- 时间：ISO-8601；from/to 可空（默认最近 30 天）；
- 排序：at DESC；
- 审计导出本身写审计 `PLATFORM_AUDIT_EXPORTED`（不含明细）。

## 3. 前端

- 平台分组新增「审计中心」页：筛选（租户下拉/动作/时间/关键字）+ 表格（时间、租户、操作人、动作、详情摘要）+ 分页 + 导出 CSV。
- 仅平台管理员可见。

## 4. 网关

- 代理前缀 `/api/platform/audits`、`/api/platform/audits/**` → tenant-service（JWT 鉴权，平台管理员校验在服务端）。

## 5. 验收

- 后端测试 ≥3 类：跨租户聚合/过滤分页/权限 403/CSV 转义与上限；
- E2E v11：平台管理员查询到跨租户记录、过滤生效、租户管理员 403、CSV 下载；
- `scripts/verify.ps1` 纳入 v11。
