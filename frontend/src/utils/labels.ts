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
// 中文标签与状态色映射（枚举来自 docs/03-data-model.md 与 docs/02-api-contracts.md）
import type { BadgeTone } from '../components/Badge'

export const TENANT_STATUS_OPTIONS = [
  'DRAFT',
  'SUBMITTED',
  'RISK_REVIEW',
  'PENDING_APPROVAL',
  'APPROVED',
  'PROVISIONING',
  'ACTIVE',
  'REJECTED',
  'SUSPENDED',
  'TERMINATED',
]

const TENANT_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  SUBMITTED: '已提交',
  RISK_REVIEW: '风险审查中',
  PENDING_APPROVAL: '待审批',
  APPROVED: '已批准',
  PROVISIONING: '开通中',
  ACTIVE: '正常',
  REJECTED: '已驳回',
  SUSPENDED: '已停用',
  TERMINATED: '已终止',
}

export const PLAN_OPTIONS = ['FREE', 'PRO', 'ENTERPRISE']
const PLAN_LABELS: Record<string, string> = {
  FREE: '免费版',
  PRO: '专业版',
  ENTERPRISE: '企业版',
}

const RISK_LABELS: Record<string, string> = { LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' }

export const RISK_OPTIONS = ['LOW', 'MEDIUM', 'HIGH']

const APPROVAL_STATE_LABELS: Record<string, string> = {
  RISK_CHECK: '风险检查',
  AUTO_DECISION: '自动决策',
  AUTO_APPROVED: '自动通过',
  PENDING_APPROVAL: '待人工审批',
  APPROVED: '审批通过',
  REJECTED: '已驳回',
  PROVISIONING: '资源开通中',
  ACTIVE: '已生效',
}

export const APPROVAL_STATE_OPTIONS = [
  'RISK_CHECK',
  'AUTO_DECISION',
  'AUTO_APPROVED',
  'PENDING_APPROVAL',
  'APPROVED',
  'REJECTED',
  'PROVISIONING',
  'ACTIVE',
]

const NODE_LABELS: Record<string, string> = {
  RISK_CHECK: '风险检查',
  AUTO_DECISION: '自动决策',
  PENDING_APPROVAL: '人工审批',
  L1_REVIEW: 'L1 审核',
  L2_REVIEW: 'L2 会签',
  APPROVED: '审批通过',
  REJECTED: '审批驳回',
  PROVISIONING: '资源开通',
  ACTIVE: '租户生效',
}

const NODE_STATE_LABELS: Record<string, string> = {
  PENDING: '待处理',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  SKIPPED: '已跳过',
}

const AGENT_TYPE_LABELS: Record<string, string> = {
  'tenant-intake': '租户受理',
  'risk-review': '风险审查',
  approval: '审批助手',
  provisioning: '资源开通',
}

export const AGENT_TYPE_OPTIONS = ['tenant-intake', 'risk-review', 'approval', 'provisioning']

const RUN_STATUS_LABELS: Record<string, string> = {
  RUNNING: '运行中',
  SUCCEEDED: '成功',
  FAILED: '失败',
}

const JOB_STATUS_LABELS: Record<string, string> = {
  PENDING: '等待中',
  RUNNING: '执行中',
  SUCCEEDED: '成功',
  PARTIAL: '部分成功',
  FAILED: '失败',
}

const SKILL_TYPE_LABELS: Record<string, string> = {
  INTAKE: '受理',
  RISK: '风险',
  APPROVAL: '审批',
  PROVISIONING: '开通',
}

const SKILL_TYPE_TONES: Record<string, BadgeTone> = {
  INTAKE: 'cyan',
  RISK: 'amber',
  APPROVAL: 'purple',
  PROVISIONING: 'blue',
}

const VERDICT_LABELS: Record<string, string> = { PASS: '通过', WARN: '警告', FAIL: '失败' }
const DECISION_LABELS: Record<string, string> = {
  AUTO_APPROVE: '自动通过',
  NEEDS_REVIEW: '转人工审核',
  REJECT: '建议驳回',
  NONE: '无',
}
const SOURCE_LABELS: Record<string, string> = {
  MANUAL: '手工创建',
  IMPORT: '批量导入',
  API: 'API 接入',
  DISCOVERY: '自动发现',
}
const ISOLATION_LABELS: Record<string, string> = {
  SHARED_SCHEMA: '共享表（行级隔离）',
  DEDICATED_SCHEMA: '独立 Schema',
  DEDICATED_DB: '独立数据库',
}
const MODE_LABELS: Record<string, string> = {
  MANUAL_BATCH: '手工批量',
  CSV_UPLOAD: 'CSV 上传',
  API_PULL: 'API 拉取',
  DISCOVERY: '自动发现',
}
const ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员',
  APPROVER: '审批人',
  TENANT_ADMIN: '租户管理员',
  ORG_ADMIN: '组织管理员',
  AUDITOR: '审计员',
  MEMBER: '成员',
  AGENT: 'Agent',
}

