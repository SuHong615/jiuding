/*
 * Copyright 2026 suhong
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * @author suhong
 */
// 与 docs/02-api-contracts.md、docs/03-data-model.md、docs/11-tenant-identity-org-rating.md §6
// 对齐的前端类型定义。后端字段缺失一律按可选处理，页面统一做空值降级展示。

export interface ApiEnvelope<T> {
  code: number
  message: string
  data: T
  traceId?: string | null
  timestamp?: string
}

export interface Principal {
  userId: string
  username: string
  tenantId: string | null
  roles: string[]
  // docs/13 §2.3：租户账号登录后新增字段（平台账号可为空）
  orgUnitId?: string | null
  orgUnitPath?: string | null
  mustChangePassword?: boolean
  authType?: 'PLATFORM' | 'TENANT' | string
}

export interface LoginRequest {
  username: string
  password: string
  /** 可选；为空时按平台账号登录，填写后按租户成员登录 */
  tenantCode?: string
}

export interface LoginResult {
  token: string
  tokenType: string
  expiresIn: number
  principal: Principal
  // docs/16 §3：MFA 登录响应
  mfaRequired?: boolean
  challengeToken?: string
  username?: string
  // docs/20：无 tenantCode 时多租户匹配返回租户选择列表
  tenantSelectionRequired?: boolean
  tenants?: AuthTenantOption[]
}

export interface AuthTenantOption {
  tenantId?: string
  tenantCode: string
  name?: string
}

export interface OidcProvider {
  code: string
  name: string
  loginUrl?: string | null
}

export interface LoginMfaRequest {
  challengeToken: string
  code: string
}

export interface OidcStatus {
  enabled: boolean
  providerName?: string | null
  loginUrl?: string | null
}

export interface MfaStatus {
  enabled: boolean
  confirmedAt?: string | null
}

export interface MfaSetupResult {
  secret: string
  otpauthUrl: string
  recoveryCodes: string[]
}

export interface MfaVerifyResult {
  enabled: boolean
}

/* ------------------------------ v6：SCIM Token（docs/16 §7.1） ------------------------------ */

export interface ScimTokenView {
  id: string
  tenantId: string
  name: string
  tokenPrefix?: string | null
  prefix?: string | null
  status: string
  createdBy?: string | null
  createdAt?: string | null
  lastUsedAt?: string | null
  revokedAt?: string | null
}

export interface ScimTokenCreateRequest {
  tenantId: string
  name: string
}

export interface ScimTokenCreateResult {
  id: string
  token: string
  prefix: string
  tenantId?: string
  name?: string
  createdAt?: string | null
}

/* ------------------------------ v10：平台账号 / SSO Provider / 工作流（docs/20） ------------------------------ */

