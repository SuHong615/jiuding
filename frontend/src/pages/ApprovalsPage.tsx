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
import { useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { ApprovalOverview, ApprovalView, PageResult } from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Pager } from '../components/Pager'
import { formatDateTime, timeAgo } from '../utils/format'
import {
  APPROVAL_STATE_OPTIONS,
  approvalStateLabel,
  approvalStateTone,
  nodeLabel,
  planLabel,
  riskLabel,
  riskTone,
} from '../utils/labels'

const PAGE_SIZE = 10

export function ApprovalsPage() {
  const [tab, setTab] = useState<'pending' | 'all'>('pending')
  const [stateFilter, setStateFilter] = useState('')
  const [page, setPage] = useState(0)

  const overview = useAsync(() => api.get<ApprovalOverview>('/api/approvals/overview'), [])
  const pending = useAsync(() => api.get<ApprovalView[]>('/api/approvals/pending'), [])

  const query = useMemo(() => {
    const params = new URLSearchParams()
    if (stateFilter) params.set('state', stateFilter)
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    return params.toString()
  }, [stateFilter, page])

  const all = useAsync(
    () => api.get<PageResult<ApprovalView>>(`/api/approvals?${query}`),
    [query],
  )
  const allItems = pageItems(all.data)
  const pendingItems = pending.data ?? []

  const refresh = () => {
    overview.reload()
    if (tab === 'pending') pending.reload()
    else all.reload()
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>审批中心</h2>
            <p>处理待办审批、查看审批流程节点与 SLA；驳回必须填写审批意见</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={refresh}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <div className="kpi-grid kpi-grid-compact">
        <div className="card mini-stat">
          <span>待处理</span>
          <strong className="text-amber">{overview.data?.pending ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>自动通过</span>
          <strong className="text-green">{overview.data?.autoApproved ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>人工通过</span>
          <strong>{overview.data?.approved ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>已驳回</span>
          <strong className="text-red">{overview.data?.rejected ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>SLA 超时</span>
          <strong className={overview.data?.slaBreached ? 'text-red' : ''}>
            {overview.data?.slaBreached ?? '—'}
          </strong>
        </div>
      </div>

      <div className="tabs">
        <button
          type="button"
          className={`tab ${tab === 'pending' ? 'active' : ''}`}
          onClick={() => setTab('pending')}
        >
          待办审批
          <span className="tab-count">{pendingItems.length}</span>
        </button>
        <button type="button" className={`tab ${tab === 'all' ? 'active' : ''}`} onClick={() => setTab('all')}>
          全部审批
        </button>
      </div>

      {tab === 'pending' ? (
        <SectionCard title="待我处理" description="来自 /api/approvals/pending">
          <AsyncBlock
            loading={pending.loading}
            error={pending.error}
            onRetry={pending.reload}
            empty={!pendingItems.length}
            emptyText="当前没有待处理审批"
            emptyHint="提交租户后，风险审查为 MEDIUM/HIGH 的审批单会出现在这里"
          >
            <ul className="approval-list">
              {pendingItems.map((approval) => (
                <li key={approval.id}>
                  <Link to={`/approvals/${approval.id}`} className="approval-card">
                    <div className="approval-card-main">
                      <div className="approval-title">
                        <strong>{approval.tenantCode || approval.tenantId}</strong>
                        <Badge tone={approvalStateTone(approval.state)} dot>
                          {approvalStateLabel(approval.state)}
                        </Badge>
                        {approval.riskLevel ? (
                          <Badge tone={riskTone(approval.riskLevel)}>
                            {riskLabel(approval.riskLevel)}
                            {typeof approval.riskScore === 'number' ? ` · ${approval.riskScore}` : ''}
                          </Badge>
                        ) : null}
                        {approval.autoDecided ? <Badge tone="cyan">自动决策</Badge> : null}
                      </div>
                      <div className="approval-meta">
                        <span>{planLabel(approval.plan)}</span>
                        <span>当前节点：{nodeLabel(approval.currentNode)}</span>
                        <span>创建：{formatDateTime(approval.createdAt)}</span>
                        <span>SLA 截止：{formatDateTime(approval.slaDueAt)}</span>
                      </div>
                    </div>
                    <div className="approval-side">
                      <span className="btn btn-primary btn-sm">
                        去处理
                        <Icon name="arrowRight" size={14} />
                      </span>
                      <span className="muted">{timeAgo(approval.createdAt)}</span>
                    </div>
                  </Link>
                </li>
              ))}
            </ul>
          </AsyncBlock>
        </SectionCard>
      ) : (
        <SectionCard
          title="全部审批单"
          description="按状态筛选、分页查看审批历史"
          actions={
            <div className="filter-item">
              <select
                className="select"
                value={stateFilter}
                onChange={(event) => {
                  setStateFilter(event.target.value)
                  setPage(0)
                }}
              >
                <option value="">全部状态</option>
                {APPROVAL_STATE_OPTIONS.map((state) => (
                  <option key={state} value={state}>
                    {approvalStateLabel(state)}
                  </option>
                ))}
              </select>
            </div>
          }
        >
          <AsyncBlock
            loading={all.loading}
            error={all.error}
            onRetry={all.reload}
            empty={!allItems.length}
            emptyText="暂无审批记录"
            emptyHint="调整状态筛选，或提交租户以生成审批单"
          >
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>租户</th>
                    <th>计划</th>
                    <th>风险</th>
                    <th>状态</th>
                    <th>当前节点</th>
                    <th>SLA 截止</th>
                    <th>创建时间</th>
                    <th className="align-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {allItems.map((approval) => (
                    <tr key={approval.id}>
                      <td>
                        <div className="cell-title">
                          <Link to={`/approvals/${approval.id}`}>{approval.tenantCode || approval.tenantId}</Link>
                        </div>
                        <div className="cell-sub mono">{approval.tenantId}</div>
                      </td>
                      <td>
                        <Badge tone="blue">{planLabel(approval.plan)}</Badge>
                      </td>
                      <td>
                        <Badge tone={riskTone(approval.riskLevel)}>{riskLabel(approval.riskLevel)}</Badge>
                      </td>
                      <td>
                        <Badge tone={approvalStateTone(approval.state)} dot>
                          {approvalStateLabel(approval.state)}
                        </Badge>
                      </td>
                      <td className="muted">{nodeLabel(approval.currentNode)}</td>
                      <td className="muted nowrap">{formatDateTime(approval.slaDueAt)}</td>
                      <td className="muted nowrap">{formatDateTime(approval.createdAt)}</td>
                      <td className="align-right">
                        <Link to={`/approvals/${approval.id}`} className="btn btn-ghost btn-sm">
                          详情
                        </Link>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={page}
              totalPages={all.data?.totalPages}
              total={all.data?.totalElements ?? allItems.length}
              onPage={setPage}
              disabled={all.loading}
            />
          </AsyncBlock>
        </SectionCard>
      )}
    </div>
  )
}