function fallback(map: Record<string, string>, value?: string | null, blank = '—'): string {
  if (!value) return blank
  return map[value] ?? value
}

export const tenantStatusLabel = (v?: string | null) => fallback(TENANT_STATUS_LABELS, v ?? undefined)
export const planLabel = (v?: string | null) => fallback(PLAN_LABELS, v ?? undefined)
export const riskLabel = (v?: string | null) => fallback(RISK_LABELS, v ?? undefined, '未评估')
export const approvalStateLabel = (v?: string | null) => fallback(APPROVAL_STATE_LABELS, v ?? undefined)
export const nodeLabel = (v?: string | null) => fallback(NODE_LABELS, v ?? undefined)
export const nodeStateLabel = (v?: string | null) => fallback(NODE_STATE_LABELS, v ?? undefined)
export const agentTypeLabel = (v?: string | null) => fallback(AGENT_TYPE_LABELS, v ?? undefined)
export const runStatusLabel = (v?: string | null) => fallback(RUN_STATUS_LABELS, v ?? undefined)
export const jobStatusLabel = (v?: string | null) => fallback(JOB_STATUS_LABELS, v ?? undefined)
export const skillTypeLabel = (v?: string | null) => fallback(SKILL_TYPE_LABELS, v ?? undefined)
export const verdictLabel = (v?: string | null) => fallback(VERDICT_LABELS, v ?? undefined)
export const decisionLabel = (v?: string | null) => fallback(DECISION_LABELS, v ?? undefined)
export const sourceLabel = (v?: string | null) => fallback(SOURCE_LABELS, v ?? undefined)
export const isolationLabel = (v?: string | null) => fallback(ISOLATION_LABELS, v ?? undefined)
export const collectModeLabel = (v?: string | null) => fallback(MODE_LABELS, v ?? undefined)
export const roleLabel = (v?: string | null) => fallback(ROLE_LABELS, v ?? undefined)

export const skillTypeTone = (v?: string | null): BadgeTone =>
  (v ? SKILL_TYPE_TONES[v] : undefined) ?? 'gray'

export function tenantStatusTone(status?: string | null): BadgeTone {
  switch (status) {
    case 'ACTIVE':
    case 'APPROVED':
      return 'green'
    case 'PENDING_APPROVAL':
    case 'RISK_REVIEW':
    case 'SUBMITTED':
      return 'amber'
    case 'PROVISIONING':
      return 'blue'
    case 'REJECTED':
    case 'TERMINATED':
      return 'red'
    case 'SUSPENDED':
    case 'DRAFT':
    default:
      return 'gray'
  }
}

export function planTone(plan?: string | null): BadgeTone {
  if (plan === 'ENTERPRISE') return 'purple'
  if (plan === 'PRO') return 'blue'
  return 'gray'
}

