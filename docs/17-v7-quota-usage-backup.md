# 17 · v7：租户配额与用量（Quota & Usage）+ 备份恢复

> 状态：**已完成并验收**（2026-11-08）｜Owner：Lead｜关联：docs/03、docs/06、docs/10、docs/PRODUCT_GUIDE.md

## 1. 目标

把已有的 `quota_seats / quota_storage_gb / quota_api_rpm` 从“只存不用”变成**可观测、可强制**的租户配额体系，并补齐 k3s 数据库备份/恢复脚本。

| # | 目标 | 验收 |
|---|---|---|
| G1 | 席位配额强制：创建/导入账号超过 `quota_seats` 拒绝 | 409 `QUOTA_EXCEEDED` |
| G2 | 组织配额：新增 `quota_org_units` 并强制 | 409 |
| G3 | 配额/用量查询 API + 控制台展示 | 已用/上限/剩余/百分比 |
| G4 | API RPM 配额：网关按租户限流（基于 `quota_api_rpm`） | 429 |
| G5 | k3s PostgreSQL 备份/恢复脚本 | 4 库备份 + 可恢复校验 |
| G6 | E2E + 文档 | `tests/e2e-v7.ps1` 全绿 |

## 2. 数据模型（tenant-service V8）

```sql
ALTER TABLE tenants ADD COLUMN quota_org_units INT;
-- 回填历史数据：默认 50
UPDATE tenants SET quota_org_units = 50 WHERE quota_org_units IS NULL;
```
- 现有 `quota_seats`、`quota_storage_gb`、`quota_api_rpm` 保留；
- 配额为 `NULL` 表示不限；`<=0` 视为不限（兼容旧数据）。

## 3. API

```
GET  /api/tenants/{tenantId}/quota
→ {
    tenantId, plan,
    seats:   {limit, used, remaining, percent},
    orgUnits:{limit, used, remaining, percent},
    storageGb:{limit, used(null), remaining(null)},
    apiRpm:  {limit},
    status:  "OK" | "WARNING" | "EXCEEDED"
  }
  权限：平台管理员 / 租户管理员 / 审计员只读；ORG_ADMIN/MEMBER 403

PUT  /api/tenants/{tenantId}   # 已有租户更新接口增加 quotaOrgUnits 字段
```

用量口径：
- `usedSeats` = 未删除账号数（`deleted_at IS NULL`，含 DISABLED/LOCKED）；
- `usedOrgUnits` = 未删除组织数；
- `usedStorageGb` 暂不采集，返回 null（文档标注）。

## 4. 强制点

| 入口 | 规则（实测 HTTP 409 / body code 42900，message `QUOTA_EXCEEDED`） |
|---|---|
| `POST /api/tenants/{tid}/accounts` | `usedSeats >= limit` → 409 `QUOTA_EXCEEDED`，message 含 used/limit |
| `POST /api/tenants/{tid}/accounts/import` | 逐行检查；超限行记为 failed（不中断整批） |
| `POST /api/tenants/{tid}/org-units` | `usedOrgUnits >= limit` → 409 |
| `POST /api/tenants/{tid}/org-units/import` | 超限行 failed |
| 网关所有租户 JWT 的 `/api/**` 代理请求 | 按 `quota_api_rpm` 滑动窗口限流（默认不启用）；超限 429/42901 |

并发安全：创建前在同一事务内统计；极端并发允许轻微越界（记录为已知限制），生产应使用数据库约束/计费系统。

## 5. 网关 RPM 实现

- 网关启动/首次请求时通过内部接口 `GET /internal/tenants/{id}/quota` 获取 `quota_api_rpm`（`X-Internal-Token`），缓存 60 秒；
- 内存滑动窗口 key=`tenantId`，窗口 60s；超过 limit → HTTP 429 `{code:42901,message:"tenant api quota exceeded"}`；
- 仅对携带 `tid` 的 JWT 生效；平台管理员不计数；
- 多副本各自计数（已知限制，生产建议 Redis）。

## 6. 前端

- 租户详情「概览」新增配额卡片：席位/组织/存储/API RPM，进度条 + 百分比 + 状态色（OK/WARNING/EXCEEDED）；
- 新建/编辑租户表单增加 `quotaOrgUnits` 字段；
- 创建账号/组织失败时展示后端配额错误信息。

## 7. 备份与恢复（deploy/k8s）

```
deploy/k8s/backup.sh [--out DIR] [--keep N]
  → pg_dump -Fc 四个库（tenant_db/approval_db/agent_db/mcp_db）到
    backup/YYYYmmdd-HHMMSS/{db}.dump + manifest.txt
deploy/k8s/restore.sh --from <dir> [--db all|tenant_db,...] [--yes]
  → pg_restore --clean --if-exists（需 --yes 防误操作）
```
- 通过 `kubectl exec deploy/postgres -- pg_dump/pg_restore` 执行；
- manifest 记录版本/时间/数据库/大小/校验和（sha256）；
- 文档：备份频率建议、恢复演练、异地存储（S3/MinIO）说明。

## 8. CLI

- `jiuding tenant quota <tenantId>`（table/json）；
- `jiuding tenant usage` 同上（别名）；
- 备份/恢复不在 CLI 内（属运维脚本）。

## 9. 验收

- 后端：`mvn -B -f tenant-service/pom.xml test` 全绿（新增配额/用量测试）；
- 网关：租户 RPM 超限 429 测试；
- E2E：`tests/e2e-v7.ps1`（配额强制/用量/更新/导入超限/网关 429/SKIP 兜底）；
- 前端 `npm run build` 0 错误；备份脚本 `sh -n` + 实际备份/恢复校验（可选）。