export interface PlatformUserView {
  id: string
  username: string
  displayName?: string | null
  email?: string | null
  roles: string[]
  status: string
  lastLoginAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface PlatformUserCreateRequest {
  username: string
  displayName?: string
  email?: string
  roles: string[]
  password: string
}

export interface PlatformUserUpdateRequest {
  displayName?: string
  email?: string
  roles?: string[]
  status?: string
}

export interface PlatformUserResetResult {
  tempPassword?: string | null
}

export interface SsoProviderView {
  id: string
  code: string
  name: string
  issuer?: string | null
  clientId?: string | null
  clientSecret?: string | null
  scopes?: string | null
  redirectUri?: string | null
  frontendRedirect?: string | null
  roleClaim?: string | null
  defaultRole?: string | null
  enabled?: boolean
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SsoProviderPayload {
  code: string
  name: string
  issuer?: string
  clientId?: string
  clientSecret?: string
  scopes?: string
  redirectUri?: string
  frontendRedirect?: string
  roleClaim?: string
  defaultRole?: string
  enabled?: boolean
}

export interface SsoProviderTestResult {
  ok?: boolean
  status?: string
  message?: string
  authorizationEndpoint?: string
  issuer?: string
}

export interface WorkflowStageView {
  stage: string
  label: string
  description?: string | null
  activities?: string[]
  skills?: string[]
  agents?: string[]
  outputs?: string[]
}

export interface WorkflowLifecycleView {
  stages?: WorkflowStageView[]
  updatedAt?: string | null
}

/** 列表分页：兼容 Spring Page（content/totalElements）与 docs/11 的 {items,total,page,size} */
export interface PageResult<T> {
  content?: T[]
  items?: T[]
  total?: number
  totalElements?: number
  totalPages?: number
  number?: number
  page?: number
  size?: number
}

export function pageItems<T>(page?: PageResult<T> | T[] | null): T[] {
  if (!page) return []
  if (Array.isArray(page)) return page
  if (Array.isArray(page.items)) return page.items
  if (Array.isArray(page.content)) return page.content
  return []
}

export function pageTotal(page?: PageResult<unknown> | unknown[] | null): number {
  if (!page) return 0
  if (Array.isArray(page)) return page.length
  return page.total ?? page.totalElements ?? pageItems(page as PageResult<unknown>).length
}

/* ------------------------------ 租户 ------------------------------ */

export type TenantStatus =
  | 'DRAFT'
  | 'SUBMITTED'
  | 'RISK_REVIEW'
  | 'PENDING_APPROVAL'
  | 'APPROVED'
  | 'PROVISIONING'
  | 'ACTIVE'
  | 'REJECTED'
  | 'SUSPENDED'
  | 'TERMINATED'

export type RatingGrade = 'AAA' | 'AA' | 'A' | 'B' | 'C' | 'D'

export interface TenantView {
  id: string
  tenantCode: string
  name: string
  contactName?: string | null
  contactEmail?: string | null
  contactPhone?: string | null
  plan: string
  region: string
  isolationMode: string
  status: string
  riskLevel?: string | null
  riskScore?: number | null
  quotaSeats?: number | null
  quotaOrgUnits?: number | null
  quotaStorageGb?: number | null
  quotaApiRpm?: number | null
  source?: string
  rejectReason?: string | null
  // docs/11 §6.1 新增字段
  ratingGrade?: string | null
  ratingScore?: number | null
  ratingAssessedAt?: string | null
  orgUnitCount?: number | null
  accountCount?: number | null
  deletedAt?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  submittedAt?: string | null
  decidedAt?: string | null
  activatedAt?: string | null
  version?: number
}

export interface TenantAudit {
  at?: string | null
  actor?: string | null
  action?: string | null
  detail?: string | null
}

/* ------------------------------ v8：租户导出 / 离户（docs/18 §2/§3） ------------------------------ */

export interface TenantExportPayload {
  exportedAt?: string
  tenant?: TenantView
  orgUnits?: OrgUnitView[]
  accounts?: AccountView[]
  rating?: {
    current?: RatingCurrentView | null
    history?: RatingView[]
  }
  audits?: TenantAudit[]
}

export interface TenantOffboardRequest {
  reason: string
}

/* ------------------------------ v11：平台审计中心（docs/21） ------------------------------ */

export interface PlatformAuditView {
  id?: string
  tenantId?: string
  tenantCode?: string | null
  tenantName?: string | null
  at?: string | null
  actor?: string | null
  action?: string | null
  detail?: string | null
}

export interface TenantOverview {
  total?: number
  byStatus?: Record<string, number>
  byPlan?: Record<string, number>
  byRisk?: Record<string, number>
  active?: number
  pending?: number
}

export interface TenantCreatePayload {
  tenantCode: string
  name: string
  contactName?: string | null
  contactEmail?: string | null
  contactPhone?: string | null
  plan?: string
  region?: string
  isolationMode?: string
  quotaSeats?: number | null
  quotaOrgUnits?: number | null
  quotaStorageGb?: number | null
  quotaApiRpm?: number | null
  source?: string
  attributes?: Record<string, unknown> | null
  autoSubmit?: boolean
}

export interface CollectJobView {
  id: string
  mode: string
  sourceName?: string | null
  status: string
  total: number
  imported: number
  duplicated: number
  failed: number
  errors?: string[] | null
  createdAt?: string | null
  finishedAt?: string | null
}

/* ------------------------------ 配额与用量（docs/17 §3） ------------------------------ */

export interface QuotaDimension {
  limit?: number | null
  used?: number | null
  remaining?: number | null
  percent?: number | null
}

export type QuotaStatus = 'OK' | 'WARNING' | 'EXCEEDED'

export interface QuotaView {
  tenantId?: string
  plan?: string | null
  seats?: QuotaDimension
  orgUnits?: QuotaDimension
  storageGb?: QuotaDimension
  apiRpm?: QuotaDimension
  status?: QuotaStatus | string
}

/* ------------------------------ 审批 ------------------------------ */

export interface ApprovalNodeView {
  node: string
  type: string
  state: string
  assigneeRole?: string | null
  decidedBy?: string | null
  decidedAt?: string | null
  comment?: string | null
  slaHours?: number | null
}

export interface TimelineItem {
  at?: string | null
  actor?: string | null
  action?: string | null
  detail?: string | null
}

export interface ApprovalView {
  id: string
  tenantId: string
  tenantCode: string
  plan?: string
  state: string
  currentNode?: string | null
  riskLevel?: string | null
  riskScore?: number | null
  autoDecided?: boolean
  decidedBy?: string | null
  decisionComment?: string | null
  slaDueAt?: string | null
  nodes?: ApprovalNodeView[]
  timeline?: TimelineItem[]
  createdAt?: string | null
  updatedAt?: string | null
  escalated?: boolean
}

export interface ApprovalOverview {
  pending?: number
  autoApproved?: number
  approved?: number
  rejected?: number
  slaBreached?: number
}

export interface DecisionPayload {
  decision: 'APPROVE' | 'REJECT'
  comment?: string
}

/* ------------------------------ 组织架构（docs/11 §6.2） ------------------------------ */

export type OrgUnitType = 'COMPANY' | 'BRANCH' | 'DEPARTMENT' | 'TEAM'
export type OrgUnitStatus = 'ACTIVE' | 'DISABLED'

export interface OrgUnitView {
  id: string
  tenantId?: string
  parentId?: string | null
  code: string
  name: string
  type: string
  path?: string
  depth?: number
  sortOrder?: number
  managerAccountId?: string | null
  managerName?: string | null
  status?: string
  accountCount?: number
  childCount?: number
  createdAt?: string | null
  updatedAt?: string | null
  version?: number
  children?: OrgUnitView[]
}

export interface OrgUnitCreatePayload {
  parentId?: string | null
  code: string
  name: string
  type: string
  sortOrder?: number
  managerAccountId?: string | null
}

export interface OrgUnitUpdatePayload {
  name: string
  type: string
  sortOrder?: number
  managerAccountId?: string | null
}

/* ------------------------------ 账号（docs/11 §6.3） ------------------------------ */

export interface AccountView {
  id: string
  tenantId?: string
  orgUnitId?: string | null
  orgUnitName?: string | null
  username: string
  displayName: string
  email?: string | null
  phone?: string | null
  role: string
  status: string
  mustChangePassword?: boolean
  failedAttempts?: number
  lastLoginAt?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  deletedAt?: string | null
  version?: number
}

export interface AccountCreateRequest {
  username: string
  displayName: string
  email?: string
  phone?: string
  orgUnitId?: string | null
  role: string
  initialPassword?: string
}

export interface AccountUpdateRequest {
  displayName: string
  email?: string
  phone?: string
  orgUnitId?: string | null
  role: string
}

export interface AccountCreateResult {
  account: AccountView
  tempPassword?: string | null
}

export interface AccountResetResult {
  tempPassword?: string | null
}

export interface AccountStatusRequest {
  status: 'ACTIVE' | 'DISABLED' | 'LOCKED'
  reason?: string
}

export interface ChangePasswordRequest {
  oldPassword: string
  newPassword: string
}

/** docs/14 §2/§3.1：账号登录审计事件 */
export interface LoginEventView {
  id?: string
  at?: string | null
  ip?: string | null
  userAgent?: string | null
  success?: boolean
  reason?: string | null
}

/** docs/14 §3.2：审计 CSV 导出查询参数 */
export interface AuditExportQuery {
  from?: string
  to?: string
  format?: 'csv'
}

/* ------------------------------ 评级（docs/11 §6.4） ------------------------------ */

export interface RatingDims {
  business: number
  compliance: number
  financial: number
  security: number
  service: number
}

export interface RatingView {
  id: string
  tenantId?: string
  grade: string
  score: number
  dims?: Partial<RatingDims> | null
  source?: string
  assessedBy?: string | null
  note?: string | null
  createdAt?: string | null
  // docs/14 §3.3：评级增量字段（历史连续两条 delta）
  previousGrade?: string | null
  previousScore?: number | null
  scoreDelta?: number | null
  gradeChanged?: boolean
}

/** GET /ratings/current 的返回结构未在契约中细化，兼容 RatingView 与租户冗余字段两种形态 */
export interface RatingCurrentView {
  id?: string
  tenantId?: string
  grade?: string | null
  score?: number | null
  dims?: Partial<RatingDims> | null
  dimBusiness?: number | null
  dimCompliance?: number | null
  dimFinancial?: number | null
  dimSecurity?: number | null
  dimService?: number | null
  source?: string | null
  assessedBy?: string | null
  note?: string | null
  createdAt?: string | null
  assessedAt?: string | null
  previousGrade?: string | null
  previousScore?: number | null
  scoreDelta?: number | null
  gradeChanged?: boolean
}

export interface RatingAssessRequest {
  note?: string
}

export interface RatingOverrideRequest {
  grade: string
  score: number
  reason: string
}

/* ------------------------------ Skill / Agent ------------------------------ */

export interface Finding {
  ruleId?: string | null
  severity?: string | null
  message?: string | null
  field?: string | null
  value?: unknown
}

export interface SkillAction {
  action?: string | null
  value?: unknown
  message?: string | null
}

export interface TraceItem {
  ruleId?: string | null
  matched?: boolean
  reason?: string | null
  error?: string | null
  skipped?: boolean
}

export interface SkillRunResult {
  skillId?: string
  runId?: string
  verdict?: string
  score?: number
  decision?: string
  findings?: Finding[]
  actions?: SkillAction[]
  trace?: TraceItem[]
  durationMs?: number
  model?: string
}

export interface SkillView {
  id: string
  name: string
  version: string
  type: string
  description?: string | null
  triggers?: string[] | null
  tools?: string[] | null
  enabled?: boolean
  path?: string | null
  // docs/15 §2：技能包管理与审核字段
  source?: string | null // BUILTIN | UPLOADED
  status?: string | null // DRAFT | PENDING_REVIEW | APPROVED | REJECTED | DISABLED
  reviewComment?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

export interface SkillRule {
  id?: string | null
  description?: string | null
  severity?: string | null
  when?: unknown
  then?: unknown
  [key: string]: unknown
}

export interface SkillDetail extends SkillView {
  manifest?: Record<string, unknown> | null
  rules?: SkillRule[] | null
  instructions?: string | null
  rulesYaml?: string | null
}

export interface SkillReviewView {
  id?: string
  skillId?: string
  skillVersion?: string
  action?: string
  actor?: string
  comment?: string | null
  at?: string | null
}

/** docs/15 §12：技能版本列表项 / 版本内容 */
export interface SkillVersionView {
  id?: string
  skillId?: string
  version: string
  status?: string | null
  enabled?: boolean
  source?: string | null
  description?: string | null
  reviewComment?: string | null
  reviewedBy?: string | null
  reviewedAt?: string | null
  createdBy?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  instructions?: string | null
  rulesYaml?: string | null
  rules?: SkillRule[] | null
  manifest?: Record<string, unknown> | null
}

export interface SkillDebugRequest {
  input: unknown
  version?: string
}

export interface SkillMatchedRule {
  ruleId?: string
  rule?: string
  id?: string
  severity?: string
  message?: string
  description?: string
}

export interface SkillDebugResult {
  version?: string
  decision?: string | null
  verdict?: string | null
  score?: number | null
  actions?: SkillAction[]
  matchedRules?: Array<SkillMatchedRule | string>
  durationMs?: number
  notes?: string[]
}

export interface SkillUpdatePayload {
  instructions?: string
  rulesYaml?: string
  description?: string
  version: string
}

export interface AgentStep {
  index: number
  thought?: string | null
  action?: string | null
  actionInput?: string | null
  observation?: string | null
  // docs/15 §3：中文可读标题
  title?: string | null
  actionLabel?: string | null
}

export interface AgentRunView {
  runId: string
  agentType: string
  status: string
  tenantId?: string | null
  input?: unknown
  output?: unknown
  steps?: AgentStep[] | null
  model?: string | null
  tokensUsed?: number
  durationMs?: number
  createdAt?: string | null
  // docs/15 §3：运行可读性
  displayName?: string | null
  purpose?: string | null
  summary?: string | null
}

export interface AgentCatalogItem {
  agentType: string
  displayName: string
  description: string
  inputs?: string[] | null
  outputs?: string[] | null
  exampleInput?: unknown
}

export interface AgentTool {
  name: string
  description?: string | null
  parameters?: Record<string, unknown> | null
}

export interface AgentOverview {
  runs?: number
  todayRuns?: number
  skills?: number
  models?: string | string[]
}

/* ------------------------------ 平台 / MCP ------------------------------ */

export interface ServiceHealth {
  name: string
  url?: string | null
  status: string
  latencyMs?: number | null
}

export interface PlatformOverview {
  tenants?: { total?: number; active?: number; pending?: number }
  approvals?: { pending?: number; autoApproved?: number; rejected?: number }
  skills?: { total?: number }
  agents?: { runs?: number; todayRuns?: number }
  services?: ServiceHealth[]
}

export interface McpTool {
  name: string
  description?: string | null
  inputSchema?: Record<string, unknown> | null
}

export interface McpInfo {
  server?: unknown
  protocolVersion?: string
  tools?: McpTool[]
  resources?: Array<{ uri?: string; name?: string; description?: string }>
  prompts?: Array<{ name?: string; description?: string }>
  apiKeyRequired?: boolean
}

/* ------------------------------ v5：MCP 配置 / 凭据 / 审计 / 外部服务器 ------------------------------ */

export interface MePermissions {
  roles: string[]
  permissions: string[]
}

export interface McpConfig {
  endpoint: string
  transport?: string
  protocolVersion?: string
  sampleConfig?: unknown
  keyHint?: string | null
  docs?: string | null
}

export interface McpApiKey {
  id: string
  name: string
  keyPrefix: string
  status: string // ACTIVE | REVOKED | EXPIRED
  ownerUsername?: string | null
  tenantId?: string | null
  createdAt?: string | null
  expiresAt?: string | null
  lastUsedAt?: string | null
  revokedAt?: string | null
}

export interface McpKeyCreateRequest {
  name: string
  expiresInDays?: number
}

export interface McpKeyCreateResult {
  id: string
  name: string
  keyPrefix: string
  apiKey: string
  expiresAt?: string | null
}

export interface McpCallAudit {
  id: string
  apiKeyId?: string | null
  keyPrefix?: string | null
  ownerUsername?: string | null
  tenantId?: string | null
  tool: string
  requestId?: string | null
  sessionId?: string | null
  success?: boolean
  errorCode?: number | null
  durationMs?: number | null
  ip?: string | null
  userAgent?: string | null
  at?: string | null
}

export interface McpExternalServer {
  id: string
  name: string
  transport: string
  url: string
  headers?: Record<string, string> | string | null
  status?: string | null
  createdBy?: string | null
  createdAt?: string | null
  lastCheckAt?: string | null
  lastCheckOk?: boolean | null
}

export interface McpExternalServerPayload {
  name: string
  transport: string
  url: string
  headers?: Record<string, string> | null
}

export interface McpExternalCheckResult {
  ok?: boolean
  status?: string
  latencyMs?: number
  message?: string
  lastCheckAt?: string | null
}

/* ------------------------------ v5：公开入驻 ------------------------------ */

export interface OnboardingRequest {
  companyName: string
  tenantCode?: string
  contactName: string
  contactEmail: string
  contactPhone?: string
  plan: string
  region?: string
  industry?: string
  notes?: string
}

export interface OnboardingResult {
  applicationNo: string
  tenantCode?: string | null
  status: string
  message?: string | null
}

export interface OnboardingStatusView {
  applicationNo: string
  companyName?: string | null
  tenantCode?: string | null
  plan?: string | null
  status: string
  message?: string | null
  agentDecision?: string | null
  agentRiskLevel?: string | null
  agentScore?: number | null
  createdAt?: string | null
  updatedAt?: string | null
}

/* ------------------------------ v5：组织/账号导入 ------------------------------ */

export interface ImportErrorRow {
  row?: number
  message?: string
}

export interface ImportTempPassword {
  row?: number
  username: string
  tempPassword: string
}

export interface ImportResult {
  total: number
  imported: number
  skipped: number
  failed: number
  errors?: ImportErrorRow[]
  tempPasswords?: ImportTempPassword[]
}

/* ------------------------------ v5：角色与权限 ------------------------------ */

export interface PermissionItem {
  code: string
  name?: string | null
  groupCode?: string | null
  description?: string | null
}

export interface RoleView {
  code: string
  name: string
  scope?: string | null
  builtin?: boolean
  status?: string | null
  description?: string | null
  permissions?: string[] | null
  permissionCodes?: string[] | null
}

export interface RoleCreateRequest {
  code: string
  name: string
  scope: string
  description?: string
}

export interface RolePermissionsRequest {
  permissionCodes: string[]
}
