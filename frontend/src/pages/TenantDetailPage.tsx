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
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { api, downloadFile, errorMessage } from '../api/client'
import type { TenantView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { usePermissions } from '../utils/permissions'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { ErrorBlock, LoadingBlock } from '../components/PageState'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
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
  const navigate = useNavigate()
  const toast = useToast()
  const perms = usePermissions()
  const canManage = perms.canManageTenant
  const canExport = perms.canExportTenant
  const canOffboard = perms.canOffboardTenant
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

  // v8：导出 / 离户
  const [exportOpen, setExportOpen] = useState(false)
  const [exportPlain, setExportPlain] = useState(false)
  const [exporting, setExporting] = useState(false)
  const [plainConfirmOpen, setPlainConfirmOpen] = useState(false)
  const [offboardOpen, setOffboardOpen] = useState(false)
  const [offboardReason, setOffboardReason] = useState('')
  const [offboardCode, setOffboardCode] = useState('')
  const [offboardError, setOffboardError] = useState<string | null>(null)
  const [offboarding, setOffboarding] = useState(false)

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

  const runExport = async (mask: boolean) => {
    if (!tenant.data) return
    setExporting(true)
    try {
      const fallback = `tenant-${tenant.data.tenantCode || tenant.data.id}-export.json`
      await downloadFile(`/api/tenants/${id}/export?mask=${mask ? 'true' : 'false'}`, fallback)
      toast.success(mask ? '租户数据已导出（脱敏）' : '租户数据已导出（明文）')
      setExportOpen(false)
      setPlainConfirmOpen(false)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  const openExport = () => {
    setExportPlain(false)
    setExportOpen(true)
  }

  const confirmExport = () => {
    if (exportPlain) {
      setExportOpen(false)
      setPlainConfirmOpen(true)
      return
    }
    runExport(true)
  }

  const openOffboard = () => {
    setOffboardReason('')
    setOffboardCode('')
    setOffboardError(null)
    setOffboardOpen(true)
  }

  const submitOffboard = async () => {
    if (!tenant.data) return
    setOffboardError(null)
    if (offboardReason.trim().length < 2) {
      setOffboardError('请填写离户原因（至少 2 个字符）')
      return
    }
    if (offboardReason.trim().length > 512) {
      setOffboardError('离户原因不能超过 512 个字符')
      return
    }
    if (offboardCode.trim() !== tenant.data.tenantCode) {
      setOffboardError('请输入完整的租户编码以确认离户')
      return
    }
    setOffboarding(true)
    try {
      await api.post<TenantView>(`/api/tenants/${id}/offboard`, {
        reason: offboardReason.trim(),
      })
      toast.success('租户已离户（终止并软删除）')
      setOffboardOpen(false)
      navigate('/tenants', { replace: true })
    } catch (err) {
      setOffboardError(errorMessage(err))
    } finally {
      setOffboarding(false)
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
  const canOffboardNow = canOffboard && (t.status === 'ACTIVE' || t.status === 'SUSPENDED')
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
          {canExport ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={openExport}>
              <Icon name="download" size={15} />
              导出数据
            </button>
          ) : null}
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
          {canOffboardNow ? (
            <button type="button" className="btn btn-danger btn-sm" onClick={openOffboard}>
              <Icon name="alert" size={15} />
              离户
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

      <Modal
        open={exportOpen}
        title="导出租户数据"
        description="导出 JSON 包含租户、组织架构、账号、评级与审计（文件名优先使用服务端 Content-Disposition）。"
        onClose={() => {
          if (!exporting) setExportOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setExportOpen(false)} disabled={exporting}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={confirmExport} disabled={exporting}>
              {exporting ? <span className="spinner spinner-light" /> : <Icon name="download" size={15} />}
              {exporting ? '导出中…' : '下载 JSON'}
            </button>
          </>
        }
      >
        <div className="decision-group export-options">
          <label className={`decision-option ${!exportPlain ? 'selected approve' : ''}`}>
            <input type="radio" checked={!exportPlain} onChange={() => setExportPlain(false)} />
            <span>
              <strong>脱敏导出（默认）</strong>
              <em>邮箱/手机号掩码，适用于常规审计与数据交付</em>
            </span>
          </label>
          {canOffboard ? (
            <label className={`decision-option ${exportPlain ? 'selected reject' : ''}`}>
              <input type="radio" checked={exportPlain} onChange={() => setExportPlain(true)} />
              <span>
                <strong>导出明文（仅平台管理员）</strong>
                <em>包含未脱敏 PII，需二次确认，操作会写入审计</em>
              </span>
            </label>
          ) : null}
        </div>
        {exportPlain ? (
          <div className="alert alert-red">
            <Icon name="alert" size={15} />
            <span>明文导出包含联系人邮箱/手机号等 PII，请确保传输与存储符合合规要求。</span>
          </div>
        ) : null}
      </Modal>

      <ConfirmModal
        open={plainConfirmOpen}
        title="确认导出明文数据"
        description="即将导出包含未脱敏邮箱/手机号的租户 JSON。该操作仅平台管理员可执行，并会记录 TENANT_EXPORTED 审计。"
        confirmText="确认导出明文"
        danger
        loading={exporting}
        onCancel={() => {
          if (!exporting) setPlainConfirmOpen(false)
        }}
        onConfirm={() => runExport(false)}
      />

      <Modal
        open={offboardOpen}
        title="租户离户"
        description="离户将终止租户并软删除租户记录，数据保留至保留期（默认 180 天）后清理。此操作不可恢复。"
        width={520}
        onClose={() => {
          if (!offboarding) setOffboardOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setOffboardOpen(false)} disabled={offboarding}>
              取消
            </button>
            <button type="button" className="btn btn-danger" onClick={submitOffboard} disabled={offboarding}>
              {offboarding ? <span className="spinner spinner-light" /> : <Icon name="alert" size={15} />}
              {offboarding ? '离户中…' : '确认离户'}
            </button>
          </>
        }
      >
        <div className="alert alert-red">
          <Icon name="alert" size={15} />
          <div>
            <strong>危险操作</strong>
            <p>仅平台管理员可执行，仅支持 ACTIVE / SUSPENDED 租户；离户后租户将无法登录且默认查询不可见。</p>
          </div>
        </div>
        <Field label="离户原因" required hint="必填，≤512 字符，写入租户审计">
          <textarea
            className={`input ${offboardError ? 'has-error' : ''}`}
            rows={3}
            value={offboardReason}
            placeholder="请填写离户原因，例如：客户合同到期不再续约"
            onChange={(event) => {
              setOffboardReason(event.target.value)
              setOffboardError(null)
            }}
          />
        </Field>
        <Field label={`输入租户编码「${t.tenantCode}」确认`} required>
          <input
            className="input mono"
            value={offboardCode}
            placeholder={t.tenantCode}
            onChange={(event) => {
              setOffboardCode(event.target.value)
              setOffboardError(null)
            }}
          />
        </Field>
        {offboardError ? <p className="field-error modal-error">{offboardError}</p> : null}
      </Modal>

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
