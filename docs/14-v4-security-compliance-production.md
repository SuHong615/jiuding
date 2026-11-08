# 14 · v4：账号安全与合规、评级增量、生产化

> 状态：**已完成并验收**（2026-11-08）｜Owner：Lead｜关联：docs/11、docs/13、docs/02
> 验收：**328 自动化测试全绿**；`tests/e2e-v4.ps1` **20/20 PASS**；k3s 生产 overlay（2 副本/PDB/HPA/CronJob）生效；滚动重启 **0 失败**；CronJob 手动触发 `refreshed=14, skipped=4, failed=0`。

## 1. 目标

v3 已实现租户账号登录与五档 RBAC。v4 补齐企业落地所需的**安全运营、合规审计与生产化部署**：

| # | 目标 | 验收 |
|---|---|---|
| G1 | 账号锁定可解锁、本人可改密、登录审计（IP/UA/结果） | E2E 实测 |
| G2 | 审计导出 CSV、PII 掩码 | E2E + 内容断言 |
| G3 | 评级增量（delta / 等级变化审计） | 测试 + UI 展示 |
| G4 | 登录限流（防爆破） | 第 11 次 429 |
| G5 | k8s 生产化：多副本 + PDB + HPA + CronJob 评级刷新 + 滚动零中断 | 实测 |

## 2. 数据模型（tenant-service V3 迁移）

```sql
CREATE TABLE account_login_events (
  id VARCHAR(36) PRIMARY KEY,
  tenant_id VARCHAR(36) NOT NULL,
  account_id VARCHAR(36),
  username VARCHAR(64) NOT NULL,
  at TIMESTAMP NOT NULL,
  ip VARCHAR(64),
  user_agent VARCHAR(256),
  success BOOLEAN NOT NULL,
  reason VARCHAR(64)
);
CREATE INDEX idx_login_events_tenant_time ON account_login_events(tenant_id, at);
CREATE INDEX idx_login_events_account_time ON account_login_events(account_id, at);
```

## 3. API 增量（tenant-service）

### 3.1 账号安全
```
POST /api/tenants/{tenantId}/accounts/{accountId}/unlock
     → AccountView（status ACTIVE、failed_attempts=0）；审计 ACCOUNT_UNLOCKED
     权限：PLATFORM_ADMIN / TENANT_ADMIN / ORG_ADMIN（子树）
POST /api/tenants/{tenantId}/accounts/{accountId}/change-password
     {oldPassword, newPassword} → AccountView
     权限：仅本人（JWT sub=accountId）；校验旧密码 + 密码策略；mustChangePassword=false；审计 PASSWORD_CHANGED
GET  /api/tenants/{tenantId}/accounts/{accountId}/login-events?page=&size=
     → PageResult<{at, ip, userAgent, success, reason}>
     权限：PLATFORM_ADMIN / TENANT_ADMIN / AUDITOR / ORG_ADMIN（子树）/ 本人
```

### 3.2 合规
```
GET /api/tenants/{tenantId}/audits/export?from=&to=&format=csv
     → text/csv; attachment; columns: at,actor,action,detail（最多 10000 行，CSV 转义）
     from/to 为 ISO-8601；缺省最近 30 天
GET /api/tenants/{tenantId}/accounts?mask=true
GET /api/tenants/{tenantId}/accounts/{id}?mask=true
     → email/phone 掩码（a***@domain / 138****1234）；AUDITOR 强制掩码
```

### 3.3 评级增量
- `RatingView` 增加 `previousGrade/previousScore/scoreDelta/gradeChanged`；
- assess 时若等级变化 → 审计 `RATING_GRADE_CHANGED`（old→new）；
- 历史列表按时间倒序计算连续两条 delta。

### 3.4 内部接口
```
POST /internal/ratings/refresh           # X-Internal-Token；触发 refreshActive，供 k8s CronJob 调用
→ {refreshed, skipped, failed}
```

## 4. 网关登录限流

- 内存滑动窗口：key = `clientIp + username`，默认 10 次/60s（env `TENANCY_LOGIN_RATE_LIMIT` / `TENANCY_LOGIN_RATE_WINDOW_SECONDS`）；
- 超限返回 HTTP **429** + `ErrorCode.QUOTA_EXCEEDED(42900)`，message `too many login attempts, retry later`；
- 仅作用于 `POST /api/auth/login`；
- 登录请求转发 tenant-service 时携带 `X-Forwarded-For`（客户端 IP）与 `User-Agent`；tenant-service 记录到 `account_login_events`。

## 5. 前端（v4）

- 账号页：LOCKED 行显示「解锁」；本人登录后可「修改密码」；新增「登录历史」抽屉（时间/IP/UA/结果）；
- 合规：审计页「导出 CSV」按钮（浏览器下载）；账号列表「脱敏」开关；
- 评级页：历史/当前显示 delta（↑/↓/—）与等级变化标记。

## 6. CLI（v4）

- `tenancy account unlock|change-password|login-events --tenant <id>`
- `tenancy audit export --tenant <id> --from --to --out <file.csv>`
- `tenancy rating current` 显示 delta。

## 7. Kubernetes 生产化

- 基础清单保持单副本；新增 **kustomize 生产 overlay**（`deploy/k8s/overlays/prod`）：
  - 无状态服务 replicas=2（tenant/approval/agent/mcp/gateway）；frontend 2；
  - PodDisruptionBudget `minAvailable=1`；
  - HPA（gateway/tenant，CPU 70%）；
  - 首选反亲和（同 app 分散节点）；
  - 关闭服务内评级定时器（`TENANCY_RATING_AUTO_REFRESH_ENABLED=false`），改用 **CronJob**（每日 03:00）调用 `/internal/ratings/refresh`；
- `deploy/k8s/deploy.sh` 支持 `--profile prod`；
- 零中断验证脚本：rollout restart 期间持续请求 gateway，断言无失败。

## 8. 验收标准

- 全量测试 ≥ 320 全绿；
- v4 E2E：错误口令锁定 → 管理员解锁 → 本人改密 → 登录历史含 IP/UA → 审计 CSV 含动作 → 掩码生效 → 评级 delta → 网关第 11 次登录 429；
- k8s prod overlay：replicas=2、PDB/HPA 生效、CronJob 成功执行、滚动期间 0 失败；
- 文档更新：docs/02、docs/10、docs/13、README。
