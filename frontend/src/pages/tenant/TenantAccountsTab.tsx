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
import { useMemo, useRef, useState } from 'react'
import { api, downloadFile, errorMessage } from '../../api/client'
import type {
  AccountCreateRequest,
  AccountCreateResult,
  AccountResetResult,
  AccountUpdateRequest,
  AccountView,
  ChangePasswordRequest,
  ImportResult,
  LoginEventView,
  OrgUnitView,
  PageResult,
} from '../../api/types'
import { pageItems, pageTotal } from '../../api/types'
import { useAsync } from '../../hooks/useAsync'
import { useAuth } from '../../auth/AuthContext'
import { usePermissions } from '../../utils/permissions'
import { AsyncBlock } from '../../components/PageState'
import { Badge } from '../../components/Badge'
import { ConfirmModal } from '../../components/ConfirmModal'
import { Drawer } from '../../components/Drawer'
import { Field } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { Modal } from '../../components/Modal'
import { Pager } from '../../components/Pager'
import { useToast } from '../../components/Toast'
import { formatDateTime, truncate } from '../../utils/format'
import {
  ACCOUNT_ROLE_OPTIONS,
  ACCOUNT_STATUS_OPTIONS,
  accountRoleLabel,
  accountStatusLabel,
  accountStatusTone,
} from '../../utils/labels'
import { flattenOrgUnits, normalizeOrgTree } from '../../utils/orgTree'

const PAGE_SIZE = 10
const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/
const USERNAME_RE = /^[a-zA-Z0-9._-]{3,64}$/
const PASSWORD_RE = /^(?=.*[A-Za-z])(?=.*\d).{10,}$/

// docs/14 §3.2：后端已按 mask=true 掩码；前端再兜底一次，避免旧后端返回明文
function maskEmail(value?: string | null): string {
  if (!value) return '—'
  const at = value.indexOf('@')
  if (at <= 0) return value
  return `${value.slice(0, 1)}***${value.slice(at)}`
}

function maskPhone(value?: string | null): string {
  if (!value) return '—'
  const digits = value.replace(/\D/g, '')
  if (digits.length < 7) return value
  return `${digits.slice(0, 3)}****${digits.slice(-4)}`
}

interface AccountEditorState {
  mode: 'create' | 'edit'
  account: AccountView | null
}

