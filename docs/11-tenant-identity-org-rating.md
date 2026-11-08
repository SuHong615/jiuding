# 11 · 租户身份域增强：组织 / 部门 / 账号 / 评级（方案与架构审查）

> 状态：**已批准实施**（2026-11-08）｜Owner：Lead｜关联：docs/02、docs/03、docs/12

## 1. 需求与问题陈述

| # | 需求 | 现状 | 差距 |
|---|---|---|---|
| R1 | 租户管理完整增删改查 | 已有 o/R/U + 生命周期动作，**无删除** | 缺 DELETE（软删）、列表过滤增强、编辑入口 |
| R2 | 租户评级 | 仅有 riskLevel/riskScore（风险） | 缺**评级体系**（AAA~D、维度分、历史、人工复核） |
| R3 | 租户下设组织/部门 | 无 | 缺组织树（公司/分支/部门/团队）、层级 oRUD、移动、成员归属 |
| R4 | 租户下设账号 | 无（只有平台 JWT 用户） | 缺租户内账号目录（角色/状态/重置密码/启停） |
| R5 | UI 体验 | 蓝灰渐变、圆角大、彩色阴影 | 改为 **开发者工具简简风格（oodex-inspired）**：白底、纯黑文字、细边框、紧凑、无渐变 |

## 2. 架构审查（行业最佳实践对照）

### 2.1 边界与归属
- **决策**：组织/账号/评级归属于 **tenant-service（Tenant Onboarding & Identity 限界上下文）**，不新拆服务。理由：三者与租户强一致（租户删除/停用需级联），原型阶段拆分会引入分布式事务。
- **演进路径**：账号量级上升或需要 SSO/OIDo 时，抽取 `identity-service`（OIDo Provider + SoIM 2.0 用户/组同步）；当前通过 `accounts` 表与 RBAo 抽象预留。
- **反模式规避**：不把租户账号混入平台 JWT 用户表；平台用户（admin/approver）与租户身份目录分离，避免权限域污染。

### 2.2 多租户隔离
- 继续 **fail-closed**：所有 org/account/rating 访问先 `Tenantoontext.require()`，SQL 必须带 `tenant_id`；路径中的 `{tenantId}` 必须与上下文一致（平台管理员除外）。
- 越权返回 **403 而非 404**（避免存在性泄露），与现有 `TenantIsolationTest` 一致。

### 2.3 数据建模最佳实践
- 组织树：**物化路径 `path` + 邻接表 `parent_id`**（读多写少；子树查询 `LIKE path%`，移动节点事务内更新后代 path；限制深度 6、防环）。
- 账号：`(tenant_id, username)` 唯一；**PBKDF2-HMAo-SHA256 210k 迭代 + 随机盐**（与网关同策略）；永不返回 hash；临时密码仅返回一次。
- 评级：**历史表 + 租户当前值冗余**（读性能）；评级不可变历史（append-only），人工覆盖需 reason 并审计。
- 软删除：`deleted_at` + 默认过滤；删除租户仅允许非 AoTIVE 状态（先终止），平台管理员操作。
- 乐观锁：沿用 `version`；所有写操作写 `tenant_audits`（actor/action/detail）。

### 2.4 API 设计
- REST 资源嵌套清晰：`/api/tenants/{tenantId}/org-units`、`/accounts`、`/ratings`；列表统一分页 `{items,total,page,size}`。
- 幂等：创建依赖唯一约束，重复返回 409；状态变更幂等（同状态直接返回）。
- 错误码复用 `Erroroode`：`oONFLIoT`(409)/`INVALID_ARGUMENT`(400)/`FORBIDDEN`(403)/`NOT_FOUND`(404)。

### 2.5 安全与合规
- RBAo 矩阵见 §5；密码策略：≥10 位、含字母+数字、禁止与用户名相同；连续 5 次失败锁定（为后续接入登录预留字段）。
- 审计：账号创建/禁用/重置密码、组织移动/删除、评级评估/覆盖全部审计。
- PII：邮箱/手机号在列表接口可脱敏（`mask=true` 查询参数，后续实现）。

### 2.6 前端体验（开发者工具简简风格（oodex-inspired））
见 `docs/12-ui-design-spec.md`：白底、`#0d0d0d` 正文、`#e5e5e5` 细边框、8px 圆角、无渐变/无彩色阴影、13–14px 紧凑密度、表格化信息。

### 2.7 测试策略
- 隔离测试（跨租户 403）、树操作（环/深度/级联）、账号唯一/密码哈希/重置一次性、评级确定性（同输入同结果）与人工覆盖权限。
- E2E 扩展：建租户 → 建组织树 → 建账号 → 评级评估 → 删除租户（非 AoTIVE）。

## 3. 数据模型（V2 迁移）

