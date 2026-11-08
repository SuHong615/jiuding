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
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type {
  ApprovalView,
  PageResult,
  PlatformOverview,
  ServiceHealth,
  TenantOverview,
} from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { Badge } from '../components/Badge'
import { KpiCard } from '../components/KpiCard'
import { StatusBars, type BarItem } from '../components/StatusBars'
import { AsyncBlock } from '../components/PageState'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { formatNumber, timeAgo } from '../utils/format'
import {
  TENANT_STATUS_OPTIONS,
  approvalStateLabel,
  approvalStateTone,
  nodeLabel,
  planLabel,
  tenantStatusLabel,
} from '../utils/labels'

const STATUS_COLORS: Record<string, string> = {
  DRAFT: '#d4d4d4',
  SUBMITTED: '#a3a3a3',
  RISK_REVIEW: '#8f8f8f',
  PENDING_APPROVAL: '#737373',
  APPROVED: '#525252',
  PROVISIONING: '#a3a3a3',
  ACTIVE: '#0d0d0d',
  REJECTED: '#8f8f8f',
  SUSPENDED: '#d4d4d4',
  TERMINATED: '#737373',
}

export function OverviewPage() {
  const overview = useAsync(() => api.get<PlatformOverview>('/api/platform/overview'), [])
  const health = useAsync(() => api.get<ServiceHealth[]>('/api/platform/health'), [])
  const tenantOverview = useAsync(() => api.get<TenantOverview>('/api/tenants/overview'), [])
  const recent = useAsync(
    () => api.get<PageResult<ApprovalView>>('/api/approvals?page=0&size=6'),
    [],
  )

  const data = overview.data
  const byStatus = tenantOverview.data?.byStatus ?? {}
  const bars: BarItem[] = TENANT_STATUS_OPTIONS.filter((status) => (byStatus[status] ?? 0) > 0).map(
    (status) => ({
      key: status,
      label: tenantStatusLabel(status),
      value: byStatus[status] ?? 0,
      color: STATUS_COLORS[status] ?? '#94a3b8',
    }),
  )
  const byPlan = tenantOverview.data?.byPlan ?? {}
  const byRisk = tenantOverview.data?.byRisk ?? {}
  const recentItems = pageItems(recent.data)

  const refreshAll = () => {
    overview.reload()
    health.reload()
    tenantOverview.reload()
    recent.reload()
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>运营总览</h2>
            <p>平台租户、审批、技能与 Agent 运行的整体健康度（数据来自网关聚合接口）</p>
          </div>
        </div>
        <div className="page-head-actions">
          <Link to="/tenants?new=1" className="btn btn-primary btn-sm">
            <Icon name="plus" size={15} />
            新建租户
          </Link>
          <button type="button" className="btn btn-ghost btn-sm" onClick={refreshAll}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <AsyncBlock
        loading={overview.loading && !overview.data}
        error={overview.data ? null : overview.error}
        onRetry={overview.reload}
      >
        <div className="kpi-grid">
          <KpiCard
            icon="tenants"
            accent="blue"
            label="租户总数"
            value={formatNumber(data?.tenants?.total)}
            sub={`正常 ${formatNumber(data?.tenants?.active)} · 待审 ${formatNumber(
              data?.tenants?.pending,
            )}`}
          />
          <KpiCard
            icon="shield"
            accent="green"
            label="ACTIVE 租户"
            value={formatNumber(data?.tenants?.active)}
            sub="正在服务的租户"
          />
          <KpiCard
            icon="approvals"
            accent="amber"
            label="待审批"
            value={formatNumber(data?.approvals?.pending)}
            sub={`自动通过 ${formatNumber(data?.approvals?.autoApproved)} · 驳回 ${formatNumber(
              data?.approvals?.rejected,
            )}`}
          />
          <KpiCard
            icon="skills"
            accent="purple"
            label="技能数"
            value={formatNumber(data?.skills?.total)}
            sub="规则引擎已加载的 Skill"
          />
          <KpiCard
            icon="agents"
            accent="cyan"
            label="Agent 运行"
            value={formatNumber(data?.agents?.runs)}
            sub={`今日 ${formatNumber(data?.agents?.todayRuns)} 次`}
          />
        </div>
      </AsyncBlock>

      <div className="grid-2">
        <SectionCard
          title="租户状态分布"
          description="来自 tenant-service /api/tenants/overview"
          actions={
            <button
              type="button"
              className="icon-btn"
              onClick={tenantOverview.reload}
              title="刷新状态分布"
            >
              <Icon name="refresh" size={15} />
            </button>
          }
        >
          <AsyncBlock
            loading={tenantOverview.loading}
            error={tenantOverview.error}
            onRetry={tenantOverview.reload}
            empty={!bars.length}
            emptyText="暂无租户数据"
            emptyHint="新建租户后这里将展示各状态的数量分布"
          >
            <StatusBars items={bars} />
            <div className="distribution-legend">
              <div className="legend-group">
                <span className="legend-title">计划分布</span>
                {Object.keys(byPlan).length ? (
                  Object.entries(byPlan).map(([plan, count]) => (
                    <span key={plan} className="legend-chip">
                      {planLabel(plan)}
                      <strong>{count}</strong>
                    </span>
                  ))
                ) : (
                  <span className="muted">暂无</span>
                )}
              </div>
              <div className="legend-group">
                <span className="legend-title">风险分布</span>
                {Object.keys(byRisk).length ? (
                  Object.entries(byRisk).map(([risk, count]) => (
                    <span key={risk} className="legend-chip">
                      {risk}
                      <strong>{count}</strong>
                    </span>
                  ))
                ) : (
                  <span className="muted">暂无</span>
                )}
              </div>
            </div>
          </AsyncBlock>
        </SectionCard>

        <SectionCard
          title="服务健康"
          description="来自网关 /api/platform/health（单服务失败不影响整体）"
          actions={
            <button type="button" className="icon-btn" onClick={health.reload} title="刷新服务健康">
              <Icon name="refresh" size={15} />
            </button>
          }
        >
          <AsyncBlock
            loading={health.loading}
            error={health.error}
            onRetry={health.reload}
            empty={!health.data?.length}
            emptyText="暂无健康数据"
          >
            <ul className="health-list">
              {(health.data ?? []).map((service) => (
                <li key={service.name} className="health-item">
                  <span className="health-name">
                    <Icon name="server" size={16} />
                    <strong>{service.name}</strong>
                    {service.url ? <span className="muted health-url">{service.url}</span> : null}
                  </span>
                  <span className="health-meta">
                    {typeof service.latencyMs === 'number' ? (
                      <span className="muted latency">{service.latencyMs} ms</span>
                    ) : null}
                    <span
                      className={`health-status ${
                        service.status === 'UP' ? 'up' : service.status === 'DOWN' ? 'down' : ''
                      }`}
                    >
                      <span className="dot" />
                      {service.status === 'UP'
                        ? '正常'
                        : service.status === 'DOWN'
                          ? '不可用'
                          : service.status || '未知'}
                    </span>
                  </span>
                </li>
              ))}
            </ul>
          </AsyncBlock>
        </SectionCard>
      </div>

      <SectionCard
        title="最近审批"
        description="最新审批单（审批中心）"
        actions={
          <Link to="/approvals" className="btn btn-ghost btn-sm">
            查看全部
            <Icon name="arrowRight" size={14} />
          </Link>
        }
      >
        <AsyncBlock
          loading={recent.loading}
          error={recent.error}
          onRetry={recent.reload}
          empty={!recentItems.length}
          emptyText="暂无审批记录"
          emptyHint="提交租户后将自动生成审批单"
        >
          <ul className="recent-list">
            {recentItems.map((approval) => (
              <li key={approval.id}>
                <Link to={`/approvals/${approval.id}`} className="recent-item">
                  <span className="recent-main">
                    <strong>{approval.tenantCode || approval.tenantId}</strong>
                    <span className="muted">
                      {planLabel(approval.plan)} · {nodeLabel(approval.currentNode)} ·{' '}
                      {approval.riskLevel ? `风险 ${approval.riskLevel}` : '风险未评估'}
                    </span>
                  </span>
                  <span className="recent-side">
                    <Badge tone={approvalStateTone(approval.state)}>
                      {approvalStateLabel(approval.state)}
                    </Badge>
                    <span className="muted">{timeAgo(approval.createdAt)}</span>
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        </AsyncBlock>
      </SectionCard>
    </div>
  )
}
