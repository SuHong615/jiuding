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
import type { ImportResult, OrgUnitCreatePayload, OrgUnitUpdatePayload, OrgUnitView } from '../../api/types'
import { useAsync } from '../../hooks/useAsync'
import { AsyncBlock } from '../../components/PageState'
import { Badge } from '../../components/Badge'
import { ConfirmModal } from '../../components/ConfirmModal'
import { Field, SectionCard } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { Modal } from '../../components/Modal'
import { useToast } from '../../components/Toast'
import { formatDateTime } from '../../utils/format'
import { friendlyErrorMessage, friendlyQuotaText } from '../../utils/errors'
import { ORG_TYPE_OPTIONS, orgStatusLabel, orgStatusTone, orgTypeLabel } from '../../utils/labels'
import {
  collectDescendantIds,
  flattenOrgUnits,
  normalizeOrgTree,
  orgAccountCount,
  orgChildCount,
} from '../../utils/orgTree'

type FormMode = 'create' | 'edit' | 'move'

interface TreeNodeProps {
  node: OrgUnitView
  depth: number
  selectedId: string | null
  collapsed: Set<string>
  canManage: boolean
  onToggle: (id: string) => void
  onSelect: (id: string) => void
  onAddChild: (node: OrgUnitView) => void
  onEdit: (node: OrgUnitView) => void
  onMove: (node: OrgUnitView) => void
  onDelete: (node: OrgUnitView) => void
}

function OrgTreeNode({
  node,
  depth,
  selectedId,
  collapsed,
  canManage,
  onToggle,
  onSelect,
  onAddChild,
  onEdit,
  onMove,
  onDelete,
}: TreeNodeProps) {
  const children = node.children ?? []
  const isCollapsed = collapsed.has(node.id)
  const childCount = orgChildCount(node)
  const members = orgAccountCount(node)
  const deleteDisabled = childCount > 0 || members > 0
  const deleteTitle = deleteDisabled
    ? `存在 ${childCount} 个子部门 / ${members} 个成员，删除前需先清空`
    : '删除部门'

  return (
    <li className="org-tree-node">
      <div className={`org-tree-row ${selectedId === node.id ? 'active' : ''}`} style={{ paddingLeft: depth * 16 + 4 }}>
        <button
          type="button"
          className="org-tree-toggle"
          onClick={() => onToggle(node.id)}
          disabled={!children.length}
          aria-label={children.length ? (isCollapsed ? '展开子部门' : '收起子部门') : '无子部门'}
        >
          {children.length ? (
            <Icon name={isCollapsed ? 'chevronRight' : 'chevronDown'} size={14} />
          ) : (
            <span className="tree-dot" />
          )}
        </button>
        <button type="button" className="org-tree-label" onClick={() => onSelect(node.id)} title={`${node.name}（${node.code}）`}>
          <span className="org-tree-name">{node.name}</span>
          <span className="org-tree-code mono">{node.code}</span>
        </button>
        <span className="org-tree-count" title={`成员 ${members} 人`}>
          <Icon name="user" size={12} />
          {members}
        </span>
        {canManage ? (
          <span className="org-tree-actions">
            <button
              type="button"
              className="icon-btn icon-btn-xs"
              title="新增子部门"
              aria-label="新增子部门"
              onClick={() => onAddChild(node)}
            >
              <Icon name="plus" size={13} />
            </button>
            <button
              type="button"
              className="icon-btn icon-btn-xs"
              title="编辑部门"
              aria-label="编辑部门"
              onClick={() => onEdit(node)}
            >
              <Icon name="edit" size={13} />
            </button>
            <button
              type="button"
              className="icon-btn icon-btn-xs"
              title="移动部门"
              aria-label="移动部门"
              onClick={() => onMove(node)}
            >
              <Icon name="move" size={13} />
            </button>
            <button
              type="button"
              className="icon-btn icon-btn-xs danger"
              title={deleteTitle}
              aria-label="删除部门"
              disabled={deleteDisabled}
              onClick={() => onDelete(node)}
            >
              <Icon name="trash" size={13} />
            </button>
          </span>
        ) : null}
      </div>
      {children.length && !isCollapsed ? (
        <ul className="org-tree-children">
          {children.map((child) => (
            <OrgTreeNode
              key={child.id}
              node={child}
              depth={depth + 1}
              selectedId={selectedId}
              collapsed={collapsed}
              canManage={canManage}
              onToggle={onToggle}
              onSelect={onSelect}
              onAddChild={onAddChild}
              onEdit={onEdit}
              onMove={onMove}
              onDelete={onDelete}
            />
          ))}
        </ul>
      ) : null}
    </li>
  )
}

