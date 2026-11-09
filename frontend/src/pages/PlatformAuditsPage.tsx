import { useMemo, useState } from 'react'
import { api, downloadFile, errorMessage } from '../api/client'
import type { PageResult, PlatformAuditView, TenantView } from '../api/types'
import { pageItems, pageTotal } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Pager } from '../components/Pager'
import { useToast } from '../components/Toast'
import { formatDateTime, truncate } from '../utils/format'

const PAGE_SIZE = 20
const DAY_MS = 24 * 60 * 60 * 1000

const ACTION_OPTIONS = [
  'tenant.create',
  'tenant.update',
  'tenant.submit',
  'tenant.suspend',
  'tenant.activate',
  'tenant.terminate',
  'tenant.delete',
  'TENANT_EXPORTED',
  'TENANT_OFFBOARDED',
  'ACCOUNT_UNLOCKED',
  'PASSWORD_CHANGED',
  'RATING_GRADE_CHANGED',
  'MCP_KEY_CREATED',
]

function dateInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function PlatformAuditsPage() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()

  const [tenantId, setTenantId] = useState('')
  const [action, setAction] = useState('')
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [from, setFrom] = useState(() => dateInputValue(new Date(Date.now() - 30 * DAY_MS)))
  const [to, setTo] = useState(() => dateInputValue(new Date()))
  const [page, setPage] = useState(0)
  const [exporting, setExporting] = useState(false)

  const tenants = useAsync(
    () => api.get<PageResult<TenantView>>('/api/tenants?page=0&size=100'),
    [],
  )
  const tenantItems = useMemo(() => pageItems(tenants.data), [tenants.data])

  const filters = useMemo(() => {
    const params = new URLSearchParams()
    if (tenantId) params.set('tenantId', tenantId)
    if (action) params.set('action', action)
    if (keyword) params.set('keyword', keyword)
    if (from) params.set('from', `${from}T00:00:00Z`)
    if (to) params.set('to', `${to}T23:59:59Z`)
    return params
  }, [tenantId, action, keyword, from, to])

  const query = useMemo(() => {
    const params = new URLSearchParams(filters)
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    return params.toString()
  }, [filters, page])

  const audits = useAsync(
    () => api.get<PageResult<PlatformAuditView> | PlatformAuditView[]>(`/api/platform/audits?${query}`),
    [query],
  )
  const items = Array.isArray(audits.data) ? audits.data : pageItems(audits.data)

  const handleSearch = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setKeyword(keywordInput.trim())
    setPage(0)
  }

  const resetFilters = () => {
    setTenantId('')
    setAction('')
    setKeyword('')
    setKeywordInput('')
    setFrom(dateInputValue(new Date(Date.now() - 30 * DAY_MS)))
    setTo(dateInputValue(new Date()))
    setPage(0)
  }

  const exportCsv = async () => {
    setExporting(true)
    try {
      const params = new URLSearchParams(filters)
      params.set('format', 'csv')
      await downloadFile(
        `/api/platform/audits/export?${params.toString()}`,
        `platform-audits-${from || 'all'}-${to || 'all'}.csv`,
      )
      toast.success('平台审计 CSV 已开始下载')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  if (!isPlatformAdmin) {
    return (
      <div className="page">
        <SectionCard title="平台审计中心">
          <p className="muted">仅平台管理员可访问跨租户审计中心。</p>
        </SectionCard>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>平台审计中心</h2>
            <p>跨租户查询操作审计（时间/租户/操作人/动作/详情），支持筛选与 CSV 导出。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { audits.reload(); tenants.reload() }}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={exportCsv} disabled={exporting}>
            {exporting ? <span className="spinner spinner-light" /> : <Icon name="download" size={15} />}
            {exporting ? '导出中…' : '导出 CSV'}
          </button>
        </div>
      </header>

      <div className="card">
        <form className="filters" onSubmit={handleSearch}>
          <div className="filter-item">
            <span className="filter-label">租户</span>
            <select
              className="select"
              value={tenantId}
              onChange={(event) => {
                setTenantId(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部租户</option>
              {tenantItems.map((tenant) => (
                <option key={tenant.id} value={tenant.id}>
                  {tenant.tenantCode} · {tenant.name}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-item">
            <span className="filter-label">动作</span>
            <input
              className="input mono filter-action"
              list="platform-audit-actions"
              value={action}
              placeholder="全部动作 / 输入过滤"
              onChange={(event) => {
                setAction(event.target.value)
                setPage(0)
              }}
            />
            <datalist id="platform-audit-actions">
              {ACTION_OPTIONS.map((option) => (
                <option key={option} value={option} />
              ))}
            </datalist>
          </div>
          <div className="filter-item">
            <span className="filter-label">开始日期</span>
            <input
              type="date"
              className="input input-date"
              value={from}
              max={to || undefined}
              onChange={(event) => {
                setFrom(event.target.value)
                setPage(0)
              }}
            />
          </div>
          <div className="filter-item">
            <span className="filter-label">结束日期</span>
            <input
              type="date"
              className="input input-date"
              value={to}
              min={from || undefined}
              onChange={(event) => {
                setTo(event.target.value)
                setPage(0)
              }}
            />
          </div>
          <div className="filter-item filter-grow">
            <span className="filter-label">关键词</span>
            <div className="input-with-icon">
              <Icon name="search" size={15} />
              <input
                className="input"
                value={keywordInput}
                placeholder="操作人 / 详情"
                onChange={(event) => setKeywordInput(event.target.value)}
              />
            </div>
          </div>
          <div className="filter-actions">
            <button type="submit" className="btn btn-primary btn-sm">
              查询
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilters}>
              重置
            </button>
          </div>
        </form>

        <div className="card-body">
          <AsyncBlock
            loading={audits.loading}
            error={audits.error}
            onRetry={audits.reload}
            empty={!items.length}
            emptyText="暂无审计记录"
            emptyHint="调整筛选条件，或确认已有租户操作审计"
          >
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>时间</th>
                    <th>租户</th>
                    <th>操作人</th>
                    <th>动作</th>
                    <th>详情</th>
                  </tr>
                </thead>
                <tbody>
                  {items.map((audit, index) => (
                    <tr key={audit.id ?? `${audit.at ?? ''}-${index}`}>
                      <td className="muted nowrap">{formatDateTime(audit.at)}</td>
                      <td>
                        <div className="cell-title">
                          <span className="mono">{audit.tenantCode || audit.tenantId || '—'}</span>
                        </div>
                        <div className="cell-sub">{audit.tenantName || '—'}</div>
                      </td>
                      <td className="muted">{audit.actor || '—'}</td>
                      <td>
                        <Badge tone="gray">{audit.action || '—'}</Badge>
                      </td>
                      <td className="muted audit-detail" title={audit.detail ?? undefined}>
                        {audit.detail ? truncate(audit.detail, 80) : '—'}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <Pager
              page={page}
              totalPages={Array.isArray(audits.data) ? 1 : audits.data?.totalPages}
              total={Array.isArray(audits.data) ? audits.data.length : pageTotal(audits.data)}
              onPage={setPage}
              disabled={audits.loading}
            />
          </AsyncBlock>
        </div>
      </div>
    </div>
  )
}
