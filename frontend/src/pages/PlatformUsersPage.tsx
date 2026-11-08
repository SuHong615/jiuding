import { useState } from 'react'
import { api, errorMessage } from '../api/client'
import type {
  PageResult,
  PlatformUserCreateRequest,
  PlatformUserResetResult,
  PlatformUserUpdateRequest,
  PlatformUserView,
} from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { Drawer } from '../components/Drawer'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'

const ROLE_OPTIONS = ['PLATFORM_ADMIN', 'APPROVER', 'AUDITOR'] as const
const ROLE_LABELS: Record<string, string> = {
  PLATFORM_ADMIN: '平台管理员',
  APPROVER: '审批人',
  AUDITOR: '审计员',
}

export function PlatformUsersPage() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()
  const users = useAsync(async () => {
    const result = await api.get<PlatformUserView[] | PageResult<PlatformUserView>>('/api/platform/users')
    return Array.isArray(result) ? result : pageItems(result)
  }, [])
  const items = users.data ?? []

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<PlatformUserView | null>(null)
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [roles, setRoles] = useState<string[]>(['APPROVER'])
  const [password, setPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [tempPassword, setTempPassword] = useState<string | null>(null)
  const [resetTarget, setResetTarget] = useState<PlatformUserView | null>(null)
  const [resetting, setResetting] = useState(false)

  const copy = (value: string) => {
    if (navigator.clipboard) {
      navigator.clipboard.writeText(value).then(() => toast.success('已复制')).catch(() => toast.error('复制失败'))
    }
  }

  const openCreate = () => {
    setEditing(null)
    setUsername('')
    setDisplayName('')
    setEmail('')
    setRoles(['APPROVER'])
    setPassword('')
    setFormError(null)
    setEditorOpen(true)
  }

  const openEdit = (user: PlatformUserView) => {
    setEditing(user)
    setUsername(user.username)
    setDisplayName(user.displayName ?? '')
    setEmail(user.email ?? '')
    setRoles(user.roles ?? [])
    setPassword('')
    setFormError(null)
    setEditorOpen(true)
  }

  const toggleRole = (role: string) => {
    setRoles((prev) => (prev.includes(role) ? prev.filter((item) => item !== role) : [...prev, role]))
  }

  const save = async () => {
    setFormError(null)
    if (!editing && !username.trim()) {
      setFormError('请输入用户名')
      return
    }
    if (!displayName.trim()) {
      setFormError('请输入显示名称')
      return
    }
    if (!roles.length) {
      setFormError('至少选择一个角色')
      return
    }
    if (!editing && password.length < 8) {
      setFormError('初始密码至少 8 位')
      return
    }
    setSaving(true)
    try {
      if (editing) {
        const payload: PlatformUserUpdateRequest = {
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          roles,
          status: editing.status,
        }
        await api.put<PlatformUserView>(`/api/platform/users/${editing.id}`, payload)
        toast.success('平台用户已更新')
      } else {
        const payload: PlatformUserCreateRequest = {
          username: username.trim(),
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          roles,
          password,
        }
        await api.post<PlatformUserView>('/api/platform/users', payload)
        toast.success('平台用户已创建')
      }
      setEditorOpen(false)
      users.reload()
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (user: PlatformUserView) => {
    setBusyId(user.id)
    try {
      const nextStatus = user.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
      await api.put<PlatformUserView>(`/api/platform/users/${user.id}`, {
        displayName: user.displayName,
        email: user.email ?? undefined,
        roles: user.roles,
        status: nextStatus,
      })
      toast.success(nextStatus === 'ACTIVE' ? '用户已启用' : '用户已停用')
      users.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const resetPassword = async () => {
    if (!resetTarget) return
    setResetting(true)
    try {
      const result = await api.post<PlatformUserResetResult>(
        `/api/platform/users/${resetTarget.id}/reset-password`,
        {},
      )
      setResetTarget(null)
      if (result?.tempPassword) setTempPassword(result.tempPassword)
      else toast.success('密码已重置')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setResetting(false)
    }
  }

  if (!isPlatformAdmin) {
    return (
      <div className="page">
        <SectionCard title="平台用户">
          <p className="muted">仅平台管理员可管理平台用户。</p>
        </SectionCard>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>平台用户</h2>
            <p>平台账号（PLATFORM_ADMIN / APPROVER / AUDITOR）的创建、编辑、启停与密码重置。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={users.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
            <Icon name="plus" size={15} />
            新建平台用户
          </button>
        </div>
      </header>

      <SectionCard title="平台用户列表" description="来自 /api/platform/users">
        <AsyncBlock
          loading={users.loading}
          error={users.error}
          onRetry={users.reload}
          empty={!items.length}
          emptyText="暂无平台用户"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>用户名</th>
                  <th>显示名称</th>
                  <th>邮箱</th>
                  <th>角色</th>
                  <th>状态</th>
                  <th>最近登录</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((user) => (
                  <tr key={user.id}>
                    <td className="mono">{user.username}</td>
                    <td>{user.displayName || '—'}</td>
                    <td className="muted">{user.email || '—'}</td>
                    <td>
                      <div className="chips">
                        {user.roles.map((role) => (
                          <span key={role} className="chip chip-blue">
                            {ROLE_LABELS[role] ?? role}
                          </span>
                        ))}
                      </div>
                    </td>
                    <td>
                      <Badge tone={user.status === 'ACTIVE' ? 'green' : 'red'} dot>
                        {user.status === 'ACTIVE' ? '启用' : '停用'}
                      </Badge>
                    </td>
                    <td className="muted nowrap">{formatDateTime(user.lastLoginAt)}</td>
                    <td className="align-right nowrap">
                      <button type="button" className="link-btn" onClick={() => openEdit(user)}>
                        编辑
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        disabled={busyId === user.id}
                        onClick={() => toggleStatus(user)}
                      >
                        {user.status === 'ACTIVE' ? '停用' : '启用'}
                      </button>
                      <button type="button" className="link-btn" onClick={() => setResetTarget(user)}>
                        重置密码
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
        open={editorOpen}
        title={editing ? `编辑平台用户 · ${editing.username}` : '新建平台用户'}
        subtitle={editing ? '用户名不可修改' : '创建后可使用平台账号登录控制台'}
        width={520}
        onClose={() => {
          if (!saving) setEditorOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setEditorOpen(false)} disabled={saving}>
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
          <Field label="用户名" required>
            <input
              className="input mono"
              value={username}
              disabled={Boolean(editing)}
              onChange={(event) => setUsername(event.target.value)}
            />
          </Field>
          <Field label="显示名称" required>
            <input className="input" value={displayName} onChange={(event) => setDisplayName(event.target.value)} />
          </Field>
          <Field label="邮箱">
            <input className="input" value={email} onChange={(event) => setEmail(event.target.value)} />
          </Field>
          {!editing ? (
            <Field label="初始密码" required hint="至少 8 位">
              <input
                className="input"
                type="password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
              />
            </Field>
          ) : null}
        </div>
        <div className="field">
          <span className="field-label">角色</span>
          <div className="checkbox-grid">
            {ROLE_OPTIONS.map((role) => (
              <label key={role} className="checkbox-row">
                <input type="checkbox" checked={roles.includes(role)} onChange={() => toggleRole(role)} />
                <span>{ROLE_LABELS[role]}（{role}）</span>
              </label>
            ))}
          </div>
        </div>
        {formError ? <p className="field-error modal-error">{formError}</p> : null}
      </Drawer>

      <Modal
        open={Boolean(tempPassword)}
        title="平台用户密码已重置"
        description="临时密码仅显示一次，请立即转交用户并提示其尽快修改。"
        onClose={() => setTempPassword(null)}
        footer={
          <button type="button" className="btn btn-primary" onClick={() => setTempPassword(null)}>
            我已保存
          </button>
        }
      >
        <div className="temp-password">
          <code>{tempPassword}</code>
          {tempPassword ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(tempPassword)}>
              <Icon name="copy" size={14} />
              复制
            </button>
          ) : null}
        </div>
      </Modal>

      <Modal
        open={Boolean(resetTarget)}
        title="重置平台用户密码"
        description={resetTarget ? `将为「${resetTarget.username}」生成一次性临时密码。` : undefined}
        onClose={() => {
          if (!resetting) setResetTarget(null)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setResetTarget(null)} disabled={resetting}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={resetPassword} disabled={resetting}>
              {resetting ? <span className="spinner spinner-light" /> : null}
              确认重置
            </button>
          </>
        }
      />
    </div>
  )
}