export function TenantOrgTab({
  tenantId,
  canManage,
  canCreateRoot = false,
  canImport = false,
}: {
  tenantId: string
  canManage: boolean
  canCreateRoot?: boolean
  canImport?: boolean
}) {
  const toast = useToast()
  const units = useAsync(
    () => api.get<OrgUnitView[]>(`/api/tenants/${tenantId}/org-units?format=tree`),
    [tenantId],
  )
  const tree = useMemo(() => normalizeOrgTree(units.data ?? []), [units.data])
  const flat = useMemo(() => flattenOrgUnits(tree), [tree])

  const [collapsed, setCollapsed] = useState<Set<string>>(new Set())
  const [selectedId, setSelectedId] = useState<string | null>(null)
  const selected = flat.find((unit) => unit.id === selectedId) ?? flat[0] ?? null

  const [formMode, setFormMode] = useState<FormMode | null>(null)
  const [formParent, setFormParent] = useState<OrgUnitView | null>(null)
  const [formUnit, setFormUnit] = useState<OrgUnitView | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [type, setType] = useState('DEPARTMENT')
  const [sortOrder, setSortOrder] = useState('')
  const [manager, setManager] = useState('')
  const [moveTarget, setMoveTarget] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [deleteTarget, setDeleteTarget] = useState<OrgUnitView | null>(null)
  const [deleting, setDeleting] = useState(false)

  // v5：组织 CSV 导入
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

  const downloadOrgTemplate = async () => {
    try {
      await downloadFile(
        `/api/tenants/${tenantId}/import-templates/org-units.csv`,
        'org-units-template.csv',
      )
      toast.success('组织导入模板已开始下载')
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
        `/api/tenants/${tenantId}/org-units/import`,
        form,
      )
      setImportResult(result)
      toast.success(`导入完成：成功 ${result?.imported ?? 0} 条`)
      units.reload()
    } catch (err) {
      setImportError(friendlyErrorMessage(err))
    } finally {
      setImporting(false)
    }
  }

  const toggle = (id: string) =>
    setCollapsed((prev) => {
      const next = new Set(prev)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  const openCreate = (parent: OrgUnitView | null) => {
    setFormMode('create')
    setFormParent(parent)
    setFormUnit(null)
    setCode('')
    setName('')
    setType(parent ? (parent.type === 'COMPANY' ? 'BRANCH' : 'DEPARTMENT') : 'COMPANY')
    setSortOrder('')
    setManager('')
    setFormError(null)
  }

  const openEdit = (unit: OrgUnitView) => {
    setFormMode('edit')
    setFormUnit(unit)
    setFormParent(null)
    setName(unit.name)
    setType(unit.type || 'DEPARTMENT')
    setSortOrder(unit.sortOrder != null ? String(unit.sortOrder) : '')
    setManager(unit.managerAccountId ?? '')
    setFormError(null)
  }

  const openMove = (unit: OrgUnitView) => {
    setFormMode('move')
    setFormUnit(unit)
    setMoveTarget(unit.parentId ?? '')
    setFormError(null)
  }

  const closeForm = () => {
    if (saving) return
    setFormMode(null)
    setFormUnit(null)
    setFormParent(null)
    setFormError(null)
  }

  const moveOptions = useMemo(() => {
    if (!formUnit) return []
    const excluded = new Set(collectDescendantIds(formUnit))
    return flat.filter((unit) => !excluded.has(unit.id))
  }, [flat, formUnit])

  const submitForm = async () => {
    if (!formMode) return
    setFormError(null)

    if (formMode === 'move') {
      if (!formUnit) return
      if (moveTarget && collectDescendantIds(formUnit).includes(moveTarget)) {
        setFormError('目标部门不能是自身或其子部门')
        return
      }
      setSaving(true)
      try {
        await api.post<OrgUnitView>(`/api/tenants/${tenantId}/org-units/${formUnit.id}/move`, {
          parentId: moveTarget || null,
        })
        toast.success('部门已移动')
        setFormMode(null)
        units.reload()
      } catch (err) {
        setFormError(friendlyErrorMessage(err))
      } finally {
        setSaving(false)
      }
      return
    }

    if (formMode === 'create') {
      if (!code.trim()) {
        setFormError('请输入部门编码')
        return
      }
      if (!/^[a-zA-Z0-9][a-zA-Z0-9_-]{0,63}$/.test(code.trim())) {
        setFormError('编码需为 1-64 位字母/数字/-/_，并以字母或数字开头')
        return
      }
    }
    if (!name.trim()) {
      setFormError('请输入部门名称')
      return
    }
    const sortValue = sortOrder.trim() ? Number(sortOrder) : undefined
    if (sortValue !== undefined && (!Number.isInteger(sortValue) || sortValue < 0)) {
      setFormError('排序值必须为非负整数')
      return
    }

    setSaving(true)
    try {
      if (formMode === 'create') {
        const payload: OrgUnitCreatePayload = {
          parentId: formParent?.id ?? null,
          code: code.trim(),
          name: name.trim(),
          type,
          sortOrder: sortValue,
          managerAccountId: manager.trim() || null,
        }
        const created = await api.post<OrgUnitView>(`/api/tenants/${tenantId}/org-units`, payload)
        toast.success('子部门已创建')
        setFormMode(null)
        units.reload()
        if (created?.id) setSelectedId(created.id)
        if (formParent) {
          setCollapsed((prev) => {
            const next = new Set(prev)
            next.delete(formParent.id)
            return next
          })
        }
      } else if (formMode === 'edit' && formUnit) {
        const payload: OrgUnitUpdatePayload = {
          name: name.trim(),
          type,
          sortOrder: sortValue,
          managerAccountId: manager.trim() || null,
        }
        await api.put<OrgUnitView>(`/api/tenants/${tenantId}/org-units/${formUnit.id}`, payload)
        toast.success('部门信息已更新')
        setFormMode(null)
        units.reload()
      }
    } catch (err) {
      setFormError(friendlyErrorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const confirmDelete = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/tenants/${tenantId}/org-units/${deleteTarget.id}`)
      toast.success('部门已删除')
      if (selectedId === deleteTarget.id) setSelectedId(null)
      setDeleteTarget(null)
      units.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  const parentName = (unit: OrgUnitView | null): string => {
    if (!unit?.parentId) return '（根级）'
    return flat.find((item) => item.id === unit.parentId)?.name ?? unit.parentId
  }

  const formTitle = formMode === 'create' ? '新增子部门' : formMode === 'edit' ? '编辑部门' : '移动部门'

  return (
    <SectionCard
      title="组织架构"
      description="物化路径 + 邻接表；支持新增、编辑、移动、删除（有子节点或成员时不可删除）"
      actions={
        <div className="card-head-actions">
          <button type="button" className="icon-btn" onClick={units.reload} title="刷新组织架构">
            <Icon name="refresh" size={15} />
          </button>
          {canImport ? (
            <button type="button" className="btn btn-ghost btn-sm" onClick={openImport}>
              <Icon name="upload" size={15} />
              导入组织
            </button>
          ) : null}
          {canManage && canCreateRoot ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={() => openCreate(null)}>
              <Icon name="plus" size={15} />
              新建根部门
            </button>
          ) : null}
        </div>
      }
    >
      <AsyncBlock
        loading={units.loading}
        error={units.error}
        onRetry={units.reload}
        empty={!tree.length}
        emptyText="暂无组织架构"
        emptyHint="创建根部门后即可逐步搭建公司 / 分支 / 部门 / 团队层级"
      >
        <div className="org-layout">
          <div className="org-tree-panel">
            <div className="org-tree-head">
              <span>组织树</span>
              <span className="muted">{flat.length} 个节点</span>
            </div>
            <ul className="org-tree">
              {tree.map((node) => (
                <OrgTreeNode
                  key={node.id}
                  node={node}
                  depth={0}
                  selectedId={selected?.id ?? null}
                  collapsed={collapsed}
                  canManage={canManage}
                  onToggle={toggle}
                  onSelect={setSelectedId}
                  onAddChild={openCreate}
                  onEdit={openEdit}
                  onMove={openMove}
                  onDelete={setDeleteTarget}
                />
              ))}
            </ul>
          </div>

          <div className="org-detail-panel">
            {selected ? (
              <>
                <header className="org-detail-head">
                  <div>
                    <h3>{selected.name}</h3>
                    <p className="mono">{selected.code}</p>
                  </div>
                  <div className="org-detail-badges">
                    <Badge tone="gray">{orgTypeLabel(selected.type)}</Badge>
                    <Badge tone={orgStatusTone(selected.status)} dot>
                      {orgStatusLabel(selected.status)}
                    </Badge>
                  </div>
                </header>
                <div className="info-grid">
                  <div className="info-row">
                    <span className="info-label">上级部门</span>
                    <span className="info-value">{parentName(selected)}</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">层级</span>
                    <span className="info-value">{selected.depth != null ? `第 ${selected.depth + 1} 层` : '—'}</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">负责人</span>
                    <span className="info-value">{selected.managerName || selected.managerAccountId || '—'}</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">成员数</span>
                    <span className="info-value">{orgAccountCount(selected)} 人</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">子部门</span>
                    <span className="info-value">{orgChildCount(selected)} 个</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">排序值</span>
                    <span className="info-value">{selected.sortOrder ?? '—'}</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">路径</span>
                    <span className="info-value mono org-path">{selected.path || '—'}</span>
                  </div>
                  <div className="info-row">
                    <span className="info-label">更新时间</span>
                    <span className="info-value">{formatDateTime(selected.updatedAt)}</span>
                  </div>
                </div>

                {canManage ? (
                  <div className="org-detail-actions">
                    <button
                      type="button"
                      className="btn btn-primary btn-sm"
                      onClick={() => openCreate(selected)}
                      disabled={(selected.depth ?? 0) >= 6}
                      title={(selected.depth ?? 0) >= 6 ? '已达最大层级（6 层）' : '新增子部门'}
                    >
                      <Icon name="plus" size={14} />
                      新增子部门
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => openEdit(selected)}>
                      <Icon name="edit" size={14} />
                      编辑
                    </button>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => openMove(selected)}>
                      <Icon name="move" size={14} />
                      移动
                    </button>
                    <button
                      type="button"
                      className="btn btn-danger-ghost btn-sm"
                      disabled={orgChildCount(selected) > 0 || orgAccountCount(selected) > 0}
                      title={
                        orgChildCount(selected) > 0 || orgAccountCount(selected) > 0
                          ? `存在 ${orgChildCount(selected)} 个子部门 / ${orgAccountCount(selected)} 个成员，删除前需先清空`
                          : '删除该部门'
                      }
                      onClick={() => setDeleteTarget(selected)}
                    >
                      <Icon name="trash" size={14} />
                      删除
                    </button>
                    {(selected.depth ?? 0) >= 6 ? <span className="field-hint">已达最大层级（6 层）</span> : null}
                    {orgChildCount(selected) > 0 || orgAccountCount(selected) > 0 ? (
                      <span className="field-hint">
                        删除前需先移除 {orgChildCount(selected)} 个子部门 / {orgAccountCount(selected)} 个成员
                      </span>
                    ) : null}
                  </div>
                ) : null}
              </>
            ) : (
              <p className="muted">请选择左侧组织节点查看详情。</p>
            )}
          </div>
        </div>
      </AsyncBlock>

      <Modal
        open={importOpen}
        title="导入组织（CSV）"
        description="≤1000 行；按 parentCode 拓扑创建；已存在 code 跳过；失败行不中断。仅平台/租户管理员可批量导入。"
        width={700}
        onClose={() => {
          if (!importing) setImportOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={downloadOrgTemplate}>
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
                  <strong>点击选择组织 CSV</strong>
                  <span className="muted">表头：parentCode,code,name,type,sortOrder,managerUsername</span>
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
                          <td className="muted">{friendlyQuotaText(error.message)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </>
            ) : (
              <p className="field-hint">全部记录处理成功。</p>
            )}
          </div>
        )}
      </Modal>

      <Modal
        open={formMode !== null}
        title={formTitle}
        description={
          formMode === 'create'
            ? `上级：${formParent ? `${formParent.name}（${formParent.code}）` : '根级'}`
            : formMode === 'move'
              ? '选择新的上级部门（不能移动到自身或其子部门下）'
              : '修改部门名称、类型、排序值与负责人'
        }
        onClose={closeForm}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={closeForm} disabled={saving}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={submitForm} disabled={saving}>
              {saving ? <span className="spinner spinner-light" /> : null}
              {formMode === 'move' ? '确认移动' : '保存'}
            </button>
          </>
        }
      >
        {formMode === 'move' ? (
          <Field label="目标上级部门">
            <select className="select" value={moveTarget} onChange={(event) => setMoveTarget(event.target.value)}>
              <option value="">（根级）</option>
              {moveOptions.map((unit) => (
                <option key={unit.id} value={unit.id}>
                  {`${'　'.repeat(unit.depth ?? 0)}${unit.name}（${unit.code}）`}
                </option>
              ))}
            </select>
          </Field>
        ) : (
          <div className="form-grid">
            {formMode === 'create' ? (
              <Field label="部门编码" required hint="租户内唯一，字母/数字/-/_">
                <input
                  className="input"
                  value={code}
                  placeholder="e.g. rd-center"
                  onChange={(event) => {
                    setCode(event.target.value)
                    setFormError(null)
                  }}
                />
              </Field>
            ) : null}
            <Field label="部门名称" required>
              <input
                className="input"
                value={name}
                placeholder="研发中心"
                onChange={(event) => {
                  setName(event.target.value)
                  setFormError(null)
                }}
              />
            </Field>
            <Field label="类型">
              <select className="select" value={type} onChange={(event) => setType(event.target.value)}>
                {ORG_TYPE_OPTIONS.map((option) => (
                  <option key={option} value={option}>
                    {orgTypeLabel(option)}
                  </option>
                ))}
              </select>
            </Field>
            <Field label="排序值" hint="升序排列，可留空">
              <input
                className="input"
                value={sortOrder}
                inputMode="numeric"
                placeholder="0"
                onChange={(event) => setSortOrder(event.target.value)}
              />
            </Field>
            <Field label="负责人账号 ID" hint="可选，填写租户账号 ID">
              <input
                className="input mono"
                value={manager}
                placeholder="account-id"
                onChange={(event) => setManager(event.target.value)}
              />
            </Field>
          </div>
        )}
        {formError ? <p className="field-error modal-error">{formError}</p> : null}
      </Modal>

      <ConfirmModal
        open={Boolean(deleteTarget)}
        title="删除部门"
        description={
          deleteTarget
            ? `将删除部门「${deleteTarget.name}」。仅当该部门无子部门且无成员时可删除，删除会写入审计。`
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
    </SectionCard>
  )
}