export function TenantAccountsTab({
  tenantId,
  canManage,
  canImport = false,
  selfOnly = false,
  assignableRoles,
}: {
  tenantId: string
  canManage: boolean
  canImport?: boolean
  selfOnly?: boolean
  assignableRoles?: string[]
}) {
  const toast = useToast()
  const { principal, updatePrincipal } = useAuth()
  const perms = usePermissions()

  const [page, setPage] = useState(0)
  const [orgUnitId, setOrgUnitId] = useState('')
  const [role, setRole] = useState('')
  const [status, setStatus] = useState('')
  const [keyword, setKeyword] = useState('')
  const [keywordInput, setKeywordInput] = useState('')
  const [mask, setMask] = useState(false)

  const query = useMemo(() => {
    const params = new URLSearchParams()
    if (orgUnitId) params.set('orgUnitId', orgUnitId)
    if (role) params.set('role', role)
    if (status) params.set('status', status)
    if (keyword) params.set('keyword', keyword)
    if (mask) params.set('mask', 'true')
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    return params.toString()
  }, [orgUnitId, role, status, keyword, page, mask])

  const accounts = useAsync(
    () => api.get<PageResult<AccountView>>(`/api/tenants/${tenantId}/accounts?${query}`),
    [query],
  )
  // MEMBER 仅展示本人：即使后端返回超集，前端再收敛一次
  const items = pageItems(accounts.data).filter(
    (account) => !selfOnly || !principal?.username || account.username === principal.username,
  )

  const orgs = useAsync(
    () => api.get<OrgUnitView[]>(`/api/tenants/${tenantId}/org-units?format=flat`),
    [tenantId],
  )
  const orgFlat = useMemo(() => flattenOrgUnits(normalizeOrgTree(orgs.data ?? [])), [orgs.data])
  const orgName = useMemo(() => {
    const map = new Map<string, string>()
    orgFlat.forEach((unit) => map.set(unit.id, unit.name))
    return map
  }, [orgFlat])

  const [editor, setEditor] = useState<AccountEditorState | null>(null)
  const [username, setUsername] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [email, setEmail] = useState('')
  const [phone, setPhone] = useState('')
  const [formOrgUnitId, setFormOrgUnitId] = useState('')
  const [formRole, setFormRole] = useState('MEMBER')
  const [initialPassword, setInitialPassword] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)
  const [tempPassword, setTempPassword] = useState<string | null>(null)

  const [deleteTarget, setDeleteTarget] = useState<AccountView | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [rowBusyId, setRowBusyId] = useState<string | null>(null)

  // v5：账号 CSV 导入（结果含一次性临时密码）
  const importInputRef = useRef<HTMLInputElement | null>(null)
  const [importOpen, setImportOpen] = useState(false)
  const [importFile, setImportFile] = useState<File | null>(null)
  const [importing, setImporting] = useState(false)
  const [importResult, setImportResult] = useState<ImportResult | null>(null)
  const [importError, setImportError] = useState<string | null>(null)

  const openImport = () => {
    setImportFile(null)
    setImportResult(null)
    setImportError(null)
    setImportOpen(true)
    if (importInputRef.current) importInputRef.current.value = ''
  }

  const downloadAccountTemplate = async () => {
    try {
      await downloadFile(
        `/api/tenants/${tenantId}/import-templates/accounts.csv`,
        'accounts-template.csv',
      )
      toast.success('账号导入模板已开始下载')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  const submitImport = async () => {
    if (!importFile) {
      setImportError('请选择 CSV 文件')
      return
    }
    setImportError(null)
    const form = new FormData()
    form.append('file', importFile)
    setImporting(true)
    try {
      const result = await api.upload<ImportResult>(
        `/api/tenants/${tenantId}/accounts/import`,
        form,
      )
      setImportResult(result)
      toast.success(`导入完成：成功 ${result?.imported ?? 0} 条`)
      accounts.reload()
    } catch (err) {
      setImportError(errorMessage(err))
    } finally {
      setImporting(false)
    }
  }

  // v4：本人修改密码
  const [passwordAccount, setPasswordAccount] = useState<AccountView | null>(null)
  const [oldPassword, setOldPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [passwordError, setPasswordError] = useState<string | null>(null)
  const [changingPassword, setChangingPassword] = useState(false)

  // v4：登录历史抽屉
  const [historyAccount, setHistoryAccount] = useState<AccountView | null>(null)
  const [historyPage, setHistoryPage] = useState(0)
  const loginEvents = useAsync(
    () =>
      historyAccount
        ? api.get<PageResult<LoginEventView>>(
            `/api/tenants/${tenantId}/accounts/${historyAccount.id}/login-events?page=${historyPage}&size=10`,
          )
        : Promise.resolve(null),
    [historyAccount?.id, historyPage],
  )
  const loginEventItems = pageItems(loginEvents.data)

  const isOwnAccount = (account: AccountView) =>
    Boolean(
      principal?.tenantId &&
        principal.username &&
        principal.username.toLowerCase() === account.username.toLowerCase(),
    )

  const canViewEventsFor = (account: AccountView) =>
    perms.canManageAccounts ||
    perms.isAuditor ||
    (perms.memberSelfOnly && isOwnAccount(account))

  const openChangePassword = (account: AccountView) => {
    setPasswordAccount(account)
    setOldPassword('')
    setNewPassword('')
    setConfirmPassword('')
    setPasswordError(null)
  }

  const closeChangePassword = () => {
    if (changingPassword) return
    setPasswordAccount(null)
    setPasswordError(null)
  }

  const submitChangePassword = async () => {
    if (!passwordAccount) return
    setPasswordError(null)
    if (!oldPassword) {
      setPasswordError('请输入当前密码')
      return
    }
    if (!PASSWORD_RE.test(newPassword)) {
      setPasswordError('新密码至少 10 位，且必须同时包含字母和数字')
      return
    }
    if (newPassword === oldPassword) {
      setPasswordError('新密码不能与当前密码相同')
      return
    }
    if (newPassword !== confirmPassword) {
      setPasswordError('两次输入的新密码不一致')
      return
    }
    setChangingPassword(true)
    try {
      const payload: ChangePasswordRequest = { oldPassword, newPassword }
      await api.post<AccountView>(
        `/api/tenants/${tenantId}/accounts/${passwordAccount.id}/change-password`,
        payload,
      )
      toast.success('密码已修改')
      // 本人改密成功后清除 mustChangePassword，隐藏顶部警示条
      if (isOwnAccount(passwordAccount)) updatePrincipal({ mustChangePassword: false })
      setPasswordAccount(null)
    } catch (err) {
      setPasswordError(errorMessage(err))
    } finally {
      setChangingPassword(false)
    }
  }

  const unlockAccount = async (account: AccountView) => {
    setRowBusyId(account.id)
    try {
      await api.post<AccountView>(`/api/tenants/${tenantId}/accounts/${account.id}/unlock`)
      toast.success('账号已解锁，状态恢复为启用')
      accounts.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setRowBusyId(null)
    }
  }

  const openLoginHistory = (account: AccountView) => {
    setHistoryAccount(account)
    setHistoryPage(0)
  }

  const closeLoginHistory = () => {
    setHistoryAccount(null)
    setHistoryPage(0)
  }

  const roleOptions = useMemo(() => {
    const base =
      assignableRoles && assignableRoles.length
        ? [...assignableRoles]
        : [...ACCOUNT_ROLE_OPTIONS]
    if (editor?.account?.role && !base.includes(editor.account.role)) {
      base.push(editor.account.role)
    }
    return base
  }, [assignableRoles, editor])

  const openCreate = () => {
    setEditor({ mode: 'create', account: null })
    setUsername('')
    setDisplayName('')
    setEmail('')
    setPhone('')
    setFormOrgUnitId('')
    setFormRole('MEMBER')
    setInitialPassword('')
    setFormError(null)
  }

  const openEdit = (account: AccountView) => {
    setEditor({ mode: 'edit', account })
    setUsername(account.username)
    setDisplayName(account.displayName)
    setEmail(account.email ?? '')
    setPhone(account.phone ?? '')
    setFormOrgUnitId(account.orgUnitId ?? '')
    setFormRole(account.role || 'MEMBER')
    setInitialPassword('')
    setFormError(null)
  }

  const closeEditor = () => {
    if (saving) return
    setEditor(null)
    setFormError(null)
  }

  const submitEditor = async () => {
    if (!editor) return
    setFormError(null)
    if (editor.mode === 'create') {
      if (!username.trim()) {
        setFormError('请输入用户名')
        return
      }
      if (!USERNAME_RE.test(username.trim())) {
        setFormError('用户名为 3-64 位字母/数字/._-')
        return
      }
    }
    if (!displayName.trim()) {
      setFormError('请输入姓名')
      return
    }
    if (email.trim() && !EMAIL_RE.test(email.trim())) {
      setFormError('邮箱格式不正确')
      return
    }
    if (editor.mode === 'create' && initialPassword) {
      if (!PASSWORD_RE.test(initialPassword)) {
        setFormError('初始密码至少 10 位，且必须同时包含字母和数字')
        return
      }
      if (initialPassword.toLowerCase() === username.trim().toLowerCase()) {
        setFormError('密码不能与用户名相同')
        return
      }
    }

    setSaving(true)
    try {
      if (editor.mode === 'create') {
        const payload: AccountCreateRequest = {
          username: username.trim().toLowerCase(),
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          phone: phone.trim() || undefined,
          orgUnitId: formOrgUnitId || null,
          role: formRole,
          initialPassword: initialPassword || undefined,
        }
        const result = await api.post<AccountCreateResult>(
          `/api/tenants/${tenantId}/accounts`,
          payload,
        )
        toast.success(`账号「${result?.account?.username ?? payload.username}」已创建`)
        setEditor(null)
        accounts.reload()
        if (result?.tempPassword) setTempPassword(result.tempPassword)
      } else if (editor.account) {
        const payload: AccountUpdateRequest = {
          displayName: displayName.trim(),
          email: email.trim() || undefined,
          phone: phone.trim() || undefined,
          orgUnitId: formOrgUnitId || null,
          role: formRole,
        }
        await api.put<AccountView>(`/api/tenants/${tenantId}/accounts/${editor.account.id}`, payload)
        toast.success('账号信息已更新')
        setEditor(null)
        accounts.reload()
      }
    } catch (err) {
      setFormError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const toggleStatus = async (account: AccountView) => {
    const nextStatus = account.status === 'ACTIVE' ? 'DISABLED' : 'ACTIVE'
    setRowBusyId(account.id)
    try {
      await api.post<AccountView>(`/api/tenants/${tenantId}/accounts/${account.id}/status`, {
        status: nextStatus,
        reason: '运营操作',
      })
      toast.success(nextStatus === 'ACTIVE' ? '账号已启用' : '账号已停用')
      accounts.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setRowBusyId(null)
    }
  }

  const resetPassword = async (account: AccountView) => {
    setRowBusyId(account.id)
    try {
      const result = await api.post<AccountResetResult>(
        `/api/tenants/${tenantId}/accounts/${account.id}/reset-password`,
        {},
      )
      if (result?.tempPassword) {
        setTempPassword(result.tempPassword)
        toast.success('密码已重置，请转交一次性临时密码')
      } else {
        toast.success('密码已重置')
      }
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setRowBusyId(null)
    }
  }

  const confirmDelete = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/tenants/${tenantId}/accounts/${deleteTarget.id}`)
      toast.success('账号已删除（软删）')
      setDeleteTarget(null)
      accounts.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  const copyTempPassword = () => {
    if (!tempPassword) return
    if (navigator.clipboard) {
      navigator.clipboard
        .writeText(tempPassword)
        .then(() => toast.success('临时密码已复制'))
        .catch(() => toast.error('复制失败，请手动选择文本'))
    } else {
      toast.error('当前环境不支持一键复制，请手动选择')
    }
  }

  const resetFilters = () => {
    setOrgUnitId('')
    setRole('')
    setStatus('')
    setKeyword('')
    setKeywordInput('')
    setPage(0)
  }

  const copyValue = (value: string) => {
    if (navigator.clipboard) {
      navigator.clipboard
        .writeText(value)
        .then(() => toast.success('已复制'))
        .catch(() => toast.error('复制失败，请手动选择'))
    } else {
      toast.error('当前环境不支持复制')
    }
  }

  return (
    <div className="card">
      {selfOnly ? (
        <div className="readonly-note">
          <Icon name="shield" size={14} />
          <span>
            MEMBER 只读视图：仅展示本人账号（{principal?.username ?? '—'}），无编辑、启停、重置密码权限。
          </span>
        </div>
      ) : (
      <form
        className="filters"
        onSubmit={(event) => {
          event.preventDefault()
          setKeyword(keywordInput.trim())
          setPage(0)
        }}
      >
        <div className="filter-item">
          <span className="filter-label">组织</span>
          <select
            className="select"
            value={orgUnitId}
            onChange={(event) => {
              setOrgUnitId(event.target.value)
              setPage(0)
            }}
            title={orgs.error ? '组织架构加载失败' : undefined}
          >
            <option value="">全部组织</option>
            {orgFlat.map((unit) => (
              <option key={unit.id} value={unit.id}>
                {unit.name}
              </option>
            ))}
          </select>
        </div>
        <div className="filter-item">
          <span className="filter-label">角色</span>
          <select
            className="select"
            value={role}
            onChange={(event) => {
              setRole(event.target.value)
              setPage(0)
            }}
          >
            <option value="">全部角色</option>
            {ACCOUNT_ROLE_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {accountRoleLabel(option)}
              </option>
            ))}
          </select>
        </div>
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
            {ACCOUNT_STATUS_OPTIONS.map((option) => (
              <option key={option} value={option}>
                {accountStatusLabel(option)}
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
              placeholder="用户名 / 姓名 / 邮箱"
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
          {canImport && !selfOnly ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={openImport}>
              <Icon name="upload" size={15} />
              导入账号
            </button>
          ) : null}
          {canManage ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
              <Icon name="plus" size={15} />
              新建账号
            </button>
          ) : null}
        </div>
      </form>
      )}

      <div className="card-body">
        <div className="table-toolbar">
          <label className="checkbox-row mask-toggle">
            <input
              type="checkbox"
              checked={mask}
              onChange={(event) => {
                setMask(event.target.checked)
                setPage(0)
              }}
            />
            <span>脱敏显示（邮箱 / 手机号，请求携带 mask=true）</span>
          </label>
        </div>
        <AsyncBlock
          loading={accounts.loading}
          error={accounts.error}
          onRetry={accounts.reload}
          empty={!items.length}
          emptyText={selfOnly ? '未找到本人账号' : '暂无账号'}
          emptyHint={
            selfOnly
              ? '请确认当前登录账号属于该租户，或联系租户管理员'
              : '点击「新建账号」创建租户内账号，或调整筛选条件'
          }
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>用户名</th>
                  <th>姓名</th>
                  <th>邮箱</th>
                  <th>手机号</th>
                  <th>组织</th>
                  <th>角色</th>
                  <th>状态</th>
                  <th>最近登录</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((account) => (
                  <tr key={account.id}>
                    <td className="mono">{account.username}</td>
                    <td>{account.displayName || '—'}</td>
                    <td className="muted nowrap">
                      {mask ? maskEmail(account.email) : account.email || '—'}
                    </td>
                    <td className="muted nowrap">
                      {mask ? maskPhone(account.phone) : account.phone || '—'}
                    </td>
                    <td className="muted">
                      {account.orgUnitName || (account.orgUnitId ? orgName.get(account.orgUnitId) : '') || '—'}
                    </td>
                    <td>
                      <Badge tone="gray">{accountRoleLabel(account.role)}</Badge>
                    </td>
                    <td>
                      <Badge tone={accountStatusTone(account.status)} dot>
                        {accountStatusLabel(account.status)}
                      </Badge>
                    </td>
                    <td className="muted nowrap">{formatDateTime(account.lastLoginAt)}</td>
                    <td className="align-right nowrap table-actions">
                      {isOwnAccount(account) && perms.canChangeOwnPassword ? (
                        <button
                          type="button"
                          className="link-btn"
                          aria-label="修改密码"
                          onClick={() => openChangePassword(account)}
                        >
                          修改密码
                        </button>
                      ) : null}
                      {canViewEventsFor(account) ? (
                        <button
                          type="button"
                          className="link-btn"
                          aria-label="登录历史"
                          onClick={() => openLoginHistory(account)}
                        >
                          登录历史
                        </button>
                      ) : null}
                      {canManage ? (
                        <>
                          <button
                            type="button"
                            className="link-btn"
                            aria-label="编辑账号"
                            onClick={() => openEdit(account)}
                          >
                            编辑
                          </button>
                          {account.status === 'LOCKED' ? (
                            <button
                              type="button"
                              className="link-btn"
                              aria-label="解锁账号"
                              disabled={rowBusyId === account.id}
                              onClick={() => unlockAccount(account)}
                            >
                              解锁
                            </button>
                          ) : account.status !== 'DELETED' ? (
                            <button
                              type="button"
                              className="link-btn"
                              aria-label={account.status === 'ACTIVE' ? '停用账号' : '启用账号'}
                              disabled={rowBusyId === account.id}
                              onClick={() => toggleStatus(account)}
                            >
                              {account.status === 'ACTIVE' ? '停用' : '启用'}
                            </button>
                          ) : null}
                          <button
                            type="button"
                            className="link-btn"
                            aria-label="重置密码"
                            disabled={rowBusyId === account.id}
                            onClick={() => resetPassword(account)}
                          >
                            重置密码
                          </button>
                          <button
                            type="button"
                            className="link-btn danger"
                            aria-label="删除账号"
                            onClick={() => setDeleteTarget(account)}
                          >
                            删除
                          </button>
                        </>
                      ) : null}
                      {!canManage && !(isOwnAccount(account) && perms.canChangeOwnPassword) && !canViewEventsFor(account) ? (
                        <span className="muted">只读</span>
                      ) : null}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          {!selfOnly ? (
            <Pager
              page={page}
              totalPages={accounts.data?.totalPages}
              total={pageTotal(accounts.data)}
              onPage={setPage}
              disabled={accounts.loading}
            />
          ) : null}
        </AsyncBlock>
      </div>

      <Drawer
        open={Boolean(editor)}
        title={editor?.mode === 'edit' ? '编辑账号' : '新建账号'}
        subtitle={
          editor?.mode === 'edit'
            ? `账号 ${editor.account?.username ?? ''}：用户名不可修改`
            : '未填写初始密码时，后端将生成一次性临时密码'
        }
        width={560}
        onClose={closeEditor}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={closeEditor} disabled={saving}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={submitEditor} disabled={saving}>
              {saving ? <span className="spinner spinner-light" /> : null}
              {saving ? '保存中…' : '保存'}
            </button>
          </>
        }
      >
        <div className="form-grid">
          <Field label="用户名" required={editor?.mode === 'create'} hint="租户内唯一，小写存储">
            <input
              className="input mono"
              value={username}
              disabled={editor?.mode === 'edit'}
              placeholder="zhangsan"
              onChange={(event) => {
                setUsername(event.target.value)
                setFormError(null)
              }}
            />
          </Field>
          <Field label="姓名" required>
            <input
              className="input"
              value={displayName}
              placeholder="张三"
              onChange={(event) => {
                setDisplayName(event.target.value)
                setFormError(null)
              }}
            />
          </Field>
          <Field label="邮箱">
            <input
              className="input"
              value={email}
              placeholder="zhangsan@acme.example"
              onChange={(event) => {
                setEmail(event.target.value)
                setFormError(null)
              }}
            />
          </Field>
          <Field label="手机号">
            <input
              className="input"
              value={phone}
              placeholder="+86 138 0000 0000"
              onChange={(event) => {
                setPhone(event.target.value)
                setFormError(null)
              }}
            />
          </Field>
          <Field label="所属组织">
            <select className="select" value={formOrgUnitId} onChange={(event) => setFormOrgUnitId(event.target.value)}>
              <option value="">（未分配）</option>
              {orgFlat.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {unit.name}（{unit.code}）
                </option>
              ))}
            </select>
          </Field>
          <Field label="角色">
            <select className="select" value={formRole} onChange={(event) => setFormRole(event.target.value)}>
              {roleOptions.map((option) => (
                <option key={option} value={option}>
                  {accountRoleLabel(option)}
                </option>
              ))}
            </select>
          </Field>
          {editor?.mode === 'create' ? (
            <Field label="初始密码" hint="可选；留空则生成一次性临时密码。至少 10 位含字母+数字">
              <input
                className="input mono"
                type="password"
                value={initialPassword}
                placeholder="可选"
                onChange={(event) => {
                  setInitialPassword(event.target.value)
                  setFormError(null)
                }}
              />
            </Field>
          ) : null}
        </div>
        {formError ? <p className="field-error modal-error">{formError}</p> : null}
      </Drawer>

      <Modal
        open={Boolean(tempPassword)}
        title="一次性临时密码"
        onClose={() => setTempPassword(null)}
        footer={
          <button type="button" className="btn btn-primary" onClick={() => setTempPassword(null)}>
            我已保存
          </button>
        }
      >
        <p className="temp-password-hint">
          该临时密码仅显示一次，请立即通过安全渠道转交账号本人，并提示其首次登录后修改密码。
        </p>
        <div className="temp-password">
          <code>{tempPassword}</code>
          <button type="button" className="btn btn-ghost btn-sm" onClick={copyTempPassword}>
            <Icon name="copy" size={14} />
            复制
          </button>
        </div>
      </Modal>

      <Modal
        open={Boolean(passwordAccount)}
        title="修改密码"
        description={
          passwordAccount
            ? `账号「${passwordAccount.username}」：修改成功后必须使用新密码登录。`
            : undefined
        }
        onClose={closeChangePassword}
        footer={
          <>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={closeChangePassword}
              disabled={changingPassword}
            >
              取消
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={submitChangePassword}
              disabled={changingPassword}
            >
              {changingPassword ? <span className="spinner spinner-light" /> : null}
              {changingPassword ? '提交中…' : '确认修改'}
            </button>
          </>
        }
      >
        <Field label="当前密码" required>
          <input
            className="input"
            type="password"
            value={oldPassword}
            autoComplete="current-password"
            placeholder="请输入当前密码"
            onChange={(event) => {
              setOldPassword(event.target.value)
              setPasswordError(null)
            }}
          />
        </Field>
        <Field label="新密码" required hint="至少 10 位，必须同时包含字母和数字，且不能与当前密码相同">
          <input
            className="input"
            type="password"
            value={newPassword}
            autoComplete="new-password"
            placeholder="请输入新密码"
            onChange={(event) => {
              setNewPassword(event.target.value)
              setPasswordError(null)
            }}
          />
        </Field>
        <Field label="确认新密码" required>
          <input
            className="input"
            type="password"
            value={confirmPassword}
            autoComplete="new-password"
            placeholder="请再次输入新密码"
            onChange={(event) => {
              setConfirmPassword(event.target.value)
              setPasswordError(null)
            }}
          />
        </Field>
        {passwordError ? <p className="field-error modal-error">{passwordError}</p> : null}
      </Modal>

      <Drawer
        open={Boolean(historyAccount)}
        title={historyAccount ? `登录历史 · ${historyAccount.username}` : '登录历史'}
        subtitle="来自 /api/tenants/{tenantId}/accounts/{accountId}/login-events（时间 / IP / UA / 结果）"
        width={700}
        onClose={closeLoginHistory}
        footer={
          <button type="button" className="btn btn-ghost" onClick={closeLoginHistory}>
            关闭
          </button>
        }
      >
        <AsyncBlock
          loading={loginEvents.loading}
          error={loginEvents.error}
          onRetry={loginEvents.reload}
          empty={!loginEventItems.length}
          emptyText="暂无登录记录"
          emptyHint="该账号成功或失败的登录都会记录 IP / User-Agent / 结果"
        >
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>时间</th>
                  <th>IP</th>
                  <th>User-Agent</th>
                  <th>结果</th>
                  <th>原因</th>
                </tr>
              </thead>
              <tbody>
                {loginEventItems.map((event, index) => (
                  <tr key={event.id ?? `${event.at ?? ''}-${index}`}>
                    <td className="muted nowrap">{formatDateTime(event.at)}</td>
                    <td className="mono nowrap">{event.ip || '—'}</td>
                    <td className="muted ua-cell" title={event.userAgent ?? undefined}>
                      {event.userAgent ? truncate(event.userAgent, 56) : '—'}
                    </td>
                    <td>
                      <Badge tone={event.success ? 'green' : 'red'} dot>
                        {event.success ? '成功' : '失败'}
                      </Badge>
                    </td>
                    <td className="muted">{event.reason || '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={historyPage}
            totalPages={loginEvents.data?.totalPages}
            total={pageTotal(loginEvents.data)}
            onPage={setHistoryPage}
            disabled={loginEvents.loading}
          />
        </AsyncBlock>
      </Drawer>

      <Modal
        open={importOpen}
        title="导入账号（CSV）"
        description="≤1000 行；已存在 username 跳过；失败行不中断。仅平台/租户管理员可批量导入。"
        width={720}
        onClose={() => {
          if (!importing) setImportOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={downloadAccountTemplate}>
              <Icon name="download" size={14} />
              下载模板
            </button>
            {importResult ? (
              <button type="button" className="btn btn-primary" onClick={() => setImportOpen(false)}>
                完成
              </button>
            ) : (
              <>
                <button type="button" className="btn btn-ghost" onClick={() => setImportOpen(false)} disabled={importing}>
                  取消
                </button>
                <button type="button" className="btn btn-primary" onClick={submitImport} disabled={importing || !importFile}>
                  {importing ? <span className="spinner spinner-light" /> : <Icon name="upload" size={14} />}
                  {importing ? '导入中…' : '开始导入'}
                </button>
              </>
            )}
          </>
        }
      >
        {!importResult ? (
          <>
            <div
              className="dropzone"
              onClick={() => importInputRef.current?.click()}
              role="button"
              tabIndex={0}
              onKeyDown={(event) => {
                if (event.key === 'Enter' || event.key === ' ') importInputRef.current?.click()
              }}
            >
              <input
                ref={importInputRef}
                type="file"
                accept=".csv,text/csv"
                hidden
                onChange={(event) => setImportFile(event.target.files?.[0] ?? null)}
              />
              <span className="dropzone-icon">
                <Icon name="upload" size={24} />
              </span>
              {importFile ? (
                <>
                  <strong>{importFile.name}</strong>
                  <span className="muted">{(importFile.size / 1024).toFixed(1)} KB</span>
                </>
              ) : (
                <>
                  <strong>点击选择账号 CSV</strong>
                  <span className="muted">表头：username,displayName,email,phone,orgCode,role</span>
                </>
              )}
            </div>
            {importError ? <p className="field-error">{importError}</p> : null}
          </>
        ) : (
          <div className="import-result">
            <div className="import-summary">
              <div><span>总数</span><strong>{importResult.total}</strong></div>
              <div><span>成功</span><strong className="text-green">{importResult.imported}</strong></div>
              <div><span>跳过</span><strong className="text-amber">{importResult.skipped}</strong></div>
              <div><span>失败</span><strong className={importResult.failed ? 'text-red' : ''}>{importResult.failed}</strong></div>
            </div>
            {importResult.errors?.length ? (
              <>
                <h4 className="block-title">失败明细</h4>
                <div className="table-wrap">
                  <table className="table table-compact">
                    <thead>
                      <tr><th>行号</th><th>错误信息</th></tr>
                    </thead>
                    <tbody>
                      {importResult.errors.map((error, index) => (
                        <tr key={index}>
                          <td className="mono">{error.row ?? '—'}</td>
                          <td className="muted">{error.message || '—'}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : null}
            {importResult.tempPasswords?.length ? (
              <>
                <h4 className="block-title">一次性临时密码（仅显示一次）</h4>
                <p className="field-hint">请立即通过安全渠道转交给对应用户，并提示首次登录后修改密码。</p>
                <div className="table-wrap">
                  <table className="table table-compact">
                    <thead>
                      <tr><th>行号</th><th>用户名</th><th>临时密码</th><th className="align-right">复制</th></tr>
                    </thead>
                    <tbody>
                      {importResult.tempPasswords.map((item, index) => (
                        <tr key={index}>
                          <td className="mono">{item.row ?? '—'}</td>
                          <td className="mono">{item.username}</td>
                          <td className="mono">{item.tempPassword}</td>
                          <td className="align-right">
                            <button type="button" className="link-btn" onClick={() => copyValue(item.tempPassword)}>
                              复制
                            </button>
                          </td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : null}
            {!importResult.errors?.length && !importResult.tempPasswords?.length ? (
              <p className="field-hint">全部记录处理成功。</p>
            ) : null}
          </div>
        )}
      </Modal>

      <ConfirmModal
        open={Boolean(deleteTarget)}
        title="删除账号"
        description={
          deleteTarget
            ? `将软删除账号「${deleteTarget.username}」（status=DELETED，deleted_at 记录时间），删除后不可恢复，操作会写入审计。`
            : undefined
        }
        confirmText="确认删除"
        danger
        loading={deleting}
        onCancel={() => {
          if (!deleting) setDeleteTarget(null)
        }}
        onConfirm={confirmDelete}
      />
    </div>
  )
}