export function riskTone(risk?: string | null): BadgeTone {
  if (risk === 'HIGH') return 'red'
  if (risk === 'MEDIUM') return 'amber'
  if (risk === 'LOW') return 'green'
  return 'gray'
}

export function approvalStateTone(state?: string | null): BadgeTone {
  switch (state) {
    case 'AUTO_APPROVED':
    case 'APPROVED':
    case 'ACTIVE':
      return 'green'
    case 'PENDING_APPROVAL':
      return 'amber'
    case 'REJECTED':
      return 'red'
    case 'PROVISIONING':
    case 'AUTO_DECISION':
      return 'blue'
    default:
      return 'gray'
  }
}

export function nodeStateTone(state?: string | null): BadgeTone {
  if (state === 'APPROVED') return 'green'
  if (state === 'REJECTED') return 'red'
  if (state === 'SKIPPED') return 'gray'
  return 'amber'
}

export function healthTone(status?: string | null): BadgeTone {
  if (status === 'UP') return 'green'
  if (status === 'DOWN') return 'red'
  return 'gray'
}

export function runStatusTone(status?: string | null): BadgeTone {
  if (status === 'SUCCEEDED') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'RUNNING') return 'blue'
  return 'gray'
}

export function jobStatusTone(status?: string | null): BadgeTone {
  if (status === 'SUCCEEDED') return 'green'
  if (status === 'PARTIAL') return 'amber'
  if (status === 'FAILED') return 'red'
  if (status === 'RUNNING') return 'blue'
  return 'gray'
}

export function verdictTone(verdict?: string | null): BadgeTone {
  if (verdict === 'PASS') return 'green'
  if (verdict === 'WARN') return 'amber'
  if (verdict === 'FAIL') return 'red'
  return 'gray'
}

export function severityTone(severity?: string | null): BadgeTone {
  if (severity === 'HIGH') return 'red'
  if (severity === 'MEDIUM') return 'amber'
  if (severity === 'LOW') return 'green'
  return 'gray'
}

/* ------------------------------ 组织架构（docs/11 §6.2） ------------------------------ */

export const ORG_TYPE_OPTIONS = ['COMPANY', 'BRANCH', 'DEPARTMENT', 'TEAM'] as const

const ORG_TYPE_LABELS: Record<string, string> = {
  COMPANY: '公司',
  BRANCH: '分支机构',
  DEPARTMENT: '部门',
  TEAM: '团队',
}

const ORG_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '启用',
  DISABLED: '停用',
}

export const orgTypeLabel = (v?: string | null) => fallback(ORG_TYPE_LABELS, v ?? undefined)
export const orgStatusLabel = (v?: string | null) => fallback(ORG_STATUS_LABELS, v ?? undefined, '启用')

export function orgStatusTone(status?: string | null): BadgeTone {
  if (status === 'DISABLED') return 'gray'
  return 'green'
}

/* ------------------------------ 租户账号（docs/11 §6.3） ------------------------------ */

export const ACCOUNT_ROLE_OPTIONS = ['TENANT_ADMIN', 'ORG_ADMIN', 'MEMBER', 'AUDITOR'] as const

const ACCOUNT_ROLE_LABELS: Record<string, string> = {
  TENANT_ADMIN: '租户管理员',
  ORG_ADMIN: '组织管理员',
  MEMBER: '成员',
  AUDITOR: '审计员',
}

export const ACCOUNT_STATUS_OPTIONS = ['INVITED', 'ACTIVE', 'DISABLED', 'LOCKED', 'DELETED'] as const

const ACCOUNT_STATUS_LABELS: Record<string, string> = {
  INVITED: '待激活',
  ACTIVE: '启用',
  DISABLED: '停用',
  LOCKED: '已锁定',
  DELETED: '已删除',
}

export const accountRoleLabel = (v?: string | null) => fallback(ACCOUNT_ROLE_LABELS, v ?? undefined)
export const accountStatusLabel = (v?: string | null) => fallback(ACCOUNT_STATUS_LABELS, v ?? undefined)