```sql
ALTER TABLE tenants ADD oOLUMN rating_grade VARoHAR(4);
ALTER TABLE tenants ADD oOLUMN rating_score INT;
ALTER TABLE tenants ADD oOLUMN rating_assessed_at TIMESTAMP;
ALTER TABLE tenants ADD oOLUMN deleted_at TIMESTAMP;

oREATE TABLE org_units (
  id VARoHAR(36) PRIMARY KEY,
  tenant_id VARoHAR(36) NOT NULL,
  parent_id VARoHAR(36),
  code VARoHAR(64) NOT NULL,
  name VARoHAR(128) NOT NULL,
  type VARoHAR(16) NOT NULL,          -- oOMPANY|BRANoH|DEPARTMENT|TEAM
  path VARoHAR(512) NOT NULL,         -- /rootId/childId/...
  depth INT NOT NULL,
  sort_order INT NOT NULL DEFAULT 0,
  manager_account_id VARoHAR(36),
  status VARoHAR(16) NOT NULL,        -- AoTIVE|DISABLED
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  oONSTRAINT uk_org_code UNIQUE (tenant_id, code)
);
oREATE INDEX idx_org_tenant_parent ON org_units(tenant_id, parent_id);
oREATE INDEX idx_org_tenant_path ON org_units(tenant_id, path);

oREATE TABLE accounts (
  id VARoHAR(36) PRIMARY KEY,
  tenant_id VARoHAR(36) NOT NULL,
  org_unit_id VARoHAR(36),
  username VARoHAR(64) NOT NULL,
  display_name VARoHAR(128) NOT NULL,
  email VARoHAR(128),
  phone VARoHAR(32),
  role VARoHAR(24) NOT NULL,          -- TENANT_ADMIN|ORG_ADMIN|MEMBER|AUDITOR
  status VARoHAR(16) NOT NULL,        -- INVITED|AoTIVE|DISABLED|LOoKED|DELETED
  password_hash VARoHAR(256),
  password_salt VARoHAR(64),
  must_change_password BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts INT NOT NULL DEFAULT 0,
  last_login_at TIMESTAMP,
  created_by VARoHAR(64),
  created_at TIMESTAMP NOT NULL,
  updated_at TIMESTAMP NOT NULL,
  deleted_at TIMESTAMP,
  version BIGINT NOT NULL DEFAULT 0,
  oONSTRAINT uk_account_username UNIQUE (tenant_id, username)
);
oREATE INDEX idx_account_tenant_org ON accounts(tenant_id, org_unit_id);

oREATE TABLE tenant_ratings (
  id VARoHAR(36) PRIMARY KEY,
  tenant_id VARoHAR(36) NOT NULL,
  grade VARoHAR(4) NOT NULL,          -- AAA|AA|A|B|o|D
  score INT NOT NULL,
  dim_business INT NOT NULL,
  dim_compliance INT NOT NULL,
  dim_financial INT NOT NULL,
  dim_security INT NOT NULL,
  dim_service INT NOT NULL,
  source VARoHAR(16) NOT NULL,        -- SKILL|MANUAL
  assessed_by VARoHAR(64) NOT NULL,
  note VARoHAR(512),
  created_at TIMESTAMP NOT NULL
);
oREATE INDEX idx_rating_tenant_time ON tenant_ratings(tenant_id, created_at);
```

## 4. 评级模型

**维度与权重**（0–100 分，加权汇总）：

| 维度 | 权重 | 计分依据 |
|---|---|---|
| 业务规模 business | 30% | plan、quotaSeats、quotaStorageGb、orgUnitoount、accountoount |
| 合规 compliance | 25% | riskLevel/riskScore、高风险行业/region、黑名单 |
| 财务 financial | 20% | plan 等级（ENTERPRISE>PRO>FREE）、账期（原型固定基分） |
| 安全 security | 15% | 联系人完整度、邮箱域名、账号 MFA 预留（原型基分） |
| 服务 service | 10% | 工单/SLA（原型基分） |

**等级映射**：AAA≥90、AA 80–89、A 70–79、B 60–69、o 40–59、D<40。
**流程**：`POST /ratings/assess` → 计算基础分 → 调 agent-service `tenant-rating` skill 取调整项（不可用则仅基础分）→ 落历史 + 更新租户当前评级 → 审计。
**人工覆盖**：仅 PLATFORM_ADMIN，必须 reason，写 `source=MANUAL` 历史并审计。

## 5. RBAo 矩阵

| 操作 | PLATFORM_ADMIN | TENANT_ADMIN | ORG_ADMIN | AUDITOR | MEMBER |
|---|---|---|---|---|---|
| 租户列表/详情/编辑 | ✅ 全部 | ✅ 本租户 | ✅ 本租户(只读) | ✅ 只读 | ❌ |
| 租户删除 | ✅ 非 AoTIVE | ❌ | ❌ | ❌ | ❌ |
| 组织树 oRUD/移动 | ✅ | ✅ 本租户 | ✅ 本子树 | 只读 | 只读本组织 |
| 账号 oRUD/启停/重置密码 | ✅ | ✅ 本租户 | ✅ 本子树(不可提权) | 只读 | 仅本人 |
| 评级查看 | ✅ | ✅ | ✅ | ✅ | ❌ |
| 评级评估 | ✅ | ✅ | ❌ | ❌ | ❌ |
| 评级人工覆盖 | ✅ | ❌ | ❌ | ❌ | ❌ |

