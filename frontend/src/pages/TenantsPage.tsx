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
import { Link, useSearchParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { PageResult, TenantView } from '../api/types'
import { pageItems, pageTotal } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { usePermissions } from '../utils/permissions'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Icon } from '../components/Icon'
import { Pager } from '../components/Pager'
import { RatingBadge } from '../components/RatingBadge'
import { useToast } from '../components/Toast'
import { TenantCreateDrawer } from './TenantCreateDrawer'
import { formatDateTime } from '../utils/format'
import {
  PLAN_OPTIONS,
  TENANT_STATUS_OPTIONS,
  planLabel,
  planTone,
  riskLabel,
  riskTone,
  sourceLabel,
  tenantStatusLabel,
  tenantStatusTone,
} from '../utils/labels'

const PAGE_SIZE = 10

type RowActionKind = 'suspend' | 'activate' | 'delete'

interface RowAction {
  kind: RowActionKind
  tenant: TenantView
}

const ROW_ACTION_META: Record<RowActionKind, { title: string; confirmText: string; danger: boolean }> = {
  suspend: { title: '停用租户', confirmText: '确认停用', danger: false },
  activate: { title: '启用租户', confirmText: '确认启用', danger: false },
  delete: { title: '删除租户', confirmText: '确认删除', danger: true },
}

