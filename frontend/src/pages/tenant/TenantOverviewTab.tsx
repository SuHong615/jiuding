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
import type { ReactNode } from 'react'
import { Link } from 'react-router-dom'
import type { TenantView } from '../../api/types'
import { SectionCard } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { RatingBadge } from '../../components/RatingBadge'
import { Badge } from '../../components/Badge'
import { formatDateTime, formatNumber } from '../../utils/format'
import {
  isolationLabel,
  planLabel,
  planTone,
  riskLabel,
  riskTone,
  sourceLabel,
  tenantStatusLabel,
  tenantStatusTone,
} from '../../utils/labels'

function InfoRow({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="info-row">
      <span className="info-label">{label}</span>
      <span className="info-value">{value || <span className="muted">—</span>}</span>
    </div>
  )
}

export function TenantOverviewTab({
  tenant,
  showRating = true,
}: {
  tenant: TenantView
  showRating?: boolean
}) {
  return (
    <div className="detail-grid">
      <div className="detail-main">
        <SectionCard title="基本信息" description="租户主数据与联系人信息">
          <div className="info-grid">
            <InfoRow label="租户 ID" value={<span className="mono">{tenant.id}</span>} />
            <InfoRow label="租户编码" value={<span className="mono">{tenant.tenantCode}</span>} />
            <InfoRow label="租户名称" value={tenant.name} />
            <InfoRow label="区域" value={tenant.region} />
            <InfoRow label="隔离模式" value={isolationLabel(tenant.isolationMode)} />
            <InfoRow label="来源" value={sourceLabel(tenant.source)} />
            <InfoRow label="联系人" value={tenant.contactName} />
            <InfoRow label="联系邮箱" value={tenant.contactEmail} />
            <InfoRow label="联系电话" value={tenant.contactPhone} />
            <InfoRow label="更新时间" value={formatDateTime(tenant.updatedAt)} />
          </div>
        </SectionCard>

        {showRating ? (
          <SectionCard title="评级与身份域" description="评级、组织与账号规模">
            <div className="stat-grid stat-grid-4">
              <div className="stat">
                <span>当前评级</span>
                <strong>
                  <RatingBadge grade={tenant.ratingGrade} size="sm" />
                </strong>
              </div>
              <div className="stat">
                <span>评级分数</span>
                <strong>{tenant.ratingScore ?? '—'}</strong>
              </div>
              <div className="stat">
                <span>组织部门</span>
                <strong>{formatNumber(tenant.orgUnitCount)}</strong>
              </div>
              <div className="stat">
                <span>账号数</span>
                <strong>{formatNumber(tenant.accountCount)}</strong>
              </div>
            </div>
            <p className="field-hint rating-assessed-at">
              最近评估：{formatDateTime(tenant.ratingAssessedAt)}
            </p>
          </SectionCard>
        ) : null}
      </div>

      <div className="detail-side">
        <SectionCard title="生命周期">
          <div className="lifecycle-times">
            <div>
              <span>当前状态</span>
              <strong>
                <Badge tone={tenantStatusTone(tenant.status)} dot>
                  {tenantStatusLabel(tenant.status)}
                </Badge>
              </strong>
            </div>
            <div>
              <span>计划</span>
              <strong>
                <Badge tone={planTone(tenant.plan)}>{planLabel(tenant.plan)}</Badge>
              </strong>
            </div>
            <div>
              <span>风险等级</span>
              <strong>
                <Badge tone={riskTone(tenant.riskLevel)}>
                  {riskLabel(tenant.riskLevel)}
                  {typeof tenant.riskScore === 'number' ? ` · ${tenant.riskScore}` : ''}
                </Badge>
              </strong>
            </div>
            <div>
              <span>提交时间</span>
              <strong>{formatDateTime(tenant.submittedAt)}</strong>
            </div>
            <div>
              <span>决策时间</span>
              <strong>{formatDateTime(tenant.decidedAt)}</strong>
            </div>
            <div>
              <span>生效时间</span>
              <strong>{formatDateTime(tenant.activatedAt)}</strong>
            </div>
          </div>
          <Link to="/approvals" className="btn btn-ghost btn-sm btn-block lifecycle-link">
            打开审批中心
            <Icon name="arrowRight" size={14} />
          </Link>
        </SectionCard>

        <SectionCard title="配额">
          <div className="stat-grid">
            <div className="stat">
              <span>席位配额</span>
              <strong>{formatNumber(tenant.quotaSeats)}</strong>
            </div>
            <div className="stat">
              <span>存储配额</span>
              <strong>{formatNumber(tenant.quotaStorageGb)} GB</strong>
            </div>
            <div className="stat">
              <span>API 配额</span>
              <strong>{formatNumber(tenant.quotaApiRpm)} RPM</strong>
            </div>
            <div className="stat">
              <span>风险评分</span>
              <strong>{tenant.riskScore ?? '—'}</strong>
            </div>
          </div>
        </SectionCard>
      </div>
    </div>
  )
}