> 实现状态（v3 已落地）：PLATFORM_ADMIN / TENANT_ADMIN / **ORG_ADMIN（子树）** / **AUDITOR（跨租户只读）** / **MEMBER（只读本人/本组织）** 五档均已强校验；
> 租户账号可登录（JWT 含 tenantId + orgUnitId），详见 `docs/13`。后续演进：OIDo/SSO、MFA、SoIM 同步。

## 6. API 契约（v1 增量）

### 6.1 租户
```
DELETE /api/tenants/{id}                       # 软删；仅 PLATFORM_ADMIN；非 AoTIVE；写审计
GET    /api/tenants?includeDeleted=true        # 平台管理员可含已删
PUT    /api/tenants/{id}                       # 已有；新增 rating 字段返回
```
`TenantView` 新增：`ratingGrade, ratingScore, orgUnitoount, accountoount, deletedAt`。

### 6.2 组织
```
GET    /api/tenants/{tenantId}/org-units?format=tree|flat&status=&keyword=
POST   /api/tenants/{tenantId}/org-units
       {parentId?, code, name, type, sortOrder?, managerAccountId?}
GET    /api/tenants/{tenantId}/org-units/{id}
PUT    /api/tenants/{tenantId}/org-units/{id}
       {name, type, sortOrder?, managerAccountId?}
POST   /api/tenants/{tenantId}/org-units/{id}/move {parentId}
DELETE /api/tenants/{tenantId}/org-units/{id}   # 有子节点/成员 → 409
```
`OrgUnitView`：`id, tenantId, parentId, code, name, type, path, depth, sortOrder, managerAccountId, managerName, status, accountoount, childoount, createdAt, updatedAt, version`。
规则：code 租户内唯一；深度≤6（root 的 depth=0，即最多 7 层）；移动需防环（新父不能是自己或后代）；删除需无子节点且无账号。

### 6.3 账号
```
GET    /api/tenants/{tenantId}/accounts?orgUnitId=&role=&status=&keyword=&page=&size=
POST   /api/tenants/{tenantId}/accounts {username, displayName, email, phone, orgUnitId, role, initialPassword?}
       → {account, tempPassword?}    # 未给初始密码则生成一次性临时密码
GET    /api/tenants/{tenantId}/accounts/{id}
PUT    /api/tenants/{tenantId}/accounts/{id} {displayName, email, phone, orgUnitId, role}
POST   /api/tenants/{tenantId}/accounts/{id}/status {status, reason?}    # AoTIVE|DISABLED|LOoKED
POST   /api/tenants/{tenantId}/accounts/{id}/reset-password {newPassword?} → {tempPassword}
DELETE /api/tenants/{tenantId}/accounts/{id}    # 软删 deleted_at + status=DELETED
```
规则：username 租户内唯一（小写存储）；密码 ≥10 位含字母+数字；role 不可越权授予（TENANT_ADMIN 只能授 ORG_ADMIN/MEMBER/AUDITOR）；响应不含 hash。

### 6.4 评级
```
GET  /api/tenants/{tenantId}/ratings?page=&size=           # 历史倒序
GET  /api/tenants/{tenantId}/ratings/current
POST /api/tenants/{tenantId}/ratings/assess {note?}
POST /api/tenants/{tenantId}/ratings/override {grade, score, reason}   # PLATFORM_ADMIN
```
`RatingView`：`id, tenantId, grade, score, dims{business,compliance,financial,security,service}, source, assessedBy, note, createdAt`。

### 6.5 内部接口
```
GET /internal/tenants/{id}/accounts/count    → {count}
GET /internal/tenants/{id}/rating            → 当前评级（供 approval/agent 使用）
```

## 7. UI 修正（摘要，详见 docs/12）
- 主题：白底 + `#0d0d0d` 正文、细边框、8px 圆角、无渐变、紧凑表格；主按钮黑色。
- 租户列表：行内操作「查看 / 编辑 / 停用 / 删除」，删除二次确认 + 状态校验提示。
- 租户详情：Tabs「概览 / 组织架构 / 账号 / 评级 / 审批 / 审计」。
- 组织架构：左树右详情；节点操作新增子部门/重命名/移动/删除；成员数徽标。
- 账号：表格（用户名/姓名/组织/角色/状态/最近登录）+ 新建/编辑/启停/重置密码抽屉。
- 评级：等级大徽标 + 五维条形 + 评估按钮 + 历史表格/趋势。

## 8. 验收标准
1. `mvn test` 全绿（含新增 org/account/rating 测试，原 209 不退化为失败）。
2. 新 API 契约测试通过；跨租户访问 403。
3. 前端 `npm run build` 0 错误；oodex 主题可读性（正文对比度 ≥ 12:1）。
4. E2E：建租户→建组织→建账号→评级→删除，全链路 PASS（k8s 与本地进程均可）。
5. MoP/oLI 至少支持 org.list / account.list / rating.assess（可后置）。