export function TenantsPage() {
  const toast = useToast()
  const perms = usePermissions()
  const canManage = perms.canManageTenant
  const canCreate = perms.canCreateTenant
  const canDelete = perms.canDeleteTenant
  const [searchParams, setSearchParams] = useSearchParams()

  const [status, setStatus] = useState('')
  const [plan, setPlan] = useState('')
  const [keyword, setKeyword] = useState('')
  const [keywordInput, setKeywordInput] = useState('')
  const [page, setPage] = useState(0)
  const [drawerOpen, setDrawerOpen] = useState(() => searchParams.get('new') === '1')
  const [editTarget, setEditTarget] = useState<TenantView | null>(null)
  const [rowAction, setRowAction] = useState<RowAction | null>(null)
  const [acting, setActing] = useState(false)

  const query = useMemo(() => {
    const params = new URLSearchParams()
    if (status) params.set('status', status)
    if (plan) params.set('plan', plan)
    if (keyword) params.set('keyword', keyword)
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    return params.toString()
  }, [status, plan, keyword, page])

  const list = useAsync(() => api.get<PageResult<TenantView>>(`/api/tenants?${query}`), [query])
  const tenants = pageItems(list.data)

  const closeDrawer = () => {
    setDrawerOpen(false)
    setEditTarget(null)
    if (searchParams.get('new') === '1') {
      const next = new URLSearchParams(searchParams)
      next.delete('new')
      setSearchParams(next, { replace: true })
    }
  }

  const openCreate = () => {
    setEditTarget(null)
    setDrawerOpen(true)
  }

  const openEdit = (tenant: TenantView) => {
    setEditTarget(tenant)
    setDrawerOpen(true)
  }

  const handleSearch = (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setKeyword(keywordInput.trim())
    setPage(0)
  }

  const resetFilters = () => {
    setStatus('')
    setPlan('')
    setKeyword('')
    setKeywordInput('')
    setPage(0)
  }

  const confirmRowAction = async () => {
    if (!rowAction) return
    setActing(true)
    try {
      const { kind, tenant } = rowAction
      if (kind === 'delete') {
        await api.delete<unknown>(`/api/tenants/${tenant.id}`)
      } else {
        await api.post<TenantView>(
          `/api/tenants/${tenant.id}/${kind}`,
          kind === 'suspend' ? { reason: '运营停用' } : undefined,
        )
      }
      const messages: Record<RowActionKind, string> = {
        suspend: `租户「${tenant.name || tenant.tenantCode}」已停用`,
        activate: `租户「${tenant.name || tenant.tenantCode}」已启用`,
        delete: `租户「${tenant.name || tenant.tenantCode}」已删除（软删）`,
      }
      toast.success(messages[kind])
      setRowAction(null)
      list.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setActing(false)
    }
  }

  const actionMeta = rowAction ? ROW_ACTION_META[rowAction.kind] : null

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>租户管理</h2>
            <p>租户完整增删改查：查看、编辑、停用/启用、删除（仅非 ACTIVE，软删除）</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={list.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          {canCreate ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
              <Icon name="plus" size={15} />
              新建租户
            </button>
          ) : null}
        </div>
      </header>

      <div className="card">
        <form className="filters" onSubmit={handleSearch}>
          <div className="filter-item">
            <span className="filter-label">状态</span>
            <select
              className="select"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部状态</option>
              {TENANT_STATUS_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {tenantStatusLabel(option)}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-item">
            <span className="filter-label">计划</span>
            <select
              className="select"
              value={plan}
              onChange={(event) => {
                setPlan(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部计划</option>
              {PLAN_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {planLabel(option)}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-item filter-grow">
            <span className="filter-label">关键词</span>
            <div className="input-with-icon">
              <Icon name="search" size={15} />
              <input
                className="input"
                value={keywordInput}
                placeholder="租户编码 / 名称 / 联系人"
                onChange={(event) => setKeywordInput(event.target.value)}
              />
            </div>
          </div>
          <div className="filter-actions">
            <button type="submit" className="btn btn-primary btn-sm">
              搜索
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={resetFilters}>
              重置
            </button>
          </div>
        </form>

        <div className="card-body">
          <AsyncBlock
            loading={list.loading}
            error={list.error}
            onRetry={list.reload}
            empty={!tenants.length}
            emptyText="暂无租户"
            emptyHint="调整筛选条件，或点击右上角「新建租户」创建第一个租户"
          >
            <div className="table-wrap">
              <table className="table">
                <thead>
                  <tr>
                    <th>租户</th>
                    <th>计划</th>
                    <th>状态</th>
                    <th>评级</th>
                    <th>风险</th>
                    <th>配额</th>
                    <th>创建时间</th>
                    <th className="align-right">操作</th>
                  </tr>
                </thead>
                <tbody>
                  {tenants.map((tenant) => {
                    const isActive = tenant.status === 'ACTIVE'
                    const deleteDisabled = isActive
                    const deleteTitle = isActive
                      ? '仅非 ACTIVE 租户可删除，请先停用或终止'
                      : '删除租户（软删除）'
                    return (
                      <tr key={tenant.id}>
                        <td>
                          <div className="cell-title">
                            <Link to={`/tenants/${tenant.id}`}>{tenant.name || tenant.tenantCode}</Link>
                          </div>
                          <div className="cell-sub">
                            <span className="mono">{tenant.tenantCode}</span>
                            <span>· {tenant.region || '—'}</span>
                            <span>· {sourceLabel(tenant.source)}</span>
                          </div>
                        </td>
                        <td>
                          <Badge tone={planTone(tenant.plan)}>{planLabel(tenant.plan)}</Badge>
                        </td>
                        <td>
                          <Badge tone={tenantStatusTone(tenant.status)} dot>
                            {tenantStatusLabel(tenant.status)}
                          </Badge>
                        </td>
                        <td>
                          <div className="rating-cell">
                            <RatingBadge grade={tenant.ratingGrade} size="sm" />
                            {typeof tenant.ratingScore === 'number' ? (
                              <span className="muted rating-cell-score">{tenant.ratingScore}</span>
                            ) : null}
                          </div>
                        </td>
                        <td>
                          <Badge tone={riskTone(tenant.riskLevel)}>
                            {riskLabel(tenant.riskLevel)}
                            {typeof tenant.riskScore === 'number' ? ` · ${tenant.riskScore}` : ''}
                          </Badge>
                        </td>
                        <td>
                          <div className="quota-cell">
                            <span>席位 {tenant.quotaSeats ?? '—'}</span>
                            <span>存储 {tenant.quotaStorageGb ?? '—'} GB</span>
                            <span>API {tenant.quotaApiRpm ?? '—'}</span>
                          </div>
                        </td>
                        <td className="muted nowrap">{formatDateTime(tenant.createdAt)}</td>
                        <td className="align-right nowrap table-actions">
                          <Link to={`/tenants/${tenant.id}`} className="link-btn" aria-label="查看租户">
                            查看
                          </Link>
                          {canManage ? (
                            <button
                              type="button"
                              className="link-btn"
                              aria-label="编辑租户"
                              onClick={() => openEdit(tenant)}
                            >
                              编辑
                            </button>
                          ) : null}
                          {canManage && isActive ? (
                            <button
                              type="button"
                              className="link-btn"
                              aria-label="停用租户"
                              onClick={() => setRowAction({ kind: 'suspend', tenant })}
                            >
                              停用
                            </button>
                          ) : null}
                          {canManage && tenant.status === 'SUSPENDED' ? (
                            <button
                              type="button"
                              className="link-btn"
                              aria-label="启用租户"
                              onClick={() => setRowAction({ kind: 'activate', tenant })}
                            >
                              启用
                            </button>
                          ) : null}
                          {canDelete ? (
                            <button
                              type="button"
                              className="link-btn danger"
                              aria-label="删除租户"
                              title={deleteTitle}
                              disabled={deleteDisabled}
                              onClick={() => setRowAction({ kind: 'delete', tenant })}
                            >
                              删除
                            </button>
                          ) : null}
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>
            <Pager
              page={page}
              totalPages={list.data?.totalPages}
              total={pageTotal(list.data)}
              onPage={setPage}
              disabled={list.loading}
            />
          </AsyncBlock>
        </div>
      </div>

      <TenantCreateDrawer
        open={drawerOpen}
        tenant={editTarget}
        onClose={closeDrawer}
        onSaved={() => list.reload()}
      />

      <ConfirmModal
        open={Boolean(rowAction)}
        title={actionMeta?.title ?? ''}
        description={
          rowAction
            ? rowAction.kind === 'delete'
              ? `将软删除租户「${rowAction.tenant.name || rowAction.tenant.tenantCode}」。删除后仅平台管理员可通过 includeDeleted 查询，且仅非 ACTIVE 租户可删除。`
              : `租户「${rowAction.tenant.name || rowAction.tenant.tenantCode}」将${
                  rowAction.kind === 'suspend' ? '停用' : '恢复启用'
                }，状态变更会写入审计。`
            : undefined
        }
        confirmText={actionMeta?.confirmText ?? '确认'}
        danger={actionMeta?.danger ?? false}
        loading={acting}
        onCancel={() => {
          if (!acting) setRowAction(null)
        }}
        onConfirm={confirmRowAction}
      />
    </div>
  )
}
