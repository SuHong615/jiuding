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
import { api, downloadFile, errorMessage } from '../../api/client'
import type { TenantAudit } from '../../api/types'
import { useAsync } from '../../hooks/useAsync'
import { AsyncBlock } from '../../components/PageState'
import { SectionCard } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { Timeline } from '../../components/Timeline'
import { useToast } from '../../components/Toast'

const AUDIT_ACTION_LABELS: Record<string, string> = {
  'tenant.create': '创建租户',
  CREATE: '创建租户',
  'tenant.update': '更新租户',
  UPDATE: '更新租户',
  'tenant.submit': '提交审批',
  SUBMIT: '提交审批',
  'tenant.suspend': '停用租户',
  SUSPEND: '停用租户',
  'tenant.activate': '启用租户',
  ACTIVATE: '启用租户',
  'tenant.terminate': '终止租户',
  TERMINATE: '终止租户',
  'tenant.delete': '删除租户',
  DELETE: '删除租户',
  'tenant.status': '状态变更',
  'org.create': '新增组织',
  'org.update': '编辑组织',
  'org.move': '移动组织',
  'org.delete': '删除组织',
  'account.create': '新增账号',
  'account.update': '编辑账号',
  'account.disable': '停用账号',
  'account.enable': '启用账号',
  'account.reset_password': '重置密码',
  'account.delete': '删除账号',
  'ACCOUNT_UNLOCKED': '解锁账号',
  'PASSWORD_CHANGED': '修改密码',
  'rating.assess': '评级评估',
  'RATING_GRADE_CHANGED': '评级等级变更',
  'rating.override': '评级人工覆盖',
}

const DAY_MS = 24 * 60 * 60 * 1000

function dateInputValue(date: Date): string {
  const pad = (value: number) => String(value).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function TenantAuditsTab({ tenantId }: { tenantId: string }) {
  const toast = useToast()
  const audits = useAsync(
    () => api.get<TenantAudit[]>(`/api/tenants/${tenantId}/audits`),
    [tenantId],
  )

  const today = new Date()
  const [from, setFrom] = useState(() => dateInputValue(new Date(Date.now() - 30 * DAY_MS)))
  const [to, setTo] = useState(() => dateInputValue(today))
  const [exporting, setExporting] = useState(false)

  const entries = (audits.data ?? []).map((audit) => {
    const action = (audit.action ?? '').toLowerCase()
    return {
      title: audit.action ? (AUDIT_ACTION_LABELS[audit.action] ?? audit.action) : '操作',
      time: audit.at,
      actor: audit.actor,
      detail: audit.detail,
      tone: action.includes('reject') || action.includes('delete') || action.includes('locked')
        ? ('red' as const)
        : action.includes('override') || action.includes('grade')
          ? ('amber' as const)
          : ('blue' as const),
    }
  })

  const handleExport = async () => {
    if (from && to && from > to) {
      toast.error('开始日期不能晚于结束日期')
      return
    }
    const params = new URLSearchParams()
    if (from) params.set('from', `${from}T00:00:00Z`)
    if (to) params.set('to', `${to}T23:59:59Z`)
    params.set('format', 'csv')
    setExporting(true)
    try {
      await downloadFile(
        `/api/tenants/${tenantId}/audits/export?${params.toString()}`,
        `tenant-audits-${tenantId}-${from || 'all'}-${to || 'all'}.csv`,
      )
      toast.success('审计 CSV 已开始下载')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setExporting(false)
    }
  }

  return (
    <SectionCard
      title="审计时间线"
      description="来自 /api/tenants/{id}/audits（写操作均落审计）；可按日期范围导出 CSV"
      actions={
        <div className="audit-toolbar">
          <label className="audit-date">
            <span>开始</span>
            <input
              type="date"
              className="input input-date"
              value={from}
              max={to || undefined}
              onChange={(event) => setFrom(event.target.value)}
            />
          </label>
          <label className="audit-date">
            <span>结束</span>
            <input
              type="date"
              className="input input-date"
              value={to}
              min={from || undefined}
              onChange={(event) => setTo(event.target.value)}
            />
          </label>
          <button
            type="button"
            className="btn btn-primary btn-sm"
            onClick={handleExport}
            disabled={exporting}
            title="导出当前日期范围内的审计记录（CSV，最多 10000 行）"
          >
            {exporting ? <span className="spinner spinner-light" /> : <Icon name="download" size={14} />}
            {exporting ? '导出中…' : '导出 CSV'}
          </button>
          <button type="button" className="icon-btn" onClick={audits.reload} title="刷新审计记录">
            <Icon name="refresh" size={15} />
          </button>
        </div>
      }
    >
      <AsyncBlock
        loading={audits.loading}
        error={audits.error}
        onRetry={audits.reload}
        empty={!entries.length}
        emptyText="暂无审计记录"
      >
        <Timeline items={entries} />
      </AsyncBlock>
    </SectionCard>
  )
}
