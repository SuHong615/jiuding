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
import { useEffect, useMemo, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type {
  PageResult,
  PermissionItem,
  RoleCreateRequest,
  RolePermissionsRequest,
  RoleView,
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

const GROUP_ORDER = ['TENANT', 'ORG', 'ACCOUNT', 'RATING', 'APPROVAL', 'SKILL', 'AGENT', 'MCP', 'PLATFORM', 'RBAC', 'ONBOARDING']
const GROUP_LABELS: Record<string, string> = {
  TENANT: '租户',
  ORG: '组织架构',
  ACCOUNT: '账号',
  RATING: '评级',
  APPROVAL: '审批',
  SKILL: '技能',
  AGENT: 'Agent',
  MCP: 'MCP',
  PLATFORM: '平台',
  RBAC: '角色权限',
  ONBOARDING: '入驻',
  OTHER: '其他',
}

function rolePermissionCodes(role: RoleView | undefined): string[] {
  if (!role) return []
  if (Array.isArray(role.permissionCodes)) return role.permissionCodes
  if (Array.isArray(role.permissions)) return role.permissions
  return []
}

export function PermissionsPage() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()

  const permissions = useAsync(async () => {
    const result = await api.get<PermissionItem[] | PageResult<PermissionItem>>('/api/platform/permissions')
    return Array.isArray(result) ? result : pageItems(result)
  }, [])
  const roles = useAsync(async () => {
    const result = await api.get<RoleView[] | PageResult<RoleView>>('/api/platform/roles')
    return Array.isArray(result) ? result : pageItems(result)
  }, [])

  const catalog = useMemo(() => permissions.data ?? [], [permissions.data])
  const roleList = useMemo(() => roles.data ?? [], [roles.data])
  const [selectedCode, setSelectedCode] = useState<string | null>(null)
  const [checked, setChecked] = useState<Set<string>>(new Set())
  const [saving, setSaving] = useState(false)
  const [deleteTarget, setDeleteTarget] = useState<RoleView | null>(null)
  const [deleting, setDeleting] = useState(false)

  const [createOpen, setCreateOpen] = useState(false)
  const [roleCode, setRoleCode] = useState('')
  const [roleName, setRoleName] = useState('')
  const [roleScope, setRoleScope] = useState('TENANT')
  const [roleDescription, setRoleDescription] = useState('')
  const [createError, setCreateError] = useState<string | null>(null)
  const [creating, setCreating] = useState(false)

  const selectedRole = roleList.find((role) => role.code === selectedCode) ?? roleList[0]

  useEffect(() => {
    if (!selectedCode && roleList.length) setSelectedCode(roleList[0].code)
  }, [roleList, selectedCode])

  useEffect(() => {
    setChecked(new Set(rolePermissionCodes(selectedRole)))
  }, [selectedRole?.code, selectedRole])

  const grouped = useMemo(() => {
    const map = new Map<string, PermissionItem[]>()
    catalog.forEach((permission) => {
      const group = permission.groupCode || 'OTHER'
      map.set(group, [...(map.get(group) ?? []), permission])
    })
    return Array.from(map.entries()).sort((a, b) => {
      const indexA = GROUP_ORDER.indexOf(a[0])
      const indexB = GROUP_ORDER.indexOf(b[0])
      return (indexA < 0 ? 99 : indexA) - (indexB < 0 ? 99 : indexB)
    })
  }, [catalog])

  const toggle = (code: string) => {
    setChecked((prev) => {
      const next = new Set(prev)
      if (next.has(code)) next.delete(code)
      else next.add(code)
      return next
    })
  }

  const toggleGroup = (items: PermissionItem[], allChecked: boolean) => {
    setChecked((prev) => {
      const next = new Set(prev)
      items.forEach((item) => {
        if (allChecked) next.delete(item.code)
        else next.add(item.code)
      })
      return next
    })
  }

  const saveMatrix = async () => {
    if (!selectedRole) return
    setSaving(true)
    try {
      const payload: RolePermissionsRequest = { permissionCodes: Array.from(checked) }
      await api.put(`/api/platform/roles/${encodeURIComponent(selectedRole.code)}/permissions`, payload)
      toast.success(`角色「${selectedRole.name}」权限已保存`)
      roles.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const createRole = async () => {
    setCreateError(null)
    if (!roleCode.trim()) {
      setCreateError('请输入角色编码')
      return
    }
    if (!/^[A-Z][A-Z0-9_]{1,63}$/.test(roleCode.trim())) {
      setCreateError('角色编码为 2-64 位大写字母/数字/下划线，且以字母开头')
      return
    }
    if (!roleName.trim()) {
      setCreateError('请输入角色名称')
      return
    }
    setCreating(true)
    try {
      const payload: RoleCreateRequest = {
        code: roleCode.trim(),
        name: roleName.trim(),
        scope: roleScope,
        description: roleDescription.trim() || undefined,
      }
      const created = await api.post<RoleView>('/api/platform/roles', payload)
      toast.success('自定义角色已创建')
      setCreateOpen(false)
      roles.reload()
      if (created?.code) setSelectedCode(created.code)
    } catch (err) {
      setCreateError(errorMessage(err))
    } finally {
      setCreating(false)
    }
  }

  const deleteRole = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/platform/roles/${encodeURIComponent(deleteTarget.code)}`)
      toast.success('自定义角色已删除')
      if (selectedRole?.code === deleteTarget.code) setSelectedCode(null)
      setDeleteTarget(null)
      roles.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  if (!isPlatformAdmin) {
    return (
      <div className="page">
        <SectionCard title="权限管理">
          <p className="muted">仅平台管理员可访问角色与权限管理。</p>
        </SectionCard>
      </div>
    )
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>权限管理</h2>
            <p>维护角色与权限矩阵；导航与操作按 /api/me/permissions 收敛（接口不可用时回退角色判断）。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { roles.reload(); permissions.reload() }}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={() => {
            setRoleCode('')
            setRoleName('')
            setRoleScope('TENANT')
            setRoleDescription('')
            setCreateError(null)
            setCreateOpen(true)
          }}>
            <Icon name="plus" size={15} />
            新建角色
          </button>
        </div>
      </header>

      <div className="permissions-layout">
        <SectionCard title="角色" description="内置角色不可删除；自定义角色可维护权限矩阵">
          <AsyncBlock
            loading={roles.loading}
            error={roles.error}
            onRetry={roles.reload}
            empty={!roleList.length}
            emptyText="暂无角色"
          >
            <ul className="role-list">
              {roleList.map((role) => (
                <li key={role.code}>
                  <button
                    type="button"
                    className={`role-item ${selectedRole?.code === role.code ? 'active' : ''}`}
                    onClick={() => setSelectedCode(role.code)}
                  >
                    <span className="role-item-main">
                      <strong>{role.name}</strong>
                      <span className="mono">{role.code}</span>
                    </span>
                    <span className="role-item-side">
                      <Badge tone={role.builtin ? 'gray' : 'blue'}>{role.builtin ? '内置' : '自定义'}</Badge>
                      <span className="muted">{role.scope || '—'}</span>
                    </span>
                  </button>
                </li>
              ))}
            </ul>
          </AsyncBlock>
        </SectionCard>

        <SectionCard
          title={selectedRole ? `权限矩阵 · ${selectedRole.name}` : '权限矩阵'}
          description={
            selectedRole
              ? `角色编码 ${selectedRole.code} · 已选 ${checked.size} 项权限`
              : '请先在左侧选择角色'
          }
          actions={
            <div className="card-head-actions">
              {selectedRole && !selectedRole.builtin ? (
                <button type="button" className="btn btn-danger-ghost btn-sm" onClick={() => setDeleteTarget(selectedRole)}>
                  删除角色
                </button>
              ) : null}
              <button
                type="button"
                className="btn btn-primary btn-sm"
                onClick={saveMatrix}
                disabled={!selectedRole || saving}
              >
                {saving ? <span className="spinner spinner-light" /> : null}
                {saving ? '保存中…' : '保存权限'}
              </button>
            </div>
          }
        >
          <AsyncBlock
            loading={permissions.loading}
            error={permissions.error}
            onRetry={permissions.reload}
            empty={!catalog.length}
            emptyText="暂无权限目录"
            emptyHint="请确认 /api/platform/permissions 已实现"
          >
            {!selectedRole ? (
              <p className="muted">请先在左侧选择一个角色。</p>
            ) : (
              <div className="permission-groups">
                {grouped.map(([group, items]) => {
                  const allChecked = items.every((item) => checked.has(item.code))
                  return (
                    <section key={group} className="permission-group">
                      <header className="permission-group-head">
                        <strong>{GROUP_LABELS[group] ?? group}</strong>
                        <span className="muted">{items.length} 项</span>
                        <button type="button" className="link-btn" onClick={() => toggleGroup(items, allChecked)}>
                          {allChecked ? '取消全选' : '全选'}
                        </button>
                      </header>
                      <div className="permission-grid">
                        {items.map((item) => (
                          <label key={item.code} className={`permission-item ${checked.has(item.code) ? 'checked' : ''}`}>
                            <input
                              type="checkbox"
                              checked={checked.has(item.code)}
                              onChange={() => toggle(item.code)}
                            />
                            <span>
                              <strong>{item.name || item.code}</strong>
                              <em className="mono">{item.code}</em>
                              {item.description ? <small>{item.description}</small> : null}
                            </span>
                          </label>
                        ))}
                      </div>
                    </section>
                  )
                })}
              </div>
            )}
          </AsyncBlock>
        </SectionCard>
      </div>

      <Modal
        open={createOpen}
        title="新建自定义角色"
        description="自定义角色可分配权限矩阵；scope 决定平台级或租户级。"
        onClose={() => {
          if (!creating) setCreateOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setCreateOpen(false)} disabled={creating}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={createRole} disabled={creating}>
              {creating ? <span className="spinner spinner-light" /> : null}
              创建角色
            </button>
          </>
        }
      >
        <div className="form-grid">
          <Field label="角色编码" required hint="大写字母/数字/下划线，如 TENANT_OPS">
            <input className="input mono" value={roleCode} onChange={(event) => setRoleCode(event.target.value)} />
          </Field>
          <Field label="角色名称" required>
            <input className="input" value={roleName} onChange={(event) => setRoleName(event.target.value)} />
          </Field>
          <Field label="Scope">
            <select className="select" value={roleScope} onChange={(event) => setRoleScope(event.target.value)}>
              <option value="TENANT">TENANT</option>
              <option value="PLATFORM">PLATFORM</option>
            </select>
          </Field>
          <Field label="描述">
            <input className="input" value={roleDescription} onChange={(event) => setRoleDescription(event.target.value)} />
          </Field>
        </div>
        {createError ? <p className="field-error modal-error">{createError}</p> : null}
      </Modal>

      <ConfirmModal
        open={Boolean(deleteTarget)}
        title="删除自定义角色"
        description={deleteTarget ? `将删除角色「${deleteTarget.name}」（${deleteTarget.code}），已绑定该角色的账号需重新分配。` : undefined}
        confirmText="确认删除"
        danger
        loading={deleting}
        onCancel={() => {
          if (!deleting) setDeleteTarget(null)
        }}
        onConfirm={deleteRole}
      />
    </div>
  )
}
