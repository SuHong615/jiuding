import { api } from '../api/client'
import type { QuotaDimension, QuotaView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { formatNumber } from '../utils/format'

type QuotaStatus = 'OK' | 'WARNING' | 'EXCEEDED'

function statusTone(status: QuotaStatus) {
  return status === 'EXCEEDED' ? 'red' : status === 'WARNING' ? 'amber' : 'green'
}

function statusLabel(status: QuotaStatus) {
  return status === 'EXCEEDED' ? '已超限' : status === 'WARNING' ? '接近上限' : '正常'
}

function deriveStatus(quota: QuotaView): QuotaStatus {
  const raw = (quota.status || '').toUpperCase()
  if (raw === 'OK' || raw === 'WARNING' || raw === 'EXCEEDED') return raw as QuotaStatus
  const percents = [quota.seats?.percent, quota.orgUnits?.percent].filter(
    (value): value is number => typeof value === 'number',
  )
  const maxPercent = percents.length ? Math.max(...percents) : 0
  if (maxPercent >= 100) return 'EXCEEDED'
  if (maxPercent >= 80) return 'WARNING'
  return 'OK'
}

function isUnlimited(dimension?: QuotaDimension): boolean {
  return dimension?.limit === null || dimension?.limit === undefined || dimension.limit <= 0
}

function QuotaItem({
  label,
  dimension,
  status,
  note,
}: {
  label: string
  dimension?: QuotaDimension
  status: QuotaStatus
  note?: string
}) {
  const unlimited = isUnlimited(dimension)
  const limit = dimension?.limit
  const used = dimension?.used
  const remaining = dimension?.remaining
  const rawPercent = dimension?.percent
  const hasUsage = typeof used === 'number'
  const percent =
    typeof rawPercent === 'number'
      ? Math.max(0, Math.min(rawPercent, 100))
      : hasUsage && limit
        ? Math.max(0, Math.min(Math.round((used / limit) * 100), 100))
        : 0
  const over = status === 'EXCEEDED' || percent >= 100

  return (
    <div className={`quota-item ${over ? 'quota-item-exceeded' : ''}`}>
      <div className="quota-head">
        <span className="quota-label">{label}</span>
        {unlimited ? (
          <Badge tone="gray">不限</Badge>
        ) : (
          <span className={`quota-percent ${over ? 'text-red' : status === 'WARNING' ? 'text-amber' : ''}`}>
            {typeof rawPercent === 'number' ? `${Math.round(rawPercent)}%` : hasUsage ? `${percent}%` : '—'}
          </span>
        )}
      </div>
      <div className={`quota-bar ${unlimited ? 'quota-bar-unlimited' : ''}`}>
        <span
          className={`quota-fill ${over ? 'quota-fill-exceeded' : status === 'WARNING' ? 'quota-fill-warning' : ''}`}
          style={{ width: unlimited ? '100%' : `${percent}%` }}
        />
      </div>
      <div className="quota-meta">
        <span>
          已用 <strong>{typeof used === 'number' ? formatNumber(used) : '—'}</strong>
        </span>
        <span>
          上限 <strong>{unlimited ? '不限' : formatNumber(limit ?? null)}</strong>
        </span>
        <span>
          剩余 <strong>{typeof remaining === 'number' ? formatNumber(remaining) : '—'}</strong>
        </span>
      </div>
      {note ? <p className="quota-note">{note}</p> : null}
    </div>
  )
}

/** 租户配额/用量卡片（docs/17 §6）：GET /api/tenants/{id}/quota */
export function TenantQuotaCard({ tenantId }: { tenantId: string }) {
  const quota = useAsync(() => api.get<QuotaView>(`/api/tenants/${tenantId}/quota`), [tenantId])

  return (
    <SectionCard
      title="配额与用量"
      description="来自 /api/tenants/{id}/quota；≥80% 为接近上限，达到上限后相关写操作会被拒绝"
      actions={
        <button type="button" className="icon-btn" onClick={quota.reload} title="刷新配额用量">
          <Icon name="refresh" size={15} />
        </button>
      }
    >
      <AsyncBlock
        loading={quota.loading}
        error={quota.error}
        onRetry={quota.reload}
        empty={!quota.data}
        emptyText="暂无配额数据"
        emptyHint="后端尚未返回该租户的配额/用量信息"
      >
        {quota.data
          ? (() => {
              const status = deriveStatus(quota.data)
              return (
                <div className="quota-panel">
                  <div className="quota-panel-head">
                    <div className="quota-panel-title">
                      <span>套餐</span>
                      <strong>{quota.data.plan || '—'}</strong>
                    </div>
                    <Badge tone={statusTone(status)} dot>
                      {statusLabel(status)}
                    </Badge>
                  </div>
                  <div className="quota-grid">
                    <QuotaItem
                      label="席位"
                      dimension={quota.data.seats}
                      status={status}
                      note="已用 = 未删除账号数（含停用/锁定）"
                    />
                    <QuotaItem
                      label="组织部门"
                      dimension={quota.data.orgUnits}
                      status={status}
                      note="已用 = 未删除组织数"
                    />
                    <QuotaItem
                      label="存储（GB）"
                      dimension={quota.data.storageGb}
                      status={status}
                      note="存储用量暂未采集（used 返回 null）"
                    />
                    <QuotaItem
                      label="API RPM"
                      dimension={quota.data.apiRpm}
                      status={status}
                      note="网关按租户滑动窗口限流（60s）"
                    />
                  </div>
                </div>
              )
            })()
          : null}
      </AsyncBlock>
    </SectionCard>
  )
}
