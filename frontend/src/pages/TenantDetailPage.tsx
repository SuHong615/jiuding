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
import { useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { TenantView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { usePermissions } from '../utils/permissions'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { ErrorBlock, LoadingBlock } from '../components/PageState'
import { Icon } from '../components/Icon'
import { RatingBadge } from '../components/RatingBadge'
import { useToast } from '../components/Toast'
import { TenantCreateDrawer } from './TenantCreateDrawer'
import { TenantOverviewTab } from './tenant/TenantOverviewTab'
import { TenantOrgTab } from './tenant/TenantOrgTab'
import { TenantAccountsTab } from './tenant/TenantAccountsTab'
import { TenantRatingTab } from './tenant/TenantRatingTab'
import { TenantApprovalsTab } from './tenant/TenantApprovalsTab'
import { TenantAuditsTab } from './tenant/TenantAuditsTab'
import {
  planLabel,
  planTone,
  riskLabel,
  riskTone,
  tenantStatusLabel,
  tenantStatusTone,
} from '../utils/labels'
import { formatDateTime } from '../utils/format'

const TAB_KEYS = ['overview', 'org', 'accounts', 'rating', 'approvals', 'audits'] as const
type TabKey = (typeof TAB_KEYS)[number]

const TABS: { key: TabKey; label: string }[] = [
  { key: 'overview', label: '概览' },
  { key: 'org', label: '组织架构' },
  { key: 'accounts', label: '账号' },
  { key: 'rating', label: '评级' },
  { key: 'approvals', label: '审批' },
  { key: 'audits', label: '审计' },
]

type ActionKind = 'submit' | 'suspend' | 'activate' | 'terminate'

interface ActionMeta {
  title: string
  description: string
  confirmText: string
  danger: boolean
  showReason: boolean
  reasonRequired: boolean
}

const ACTION_META: Record<ActionKind, ActionMeta> = {
  submit: {
    title: '提交租户审批',
    description: '提交后将同步创建审批单，租户进入风险审查 → 待审批流程。',
    confirmText: '确认提交',
    danger: false,
    showReason: false,
    reasonRequired: false,
  },
  suspend: {
    title: '停用租户',
    description: '停用后租户将无法访问平台资源，可随时重新启用。',
    confirmText: '确认停用',
    danger: true,
    showReason: true,
    reasonRequired: false,
  },
  activate: {
    title: '启用租户',
    description: '启用后租户恢复为 ACTIVE 状态，可正常访问平台资源。',
    confirmText: '确认启用',
    danger: false,
    showReason: false,
    reasonRequired: false,
  },
  terminate: {
    title: '终止租户',
    description: '终止为不可逆操作，租户数据将按合规策略归档，请谨慎操作。',
    confirmText: '确认终止',
    danger: true,
    showReason: true,
    reasonRequired: true,
  },
}

const STATUS_HINTS: Record<string, string> = {
  DRAFT: '草稿状态，可编辑后提交审批。',
  SUBMITTED: '已提交，等待审批服务受理。',
  RISK_REVIEW: '风险审查中，Agent 正在计算风险等级与审批路径。',
  PENDING_APPROVAL: '等待审批人人工决策，可在审批 Tab 或审批中心处理。',
  APPROVED: '审批已通过，等待资源开通完成。',
  PROVISIONING: '资源开通中（命名空间 / 配额 / Schema）。',
  ACTIVE: '租户正常服务中，可停用或终止。',
  REJECTED: '审批被驳回，可按意见修改后重新提交。',
  SUSPENDED: '租户已停用，可重新启用。',
  TERMINATED: '租户已终止，生命周期结束。',
}

function actionBody(kind: ActionKind, reason: string): unknown {
  if (kind === 'suspend') return { reason: reason.trim() || '运营停用' }
  if (kind === 'terminate') return { reason: reason.trim() }
  return undefined
}

export function TenantDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const toast = useToast()
  const perms = usePermissions()
  const canManage = perms.canManageTenant
  const visibleTabs = TABS.filter((item) => item.key !== 'rating' || perms.canReadRating)

  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get('tab')
  const tab: TabKey = TAB_KEYS.includes(rawTab as TabKey) ? (rawTab as TabKey) : 'overview'
  // MEMBER 无评级读权限：即使手改 URL，也不渲染评级内容
  const effectiveTab: TabKey = tab === 'rating' && !perms.canReadRating ? 'overview' : tab

  const tenant = useAsync(() => api.get<TenantView>(`/api/tenants/${id}`), [id])

  const [editOpen, setEditOpen] = useState(false)
  const [action, setAction] = useState<ActionKind | null>(null)
  const [reason, setReason] = useState('')
  const [reasonError, setReasonError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const setTab = (next: TabKey) => {
    const params = new URLSearchParams(searchParams)
    if (next === 'overview') params.delete('tab')
    else params.set('tab', next)
    setSearchParams(params, { replace: true })
  }

  const openAction = (kind: ActionKind) => {
    setAction(kind)
    setReason('')
    setReasonError(null)
  }

  const closeAction = () => {
    if (busy) return
    setAction(null)
    setReason('')
    setReasonError(null)
  }

  const confirmAction = async () => {
    if (!action || !tenant.data) return
    const meta = ACTION_META[action]
    if (meta.reasonRequired && reason.trim().length < 2) {
      setReasonError('请填写终止原因（至少 2 个字符）')
      return
    }
    setBusy(true)
    try {
      await api.post<TenantView>(`/api/tenants/${id}/${action}`, actionBody(action, reason))
      toast.success(`${meta.title}成功`)
      setAction(null)
      setReason('')
      tenant.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusy(false)
    }
  }

  if (tenant.loading && !tenant.data) {
    return (
      <div className="page">
        <LoadingBlock text="正在加载租户详情…" />
      </div>
    )
  }

  if (tenant.error && !tenant.data) {
    return (
      <div className="page">
        <Link to="/tenants" className="back-link">
          <Icon name="arrowLeft" size={15} />
          返回租户列表
        </Link>
        <ErrorBlock message={tenant.error} onRetry={tenant.reload} />
      </div>
    )
  }

  const t = tenant.data
  if (!t) {
    return (
      <div className="page">
        <ErrorBlock message="租户不存在或已删除" onRetry={tenant.reload} />
      </div>
    )
  }

  const canSubmit = t.status === 'DRAFT' || t.status === 'REJECTED'
  const canSuspend = t.status === 'ACTIVE'
  const canActivate = t.status === 'SUSPENDED'
  const canTerminate = t.status !== 'TERMINATED'
  const meta = action ? ACTION_META[action] : null
  const statusHint = STATUS_HINTS[t.status] ?? '未知状态，请检查数据源。'

  return (
    <div className="page">
      <Link to="/tenants" className="back-link">
        <Icon name="arrowLeft" size={15} />
        返回租户列表
      </Link>

      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>{t.name || t.tenantCode}</h2>
            <p>
              <span className="mono">{t.tenantCode}</span>
              <span className="muted"> · 创建于 {formatDateTime(t.createdAt)}</span>
            </p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={tenant.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          {canManage ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setEditOpen(true)}>
              <Icon name="edit" size={15} />
              编辑租户
            </button>
          ) : null}
          {canManage && canSubmit ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => openAction('submit')}>
              提交审批
            </button>
          ) : null}
          {canManage && canSuspend ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => openAction('suspend')}>
              停用
            </button>
          ) : null}
          {canManage && canActivate ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => openAction('activate')}>
              启用
            </button>
          ) : null}
          {canManage && canTerminate ? (
            <button type="button" className="btn btn-danger-ghost btn-sm" onClick={() => openAction('terminate')}>
              终止
            </button>
          ) : null}
        </div>
      </header>

      <div className="status-strip card">
        <div className="status-strip-main">
          <Badge tone={tenantStatusTone(t.status)} dot>
            {tenantStatusLabel(t.status)}
          </Badge>
          <Badge tone={planTone(t.plan)}>{planLabel(t.plan)}</Badge>
          <Badge tone={riskTone(t.riskLevel)}>
            {riskLabel(t.riskLevel)}
            {typeof t.riskScore === 'number' ? ` · 评分 ${t.riskScore}` : ''}
          </Badge>
          <span className="rating-strip">
            评级 <RatingBadge grade={t.ratingGrade} size="sm" />
            {typeof t.ratingScore === 'number' ? <span className="muted">{t.ratingScore}</span> : null}
          </span>
          <span className="muted">
            组织 {t.orgUnitCount ?? '—'} · 账号 {t.accountCount ?? '—'}
          </span>
        </div>
        <p className="status-hint">{statusHint}</p>
      </div>

      {t.status === 'REJECTED' && t.rejectReason ? (
        <div className="alert alert-red" role="alert">
          <Icon name="alert" size={16} />
          <div>
            <strong>审批驳回原因</strong>
            <p>{t.rejectReason}</p>
          </div>
        </div>
      ) : null}

      <div className="tabs" role="tablist" aria-label="租户详情分区">
        {visibleTabs.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={effectiveTab === item.key}
            className={`tab ${effectiveTab === item.key ? 'active' : ''}`}
            onClick={() => setTab(item.key)}
          >
            {item.label}
            {item.key === 'org' && t.orgUnitCount != null ? (
              <span className="tab-count">{t.orgUnitCount}</span>
            ) : null}
            {item.key === 'accounts' && t.accountCount != null ? (
              <span className="tab-count">{t.accountCount}</span>
            ) : null}
          </button>
        ))}
      </div>

      <div role="tabpanel">
        {effectiveTab === 'overview' ? (
          <TenantOverviewTab tenant={t} showRating={perms.canReadRating} />
        ) : null}
        {effectiveTab === 'org' ? (
          <TenantOrgTab
            tenantId={t.id}
            canManage={perms.canManageOrg}
            canCreateRoot={perms.canCreateRootOrg}
            canImport={perms.canCreateRootOrg}
          />
        ) : null}
        {effectiveTab === 'accounts' ? (
          <TenantAccountsTab
            tenantId={t.id}
            canManage={perms.canManageAccounts}
            canImport={perms.canCreateRootOrg}
            selfOnly={perms.memberSelfOnly}
            assignableRoles={perms.assignableAccountRoles}
          />
        ) : null}
        {effectiveTab === 'rating' ? (
          <TenantRatingTab
            tenantId={t.id}
            tenant={t}
            canManage={perms.canAssessRating}
            isAdmin={perms.canOverrideRating}
            onTenantUpdated={tenant.reload}
          />
        ) : null}
        {effectiveTab === 'approvals' ? <TenantApprovalsTab tenantId={t.id} /> : null}
        {effectiveTab === 'audits' ? <TenantAuditsTab tenantId={t.id} /> : null}
      </div>

      <TenantCreateDrawer
        open={editOpen}
        tenant={t}
        onClose={() => setEditOpen(false)}
        onSaved={() => tenant.reload()}
      />

      <ConfirmModal
        open={Boolean(action)}
        title={meta?.title ?? ''}
        description={meta?.description}
        confirmText={meta?.confirmText ?? '确认'}
        danger={meta?.danger ?? false}
        loading={busy}
        onCancel={closeAction}
        onConfirm={confirmAction}
      >
        {meta?.showReason ? (
          <div className="field">
            <label className="field-label">
              {action === 'terminate' ? '终止原因' : '停用原因'}
              {meta.reasonRequired ? <em className="required">*</em> : null}
            </label>
            <textarea
              className={`input ${reasonError ? 'has-error' : ''}`}
              rows={3}
              value={reason}
              placeholder={action === 'terminate' ? '请说明终止原因（必填）' : '可选：说明停用原因'}
              onChange={(event) => {
                setReason(event.target.value)
                setReasonError(null)
              }}
            />
            {reasonError ? <p className="field-error">{reasonError}</p> : null}
          </div>
        ) : null}
      </ConfirmModal>
    </div>
  )
}
