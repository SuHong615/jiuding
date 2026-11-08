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
import { api, errorMessage } from '../api/client'
import type {
  PageResult,
  ScimTokenCreateRequest,
  ScimTokenCreateResult,
  ScimTokenView,
  TenantView,
} from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'

const ENDPOINTS = ['/scim/v2/Users', '/scim/v2/Groups', '/scim/v2/ServiceProviderConfig']

export function ScimPage() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()

  const [tenantFilter, setTenantFilter] = useState('')
  const tokens = useAsync(
    () =>
      api.get<ScimTokenView[]>(
        `/api/platform/scim-tokens${tenantFilter ? `?tenantId=${encodeURIComponent(tenantFilter)}` : ''}`,
      ),
    [tenantFilter],
  )
  const tenants = useAsync(
    () => api.get<PageResult<TenantView>>('/api/tenants?page=0&size=100'),
    [],
  )
  const tenantItems = useMemo(() => pageItems(tenants.data), [tenants.data])

  const [createOpen, setCreateOpen] = useState(false)
  const [formTenant, setFormTenant] = useState('')
  const [formName, setFormName] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)
  const [created, setCreated] = useState<ScimTokenCreateResult | null>(null)

  const [revokeTarget, setRevokeTarget] = useState<ScimTokenView | null>(null)
  const [revoking, setRevoking] = useState(false)

  const copy = (value: string) => {
    if (navigator.clipboard) {
      navigator.clipboard
        .writeText(value)
        .then(() => toast.success('已复制'))
        .catch(() => toast.error('复制失败，请手动选择'))
    } else {
      toast.error('当前环境不支持复制')
    }
  }

  const openCreate = () => {
    setFormTenant(tenantFilter || tenantItems[0]?.id || '')
    setFormName('')
    setCreateError(null)
    setCreateOpen(true)
  }

  const createToken = async () => {
    setCreateError(null)
    if (!formTenant) {
      setCreateError('请选择租户')
      return
    }
    if (!formName.trim()) {
      setCreateError('请输入 Token 名称')
      return
    }
    setCreating(true)
    try {
      const payload: ScimTokenCreateRequest = { tenantId: formTenant, name: formName.trim() }
      const result = await api.post<ScimTokenCreateResult>('/api/platform/scim-tokens', payload)
      setCreated(result)
      setCreateOpen(false)
      tokens.reload()
    } catch (err) {
      setCreateError(errorMessage(err))
    } finally {
      setCreating(false)
    }
  }

  const revokeToken = async () => {
    if (!revokeTarget) return
    setRevoking(true)
    try {
      await api.delete<unknown>(`/api/platform/scim-tokens/${revokeTarget.id}`)
      toast.success('SCIM Token 已撤销')
      setRevokeTarget(null)
      tokens.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setRevoking(false)
    }
  }

  const origin = typeof window !== 'undefined' ? window.location.origin : ''
  const tokenItems = tokens.data ?? []

  if (!isPlatformAdmin) {
    return (
      <div className="page">
        <SectionCard title="SCIM 配置">
          <p className="muted">仅平台管理员可访问 SCIM Token 配置。</p>
        </SectionCard>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>SCIM 配置</h2>
            <p>为租户生成 SCIM 2.0 Token，供企业 IdP 同步 Users / Groups；Token 明文仅显示一次。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { tokens.reload(); tenants.reload() }}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
            <Icon name="plus" size={15} />
            生成 Token
          </button>
        </div>
      </header>

      <SectionCard
        title="SCIM Token"
        description="每个 Token 绑定一个租户；撤销后立即失效"
        actions={
          <select
            className="select select-compact"
            value={tenantFilter}
            onChange={(event) => setTenantFilter(event.target.value)}
          >
            <option value="">全部租户</option>
            {tenantItems.map((tenant) => (
              <option key={tenant.id} value={tenant.id}>
                {tenant.tenantCode} · {tenant.name}
              </option>
            ))}
          </select>
        }
      >
        <AsyncBlock
          loading={tokens.loading}
          error={tokens.error}
          onRetry={tokens.reload}
          empty={!tokenItems.length}
          emptyText="暂无 SCIM Token"
          emptyHint="点击「生成 Token」为租户创建 SCIM 凭据"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>租户</th>
                  <th>名称</th>
                  <th>Token 前缀</th>
                  <th>状态</th>
                  <th>创建时间</th>
                  <th>最近使用</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {tokenItems.map((token) => (
                  <tr key={token.id}>
                    <td className="mono">{token.tenantId}</td>
                    <td>{token.name}</td>
                    <td className="mono">{token.tokenPrefix || token.prefix || '—'}…</td>
                    <td>
                      <Badge tone={token.status === 'ACTIVE' ? 'green' : 'red'} dot>
                        {token.status === 'ACTIVE' ? '有效' : token.status === 'REVOKED' ? '已撤销' : token.status}
                      </Badge>
                    </td>
                    <td className="muted nowrap">{formatDateTime(token.createdAt)}</td>
                    <td className="muted nowrap">{formatDateTime(token.lastUsedAt)}</td>
                    <td className="align-right">
                      {token.status === 'ACTIVE' ? (
                        <button
                          type="button"
                          className="link-btn danger"
                          onClick={() => setRevokeTarget(token)}
                          aria-label="撤销 SCIM Token"
                        >
                          撤销
                        </button>
                      ) : (
                        <span className="muted">—</span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AsyncBlock>
      </SectionCard>

      <SectionCard title="SCIM 端点" description="IdP 侧配置 Base URL 与 Bearer Token 后即可同步">
        <ul className="endpoint-list">
          {ENDPOINTS.map((endpoint) => {
            const url = `${origin}${endpoint}`
            return (
              <li key={endpoint}>
                <span className="mono endpoint-url">{url}</span>
                <span className="endpoint-label">{endpoint.replace('/scim/v2/', '')}</span>
                <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(url)}>
                  <Icon name="copy" size={14} />
                  复制
                </button>
              </li>
            )
          })}
        </ul>
        <div className="info-grid scim-auth-info">
          <div className="info-row">
            <span className="info-label">认证方式</span>
            <span className="info-value mono">Authorization: Bearer scim_...</span>
          </div>
          <div className="info-row">
            <span className="info-label">过滤</span>
            <span className="info-value mono">userName eq "x" / displayName eq "x" / active eq true</span>
          </div>
          <div className="info-row">
            <span className="info-label">分页</span>
            <span className="info-value mono">startIndex（1-based）/ count ≤ 100</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="字段映射" description="User → accounts，Group → org_units">
        <div className="table-wrap">
          <table className="table table-compact">
            <thead>
              <tr>
                <th>SCIM 字段</th>
                <th>租户模型字段</th>
                <th>说明</th>
              </tr>
            </thead>
            <tbody>
              {[
                ['userName', 'accounts.username', '租户内唯一，小写'],
                ['displayName', 'accounts.display_name', '姓名'],
                ['emails[0].value', 'accounts.email', '主邮箱'],
                ['phoneNumbers[0].value', 'accounts.phone', '手机号'],
                ['active', 'accounts.status', 'true→ACTIVE，false→DISABLED'],
                ['roles[0].value', 'accounts.role', 'TENANT_ADMIN / ORG_ADMIN / MEMBER / AUDITOR'],
                ['urn:tenancy:params:2.0:User.orgUnitId', 'accounts.org_unit_id', '所属组织'],
                ['Group.displayName', 'org_units.name', '部门名称'],
                ['Group.externalId', 'org_units.code', '部门编码（缺省由名称生成）'],
                ['Group.members', 'accounts.id 列表', '组织成员'],
              ].map(([scimField, target, note]) => (
                <tr key={scimField}>
                  <td className="mono">{scimField}</td>
                  <td className="mono muted">{target}</td>
                  <td className="muted">{note}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </SectionCard>

      <Modal
        open={createOpen}
        title="生成 SCIM Token"
        description="Token 明文仅在生成成功后显示一次，请立即保存；每个 Token 绑定一个租户。"
        onClose={() => {
          if (!creating) setCreateOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setCreateOpen(false)} disabled={creating}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={createToken} disabled={creating}>
              {creating ? <span className="spinner spinner-light" /> : null}
              生成 Token
            </button>
          </>
        }
      >
        <div className="form-grid form-grid-2">
          <Field label="租户" required>
            <select className="select" value={formTenant} onChange={(event) => setFormTenant(event.target.value)}>
              <option value="">请选择租户</option>
              {tenantItems.map((tenant) => (
                <option key={tenant.id} value={tenant.id}>
                  {tenant.tenantCode} · {tenant.name}
                </option>
              ))}
            </select>
          </Field>
          <Field label="名称" required>
            <input className="input" value={formName} placeholder="如 Okta 同步" onChange={(event) => setFormName(event.target.value)} />
          </Field>
        </div>
        {createError ? <p className="field-error modal-error">{createError}</p> : null}
      </Modal>

      <Modal
        open={Boolean(created)}
        title="SCIM Token 已生成"
        description="以下 Token 明文只显示一次，关闭后无法查看；请立即配置到 IdP。"
        onClose={() => setCreated(null)}
        footer={
          <button type="button" className="btn btn-primary" onClick={() => setCreated(null)}>
            我已保存
          </button>
        }
      >
        {created ? (
          <>
            <div className="temp-password-hint">
              前缀：<span className="mono">{created.prefix}</span>
              {created.tenantId ? ` · 租户：${created.tenantId}` : ''}
            </div>
            <div className="temp-password">
              <code>{created.token}</code>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(created.token)}>
                <Icon name="copy" size={14} />
                复制
              </button>
            </div>
            <p className="field-hint">在 IdP 中设置 Authorization: Bearer &lt;该 Token&gt;。</p>
          </>
        ) : null}
      </Modal>

      <ConfirmModal
        open={Boolean(revokeTarget)}
        title="撤销 SCIM Token"
        description={
          revokeTarget
            ? `将撤销 Token「${revokeTarget.name}」（${revokeTarget.tokenPrefix || revokeTarget.prefix}…），IdP 同步会立即失败。`
            : undefined
        }
        confirmText="确认撤销"
        danger
        loading={revoking}
        onCancel={() => {
          if (!revoking) setRevokeTarget(null)
        }}
        onConfirm={revokeToken}
      />
    </div>
  )
}
