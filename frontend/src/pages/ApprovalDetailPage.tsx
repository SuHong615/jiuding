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
import { useState, type ReactNode } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { ApprovalView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { Badge } from '../components/Badge'
import { ErrorBlock, LoadingBlock } from '../components/PageState'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { ConfirmModal } from '../components/ConfirmModal'
import { Timeline } from '../components/Timeline'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'
import {
  approvalStateLabel,
  approvalStateTone,
  nodeLabel,
  nodeStateLabel,
  nodeStateTone,
  planLabel,
  riskLabel,
  riskTone,
  roleLabel,
} from '../utils/labels'

function InfoRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="info-row">
      <span className="info-label">{label}</span>
      <span className="info-value">{value || <span className="muted">—</span>}</span>
    </div>
  )
}

export function ApprovalDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const toast = useToast()
  const approval = useAsync(() => api.get<ApprovalView>(`/api/approvals/${id}`), [id])

  const [decision, setDecision] = useState<'APPROVE' | 'REJECT'>('APPROVE')
  const [comment, setComment] = useState('')
  const [commentError, setCommentError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const [escalateOpen, setEscalateOpen] = useState(false)
  const [escalateReason, setEscalateReason] = useState('')
  const [escalating, setEscalating] = useState(false)

  const submitDecision = async () => {
    if (decision === 'REJECT' && comment.trim().length < 2) {
      setCommentError('驳回必须填写审批意见（至少 2 个字符）')
      return
    }
    setSubmitting(true)
    try {
      await api.post<ApprovalView>(`/api/approvals/${id}/decisions`, {
        decision,
        comment: comment.trim() || undefined,
      })
      toast.success(decision === 'APPROVE' ? '审批已通过，租户进入开通流程' : '审批已驳回')
      setComment('')
      setCommentError(null)
      approval.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  const submitEscalate = async () => {
    setEscalating(true)
    try {
      await api.post<ApprovalView>(`/api/approvals/${id}/escalate`, {
        reason: escalateReason.trim() || '人工升级审批（SLA）',
      })
      toast.success('已升级至上级审批角色')
      setEscalateOpen(false)
      setEscalateReason('')
      approval.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setEscalating(false)
    }
  }

  if (approval.loading && !approval.data) {
    return (
      <div className="page">
        <LoadingBlock text="正在加载审批详情…" />
      </div>
    )
  }

  if (approval.error && !approval.data) {
    return (
      <div className="page">
        <Link to="/approvals" className="back-link">
          <Icon name="arrowLeft" size={15} />
          返回审批中心
        </Link>
        <ErrorBlock message={approval.error} onRetry={approval.reload} />
      </div>
    )
  }

  const a = approval.data
  if (!a) {
    return (
      <div className="page">
        <ErrorBlock message="审批单不存在或已删除" onRetry={approval.reload} />
      </div>
    )
  }

  const nodes = a.nodes ?? []
  const timeline = (a.timeline ?? []).map((item) => ({
    title: item.action || '事件',
    time: item.at,
    actor: item.actor,
    detail: item.detail,
    tone: item.action?.toUpperCase().includes('REJECT')
      ? ('red' as const)
      : item.action?.toUpperCase().includes('APPROVE')
        ? ('green' as const)
        : ('blue' as const),
  }))
  const canDecide = a.state === 'PENDING_APPROVAL'

  return (
    <div className="page">
      <Link to="/approvals" className="back-link">
        <Icon name="arrowLeft" size={15} />
        返回审批中心
      </Link>

      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>审批 · {a.tenantCode || a.tenantId}</h2>
            <p>
              <span className="mono">{a.id}</span>
              <span className="muted"> · 创建于 {formatDateTime(a.createdAt)}</span>
            </p>
          </div>
        </div>
        <div className="page-head-actions">
          <Link to={`/tenants/${a.tenantId}`} className="btn btn-ghost btn-sm">
            <Icon name="external" size={14} />
            查看租户
          </Link>
          <button type="button" className="btn btn-ghost btn-sm" onClick={approval.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <div className="status-strip card">
        <div className="status-strip-main">
          <Badge tone={approvalStateTone(a.state)} dot>
            {approvalStateLabel(a.state)}
          </Badge>
          <Badge tone="blue">{planLabel(a.plan)}</Badge>
          <Badge tone={riskTone(a.riskLevel)}>
            {riskLabel(a.riskLevel)}
            {typeof a.riskScore === 'number' ? ` · ${a.riskScore}` : ''}
          </Badge>
          {a.autoDecided ? <Badge tone="cyan">Agent 自动决策</Badge> : null}
          {a.escalated ? <Badge tone="purple">已升级</Badge> : null}
          <span className="muted">当前节点：{nodeLabel(a.currentNode)}</span>
        </div>
        <p className="status-hint">
          {canDecide
            ? '审批单等待人工决策：通过后自动触发租户状态流转与资源开通；驳回必须填写意见。'
            : '当前节点无需人工操作，可查看流程节点与时间线。'}
        </p>
      </div>

      <div className="detail-grid">
        <div className="detail-main">
          <SectionCard title="审批流程" description="AUTO 节点由规则引擎自动执行，HUMAN 节点需审批人决策">
            {nodes.length ? (
              <div className="flow">
                {nodes.map((node, index) => (
                  <div key={`${node.node}-${index}`} className={`flow-step flow-${node.state.toLowerCase()}`}>
                    <span className="flow-dot">
                      <Icon name={node.type === 'AUTO' ? 'skills' : 'user'} size={14} />
                    </span>
                    <div className="flow-body">
                      <strong>{nodeLabel(node.node)}</strong>
                      <span className="flow-type">
                        {node.type === 'AUTO' ? '自动' : node.assigneeRole ? roleLabel(node.assigneeRole) : '人工'}
                      </span>
                      <Badge tone={nodeStateTone(node.state)}>{nodeStateLabel(node.state)}</Badge>
                      {node.decidedAt ? (
                        <span className="muted flow-time">{formatDateTime(node.decidedAt)}</span>
                      ) : null}
                      {node.comment ? <p className="flow-comment">{node.comment}</p> : null}
                    </div>
                  </div>
                ))}
              </div>
            ) : (
              <p className="muted">暂无节点数据</p>
            )}
          </SectionCard>

          <SectionCard title="审批时间线" description="来自 approval_timeline">
            <Timeline items={timeline} emptyText="暂无时间线记录" />
          </SectionCard>
        </div>

        <div className="detail-side">
          <SectionCard title="审批信息">
            <div className="info-grid">
              <InfoRow label="租户 ID" value={<span className="mono">{a.tenantId}</span>} />
              <InfoRow label="租户编码" value={a.tenantCode} />
              <InfoRow label="计划" value={planLabel(a.plan)} />
              <InfoRow label="节点" value={nodeLabel(a.currentNode)} />
              <InfoRow label="SLA 截止" value={formatDateTime(a.slaDueAt)} />
              <InfoRow label="决策人" value={a.decidedBy} />
              <InfoRow label="决策意见" value={a.decisionComment} />
              <InfoRow label="更新时间" value={formatDateTime(a.updatedAt)} />
            </div>
          </SectionCard>

          {canDecide ? (
            <SectionCard title="审批决策" description="通过后自动流转，驳回必须填写意见">
              <div className="decision-group">
                <label className={`decision-option ${decision === 'APPROVE' ? 'selected approve' : ''}`}>
                  <input
                    type="radio"
                    name="decision"
                    checked={decision === 'APPROVE'}
                    onChange={() => setDecision('APPROVE')}
                  />
                  <Icon name="check" size={16} />
                  <span>
                    <strong>通过</strong>
                    <em>触发租户 APPROVED → PROVISIONING → ACTIVE</em>
                  </span>
                </label>
                <label className={`decision-option ${decision === 'REJECT' ? 'selected reject' : ''}`}>
                  <input
                    type="radio"
                    name="decision"
                    checked={decision === 'REJECT'}
                    onChange={() => setDecision('REJECT')}
                  />
                  <Icon name="close" size={16} />
                  <span>
                    <strong>驳回</strong>
                    <em>租户进入 REJECTED，可修改后重新提交</em>
                  </span>
                </label>
              </div>
              <div className="field">
                <label className="field-label">
                  审批意见
                  {decision === 'REJECT' ? <em className="required">*</em> : null}
                </label>
                <textarea
                  className={`input ${commentError ? 'has-error' : ''}`}
                  rows={3}
                  value={comment}
                  placeholder={decision === 'REJECT' ? '请填写驳回原因（必填）' : '可选：填写审批备注'}
                  onChange={(event) => {
                    setComment(event.target.value)
                    setCommentError(null)
                  }}
                />
                {commentError ? <p className="field-error">{commentError}</p> : null}
              </div>
              <button
                type="button"
                className={`btn ${decision === 'REJECT' ? 'btn-danger' : 'btn-primary'} btn-block`}
                onClick={submitDecision}
                disabled={submitting}
              >
                {submitting ? <span className="spinner spinner-light" /> : null}
                {submitting ? '提交中…' : decision === 'REJECT' ? '确认驳回' : '确认通过'}
              </button>
              <button
                type="button"
                className="btn btn-ghost btn-block"
                onClick={() => setEscalateOpen(true)}
              >
                <Icon name="arrowRight" size={15} />
                升级审批（SLA）
              </button>
            </SectionCard>
          ) : (
            <SectionCard title="决策结果">
              {a.decidedBy || a.decisionComment ? (
                <div className="info-grid">
                  <InfoRow label="决策人" value={a.decidedBy} />
                  <InfoRow label="决策意见" value={a.decisionComment} />
                </div>
              ) : (
                <p className="muted">
                  当前状态为 {approvalStateLabel(a.state)}，无需人工决策。
                </p>
              )}
            </SectionCard>
          )}
        </div>
      </div>

      <ConfirmModal
        open={escalateOpen}
        title="升级审批（SLA）"
        description="将审批单升级至上级审批角色，升级原因可选。"
        confirmText="确认升级"
        loading={escalating}
        onCancel={() => {
          if (!escalating) setEscalateOpen(false)
        }}
        onConfirm={submitEscalate}
      >
        <div className="field">
          <label className="field-label">升级原因</label>
          <textarea
            className="input"
            rows={3}
            value={escalateReason}
            placeholder="可选：例如审批人长时间未处理"
            onChange={(event) => setEscalateReason(event.target.value)}
          />
        </div>
      </ConfirmModal>
    </div>
  )
}
