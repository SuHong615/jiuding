# 18 · v8：租户数据导出与离户（Offboarding）

> 状态：实施中（2026-11-08）｜Owner：Lead｜关联：docs/03、docs/14、docs/17、docs/PRODUCT_GUIDE.md

## 1. 目标

补齐租户生命周期的“数据可携带（Portability）”与“离户（Offboarding）”：

| # | 目标 | 验收 |
|---|---|---|
| G1 | 租户数据导出：租户/组织/账号/评级/审计 打包 JSON | 下载 + 审计 |
| G2 | PII 掩码：默认掩码，平台管理员可导出明文 | mask 参数 |
| G3 | 离户流程：终止 → 软删 → 审计，带原因 | 状态校验 |
| G4 | 前端：导出按钮、离户危险操作 | 权限与二次确认 |
| G5 | CLI：`tenant export` / `tenant offboard` | table/json |
| G6 | E2E + 文档 | 全绿 |

## 2. 导出 API

```
GET /api/tenants/{tenantId}/export?mask=true
→ 200 application/json; charset=UTF-8
  Content-Disposition: attachment; filename="tenant-<code>-export-<yyyyMMddHHmmss>.json"

{
  "exportedAt": "...",
  "tenant": { ...TenantView... },
  "orgUnits": [ { id, parentId, code, name, type, path, depth, sortOrder, managerAccountId } ],
  "accounts": [ { id, username, displayName, email, phone, role, orgUnitId, status,
                  mustChangePassword, lastLoginAt, createdAt } ],
  "rating": { "current": {...} | null, "history": [ ...最多 100 条... ] },
  "audits": [ { at, actor, action, detail } ...最多 500 条... ]
}
```
- 权限：PLATFORM_ADMIN / TENANT_ADMIN / AUDITOR（只读）；ORG_ADMIN/MEMBER 403；
- `mask` 默认 `true`：email/phone 掩码（复用 `PiiMask`）；`mask=false` 仅 PLATFORM_ADMIN 可用（否则 403）；
- 审计 `TENANT_EXPORTED`（记录 mask 与条目数，不含 PII）。
- 租户不存在/已软删 → 404。

## 3. 离户 API

```
POST /api/tenants/{tenantId}/offboard  {reason}
→ TenantView（status=TERMINATED, deletedAt != null）
```
- 仅 PLATFORM_ADMIN；`reason` 必填（≤512）；
- 允许状态：ACTIVE / SUSPENDED；其他状态 409；
- 流程：校验状态 → terminate（状态机）→ 软删除（复用 TenantService.delete 逻辑，置 deleted_at）→ 审计 `TENANT_OFFBOARDED`（含 reason）；
- 已软删租户 404；离户后数据保留直到保留期清理（`tenancy.retention.soft-deleted-tenant-days`，默认 180 天）；
- 幂等：重复离户 404/409（文档注明）。

## 4. 前端

- 租户详情头部操作区：
  - 「导出数据」：下载 JSON（平台/租户管理员/审计员可见；默认脱敏，平台管理员可选“导出明文”二次确认）；
  - 「离户」：danger 按钮，仅平台管理员；弹窗要求填写原因、输入租户 code 二次确认；成功后刷新并提示。
- 权限不足不显示按钮。

## 5. CLI

```
jiuding tenant export <tenantId> [--mask|--no-mask] [--out file.json]
jiuding tenant offboard <tenantId> --reason <text>
```

## 6. 验收

- 后端测试：导出结构/掩码/权限/审计、离户状态机/审计/权限；
- E2E `tests/e2e-v8.ps1`：导出（mask true/false）、离户、离户后 404、权限 403；
- 前端 `npm run build` 0 错误；CLI 测试全绿；
- `scripts/verify.ps1` 纳入 v8 门控。
