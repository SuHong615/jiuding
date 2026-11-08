import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { PageResult, SsoProviderPayload, SsoProviderTestResult, SsoProviderView } from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Drawer } from '../components/Drawer'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'

interface EditorState {
  mode: 'create' | 'edit'
  provider: SsoProviderView | null
}

export function SsoProvidersPage() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()
  const providers = useAsync(async () => {
    const result = await api.get<SsoProviderView[] | PageResult<SsoProviderView>>('/api/platform/sso-providers')
    return Array.isArray(result) ? result : pageItems(result)
  }, [])
  const items = providers.data ?? []

  const [editor, setEditor] = useState<EditorState | null>(null)
  const [form, setForm] = useState({
    code: '',
    name: '',
    issuer: '',
    clientId: '',
    clientSecret: '',
    scopes: 'openid,profile,email',
    redirectUri: '',
    frontendRedirect: '',
    roleClaim: 'roles',
    defaultRole: 'AUDITOR',
    enabled: true,
  })
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<SsoProviderView | null>(null)
  const [deleting, setDeleting] = useState(false)

  const copy = (value: string) => {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(value).then(() => toast.success('已复制')).catch(() => toast.error('复制失败'))
    }
  }

  const setText = (key: keyof typeof form) => (value: string | boolean) =>
    setForm((prev) => ({ ...prev, [key]: value }))

  const openCreate = () => {
    setForm({
      code: '',
      name: '',
      issuer: '',
      clientId: '',
      clientSecret: '',
      scopes: 'openid,profile,email',
      redirectUri: '',
      frontendRedirect: '',
      roleClaim: 'roles',
      defaultRole: 'AUDITOR',
      enabled: true,
    })
    setFormError(null)
    setEditor({ mode: 'create', provider: null })
  }

  const openEdit = (provider: SsoProviderView) => {
    setForm({
      code: provider.code,
      name: provider.name,
      issuer: provider.issuer ?? '',
      clientId: provider.clientId ?? '',
      clientSecret: '',
      scopes: provider.scopes ?? 'openid,profile,email',
      redirectUri: provider.redirectUri ?? '',
      frontendRedirect: provider.frontendRedirect ?? '',
      roleClaim: provider.roleClaim ?? 'roles',
      defaultRole: provider.defaultRole ?? 'AUDITOR',
      enabled: provider.enabled !== false,
    })
    setFormError(null)
    setEditor({ mode: 'edit', provider })
  }

  const buildPayload = (): SsoProviderPayload => {
    const payload: SsoProviderPayload = {
      code: form.code.trim(),
      name: form.name.trim(),
      issuer: form.issuer.trim() || undefined,
      clientId: form.clientId.trim() || undefined,
      scopes: form.scopes.trim() || undefined,
      redirectUri: form.redirectUri.trim() || undefined,
      frontendRedirect: form.frontendRedirect.trim() || undefined,
      roleClaim: form.roleClaim.trim() || undefined,
      defaultRole: form.defaultRole.trim() || undefined,
      enabled: form.enabled,
    }
    if (form.clientSecret.trim()) payload.clientSecret = form.clientSecret.trim()
    return payload
  }

  const save = async () => {
    if (!editor) return
    setFormError(null)
    if (!form.code.trim() || !form.name.trim()) {
      setFormError('请填写 Provider code 与名称')
      return
    }
    if (!form.issuer.trim() || !form.clientId.trim()) {
      setFormError('请填写 Issuer 与 Client ID')
      return
    }
    setSaving(true)
    try {
      if (editor.mode === 'edit' && editor.provider) {
        await api.put<SsoProviderView>(`/api/platform/sso-providers/${editor.provider.id}`, buildPayload())
        toast.success('SSO Provider 已更新')
      } else {
        await api.post<SsoProviderView>('/api/platform/sso-providers', buildPayload())
        toast.success('SSO Provider 已创建')
      }
      setEditor(null)
      providers.reload()
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleEnabled = async (provider: SsoProviderView) => {
    setBusyId(provider.id)
    try {
      await api.put<SsoProviderView>(`/api/platform/sso-providers/${provider.id}`, {
        ...provider,
        enabled: !provider.enabled,
      })
      toast.success(provider.enabled ? 'Provider 已停用' : 'Provider 已启用')
      providers.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const testProvider = async (provider: SsoProviderView) => {
    setBusyId(provider.id)
    try {
      const result = await api.post<SsoProviderTestResult>(
        `/api/platform/sso-providers/${provider.id}/test`,
        {},
      )
      if (result?.ok === false) toast.error(result.message || '连通性测试失败')
      else toast.success(`连通性测试通过：${result?.authorizationEndpoint || result?.status || 'OK'}`)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const deleteProvider = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/platform/sso-providers/${deleteTarget.id}`)
      toast.success('SSO Provider 已删除')
      setDeleteTarget(null)
      providers.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  if (!isPlatformAdmin) {
    return (
      <div className="page">
        <SectionCard title="SSO 配置">
          <p className="muted">仅平台管理员可访问 SSO Provider 配置。</p>
        </SectionCard>
      </div>
    )
  }

  const origin = typeof window !== 'undefined' ? window.location.origin : ''

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>SSO 配置</h2>
            <p>配置多个 OIDC Identity Provider；登录页按启用列表展示企业 SSO 按钮。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={providers.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
            <Icon name="plus" size={15} />
            新建 Provider
          </button>
        </div>
      </header>

      <SectionCard
        title="公开登录入口"
        description="企业 IdP 登录入口；仅 enabled Provider 会出现在登录页"
        actions={
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(`${origin}/login`)}>
            <Icon name="copy" size={14} />
            复制登录入口
          </button>
        }
      >
        <div className="info-grid">
          <div className="info-row">
            <span className="info-label">登录页</span>
            <span className="info-value mono">{origin}/login</span>
          </div>
          <div className="info-row">
            <span className="info-label">Provider 列表</span>
            <span className="info-value mono">{origin}/api/auth/oidc/providers</span>
          </div>
        </div>
      </SectionCard>

      <SectionCard title="SSO Provider" description="来自 /api/platform/sso-providers（不返回 client secret 明文）">
        <AsyncBlock
          loading={providers.loading}
          error={providers.error}
          onRetry={providers.reload}
          empty={!items.length}
          emptyText="暂无 SSO Provider"
          emptyHint="新建 Provider 后，登录页会按启用状态展示企业 SSO 按钮"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Code</th>
                  <th>名称</th>
                  <th>Issuer</th>
                  <th>启用</th>
                  <th>更新时间</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((provider) => (
                  <tr key={provider.id}>
                    <td className="mono">{provider.code}</td>
                    <td>{provider.name}</td>
                    <td className="muted mono provider-issuer" title={provider.issuer ?? undefined}>
                      {provider.issuer || '—'}
                    </td>
                    <td>
                      <Badge tone={provider.enabled ? 'green' : 'gray'} dot>
                        {provider.enabled ? '启用' : '停用'}
                      </Badge>
                    </td>
                    <td className="muted nowrap">{formatDateTime(provider.updatedAt)}</td>
                    <td className="align-right nowrap">
                      <button type="button" className="link-btn" onClick={() => openEdit(provider)}>
                        编辑
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        disabled={busyId === provider.id}
                        onClick={() => testProvider(provider)}
                      >
                        测试
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        disabled={busyId === provider.id}
                        onClick={() => toggleEnabled(provider)}
                      >
                        {provider.enabled ? '停用' : '启用'}
                      </button>
                      <button
                        type="button"
                        className="link-btn danger"
                        onClick={() => setDeleteTarget(provider)}
                      >
                        删除
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </AsyncBlock>
      </SectionCard>

      <Drawer
        open={Boolean(editor)}
        title={editor?.mode === 'edit' ? `编辑 SSO Provider · ${editor.provider?.code}` : '新建 SSO Provider'}
        subtitle="OIDC Authorization Code + PKCE；client secret 仅提交给后端，不回显"
        width={620}
        onClose={() => {
          if (!saving) setEditor(null)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setEditor(null)} disabled={saving}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={save} disabled={saving}>
              {saving ? <span className="spinner spinner-light" /> : null}
              保存
            </button>
          </>
        }
      >
        <div className="form-grid">
          <Field label="Code" required hint="唯一标识，如 okta / azure">
            <input
              className="input mono"
              value={form.code}
              disabled={editor?.mode === 'edit'}
              onChange={(event) => setText('code')(event.target.value)}
            />
          </Field>
          <Field label="名称" required>
            <input className="input" value={form.name} onChange={(event) => setText('name')(event.target.value)} />
          </Field>
          <Field label="Issuer" required hint="OIDC issuer，如 https://idp.example.com">
            <input className="input mono" value={form.issuer} onChange={(event) => setText('issuer')(event.target.value)} />
          </Field>
          <Field label="Client ID" required>
            <input className="input mono" value={form.clientId} onChange={(event) => setText('clientId')(event.target.value)} />
          </Field>
          <Field label="Client Secret" hint="留空表示不修改">
            <input
              className="input"
              type="password"
              value={form.clientSecret}
              onChange={(event) => setText('clientSecret')(event.target.value)}
            />
          </Field>
          <Field label="Scopes">
            <input className="input mono" value={form.scopes} onChange={(event) => setText('scopes')(event.target.value)} />
          </Field>
          <Field label="Redirect URI">
            <input className="input mono" value={form.redirectUri} onChange={(event) => setText('redirectUri')(event.target.value)} />
          </Field>
          <Field label="Frontend Redirect">
            <input
              className="input mono"
              value={form.frontendRedirect}
              onChange={(event) => setText('frontendRedirect')(event.target.value)}
            />
          </Field>
          <Field label="Role Claim">
            <input className="input mono" value={form.roleClaim} onChange={(event) => setText('roleClaim')(event.target.value)} />
          </Field>
          <Field label="Default Role">
            <input
              className="input mono"
              value={form.defaultRole}
              onChange={(event) => setText('defaultRole')(event.target.value)}
            />
          </Field>
        </div>
        <label className="checkbox-row">
          <input type="checkbox" checked={form.enabled} onChange={(event) => setText('enabled')(event.target.checked)} />
          <span>启用该 Provider（登录页显示 SSO 按钮）</span>
        </label>
        {formError ? <p className="field-error modal-error">{formError}</p> : null}
      </Drawer>

      <ConfirmModal
        open={Boolean(deleteTarget)}
        title="删除 SSO Provider"
        description={deleteTarget ? `将删除 Provider「${deleteTarget.name}」；已登录用户的会话不受影响。` : undefined}
        confirmText="确认删除"
        danger
        loading={deleting}
        onCancel={() => {
          if (!deleting) setDeleteTarget(null)
        }}
        onConfirm={deleteProvider}
      />
    </div>
  )
}