export function accountStatusTone(status?: string | null): BadgeTone {
  if (status === 'ACTIVE') return 'green'
  if (status === 'LOCKED') return 'red'
  if (status === 'INVITED') return 'amber'
  return 'gray'
}

/* ------------------------------ 租户评级（docs/11 §6.4） ------------------------------ */

export const RATING_GRADE_OPTIONS = ['AAA', 'AA', 'A', 'B', 'C', 'D'] as const

const RATING_SOURCE_LABELS: Record<string, string> = {
  SKILL: '自动评估',
  MANUAL: '人工覆盖',
}

export const ratingSourceLabel = (v?: string | null) => fallback(RATING_SOURCE_LABELS, v ?? undefined)

export const RATING_DIMENSIONS = [
  { key: 'business', label: '业务规模', weight: 30 },
  { key: 'compliance', label: '合规', weight: 25 },
  { key: 'financial', label: '财务', weight: 20 },
  { key: 'security', label: '安全', weight: 15 },
  { key: 'service', label: '服务', weight: 10 },
] as const

/* ------------------------------ v5：技能中心 / 入驻 / MCP 凭据 ------------------------------ */

const SKILL_SOURCE_LABELS: Record<string, string> = { BUILTIN: '内置', UPLOADED: '上传' }
const SKILL_STATUS_LABELS: Record<string, string> = {
  DRAFT: '草稿',
  PENDING_REVIEW: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
  DISABLED: '已停用',
}
const SKILL_REVIEW_ACTION_LABELS: Record<string, string> = {
  UPLOAD: '上传',
  UPDATE: '更新',
  SUBMIT: '提交审核',
  APPROVE: '审核通过',
  REJECT: '审核驳回',
  ENABLE: '启用',
  DISABLE: '停用',
}

export const skillSourceLabel = (v?: string | null) => fallback(SKILL_SOURCE_LABELS, v ?? undefined, '内置')
export const skillStatusLabel = (v?: string | null) => fallback(SKILL_STATUS_LABELS, v ?? undefined, '—')
export const skillReviewActionLabel = (v?: string | null) =>
  fallback(SKILL_REVIEW_ACTION_LABELS, v ?? undefined)

export function skillStatusTone(status?: string | null): BadgeTone {
  if (status === 'APPROVED') return 'green'
  if (status === 'PENDING_REVIEW') return 'amber'
  if (status === 'REJECTED') return 'red'
  if (status === 'DISABLED') return 'gray'
  return 'gray'
}

const ONBOARDING_STATUS_LABELS: Record<string, string> = {
  SUBMITTED: '已提交',
  AGENT_REVIEW: 'Agent 审核中',
  PENDING_MANUAL: '等待人工审核',
  ACTIVATED: '已开通',
  REJECTED: '已拒绝',
}

export const onboardingStatusLabel = (v?: string | null) =>
  fallback(ONBOARDING_STATUS_LABELS, v ?? undefined)

export function onboardingStatusTone(status?: string | null): BadgeTone {
  if (status === 'ACTIVATED') return 'green'
  if (status === 'REJECTED') return 'red'
  if (status === 'PENDING_MANUAL' || status === 'AGENT_REVIEW') return 'amber'
  return 'gray'
}

const MCP_KEY_STATUS_LABELS: Record<string, string> = {
  ACTIVE: '有效',
  REVOKED: '已撤销',
  EXPIRED: '已过期',
}

export const mcpKeyStatusLabel = (v?: string | null) => fallback(MCP_KEY_STATUS_LABELS, v ?? undefined)

export function mcpKeyStatusTone(status?: string | null): BadgeTone {
  if (status === 'ACTIVE') return 'green'
  if (status === 'REVOKED') return 'red'
  return 'gray'
}

export const mcpTransportLabel = (v?: string | null) =>
  v === 'SSE' ? 'SSE' : v === 'STREAMABLE_HTTP' ? 'Streamable HTTP' : fallback({}, v ?? undefined)
