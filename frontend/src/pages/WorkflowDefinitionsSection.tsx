import { useMemo, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type {
  ApprovalWorkflowPayload,
  ApprovalWorkflowView,
  PageResult,
  WorkflowValidateResult,
} from '../api/types'
import { pageItems } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Drawer } from '../components/Drawer'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'

const DEFAULT_DEFINITION = `{
  "nodes": [
    { "id": "risk", "type": "AUTO_CHECK", "skill": "risk-review", "next": "approval" },
    { "id": "approval", "type": "HUMAN", "role": "APPROVER", "slaHours": 24, "next": null }
  ],
  "transitions": [],
  "compensations": []
}`

const NODE_TYPES = ['AUTO_CHECK', 'HUMAN', 'PARALLEL', 'SLA']

function statusTone(status?: string | null) {
  if (status === 'PUBLISHED') return 'green' as const
  if (status === 'DRAFT') return 'gray' as const
  if (status === 'ARCHIVED') return 'amber' as const
  return 'gray' as const
}

export function WorkflowDefinitionsSection() {
  const { isPlatformAdmin } = useAuth()
  const toast = useToast()

  const workflows = useAsync(async () => {
    if (!isPlatformAdmin) return []
    const result = await api.get<ApprovalWorkflowView[] | PageResult<ApprovalWorkflowView>>(
      '/api/approval-workflows',
    )
    return Array.isArray(result) ? result : pageItems(result)
  }, [isPlatformAdmin])
  const items = workflows.data ?? []

  const [editorOpen, setEditorOpen] = useState(false)
  const [editing, setEditing] = useState<ApprovalWorkflowView | null>(null)
  const [code, setCode] = useState('')
  const [name, setName] = useState('')
  const [definitionText, setDefinitionText] = useState(DEFAULT_DEFINITION)
  const [editorError, setEditorError] = useState<string | null>(null)
  const [saving, setSaving] = useState(false)

  const [busyId, setBusyId] = useState<string | null>(null)
  const [deleteTarget, setDeleteTarget] = useState<ApprovalWorkflowView | null>(null)
  const [deleting, setDeleting] = useState(false)
  const [validateResult, setValidateResult] = useState<WorkflowValidateResult | null>(null)
  const [validateTarget, setValidateTarget] = useState<ApprovalWorkflowView | null>(null)

  const parsedNodes = useMemo(() => {
    try {
      const parsed = JSON.parse(definitionText) as { nodes?: Array<Record<string, unknown>> }
      return Array.isArray(parsed?.nodes) ? parsed.nodes : []
    } catch {
      return []
    }
  }, [definitionText])

  if (!isPlatformAdmin) return null

  const openCreate = () => {
    setEditing(null)
    setCode('')
    setName('')
    setDefinitionText(DEFAULT_DEFINITION)
    setEditorError(null)
    setEditorOpen(true)
  }

  const openEdit = (workflow: ApprovalWorkflowView) => {
    setEditing(workflow)
    setCode(workflow.code)
    setName(workflow.name)
    let text = workflow.definitionJson || ''
    if (!text && workflow.definition) {
      try {
        text = JSON.stringify(workflow.definition, null, 2)
      } catch {
        text = DEFAULT_DEFINITION
      }
    }
    if (!text) text = DEFAULT_DEFINITION
    try {
      text = JSON.stringify(JSON.parse(text), null, 2)
    } catch {
      /* 保留原文本，保存时再校验 */
    }
    setDefinitionText(text)
    setEditorError(null)
    setEditorOpen(true)
  }

  const save = async () => {
    setEditorError(null)
    if (!code.trim() || !name.trim()) {
      setEditorError('请填写流程 code 与名称')
      return
    }
    let parsed: unknown
    try {
      parsed = JSON.parse(definitionText)
    } catch (err) {
      setEditorError(`definition JSON 解析失败：${err instanceof Error ? err.message : '格式错误'}`)
      return
    }
    if (!parsed || typeof parsed !== 'object' || !Array.isArray((parsed as { nodes?: unknown }).nodes)) {
      setEditorError('definition 必须包含 nodes 数组')
      return
    }
    setSaving(true)
    try {
      const payload: ApprovalWorkflowPayload = {
        code: code.trim(),
        name: name.trim(),
        definitionJson: JSON.stringify(parsed),
      }
      if (editing) {
        await api.put<ApprovalWorkflowView>(`/api/approval-workflows/${editing.id}`, payload)
        toast.success('流程定义已更新')
      } else {
        await api.post<ApprovalWorkflowView>('/api/approval-workflows', payload)
        toast.success('流程定义已创建')
      }
      setEditorOpen(false)
      workflows.reload()
    } catch (err) {
      setEditorError(errorMessage(err))
    } finally {
      setSaving(false)
    }
  }

  const validate = async (workflow: ApprovalWorkflowView) => {
    setBusyId(workflow.id)
    try {
      const result = await api.post<WorkflowValidateResult>(
        `/api/approval-workflows/${workflow.id}/validate`,
        {},
      )
      setValidateTarget(workflow)
      setValidateResult(result)
      if (result?.valid || result?.ok) toast.success('流程定义校验通过')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const publish = async (workflow: ApprovalWorkflowView) => {
    setBusyId(workflow.id)
    try {
      await api.post<ApprovalWorkflowView>(`/api/approval-workflows/${workflow.id}/publish`, {})
      toast.success('流程定义已发布，后续审批将按新版本执行')
      workflows.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const remove = async () => {
    if (!deleteTarget) return
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/approval-workflows/${deleteTarget.id}`)
      toast.success('流程定义已删除')
      setDeleteTarget(null)
      workflows.reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  return (
    <>
      <SectionCard
        title="流程定义"
        description="平台管理员维护审批 DAG（节点 type/skill/role/slaHours/next），支持校验与发布；未发布定义不影响默认流程。"
        actions={
          <div className="card-head-actions">
            <button type="button" className="btn btn-ghost btn-sm" onClick={workflows.reload}>
              <Icon name="refresh" size={15} />
              刷新
            </button>
            <button type="button" className="btn btn-primary btn-sm" onClick={openCreate}>
              <Icon name="plus" size={15} />
              新建流程
            </button>
          </div>
        }
      >
        <AsyncBlock
          loading={workflows.loading}
          error={workflows.error}
          onRetry={workflows.reload}
          empty={!items.length}
          emptyText="暂无流程定义"
          emptyHint="新建流程后可校验并发布，审批引擎将按已发布定义执行"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Code</th>
                  <th>名称</th>
                  <th>版本</th>
                  <th>状态</th>
                  <th>更新时间</th>
                  <th>发布人</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {items.map((workflow) => (
                  <tr key={workflow.id}>
                    <td className="mono">{workflow.code}</td>
                    <td>{workflow.name}</td>
                    <td className="mono">v{workflow.version ?? '—'}</td>
                    <td>
                      <Badge tone={statusTone(workflow.status)} dot>
                        {workflow.status || 'DRAFT'}
                      </Badge>
                    </td>
                    <td className="muted nowrap">{formatDateTime(workflow.updatedAt || workflow.createdAt)}</td>
                    <td className="muted">{workflow.createdBy || '—'}</td>
                    <td className="align-right nowrap">
                      <button type="button" className="link-btn" onClick={() => openEdit(workflow)}>
                        编辑
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        disabled={busyId === workflow.id}
                        onClick={() => validate(workflow)}
                      >
                        校验
                      </button>
                      <button
                        type="button"
                        className="link-btn"
                        disabled={busyId === workflow.id}
                        onClick={() => publish(workflow)}
                      >
                        发布
                      </button>
                      <button
                        type="button"
                        className="link-btn danger"
                        onClick={() => setDeleteTarget(workflow)}
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
        open={editorOpen}
        title={editing ? `编辑流程定义 · ${editing.code}` : '新建流程定义'}
        subtitle="definition JSON 包含 nodes（DAG）；支持节点 id/type/skill/role/slaHours/next"
        width={860}
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
              保存草稿
            </button>
          </>
        }
      >
        <div className="form-grid form-grid-2">
          <Field label="Code" required hint="唯一标识，如 default-approval">
            <input
              className="input mono"
              value={code}
              disabled={Boolean(editing)}
              onChange={(event) => setCode(event.target.value)}
            />
          </Field>
          <Field label="名称" required>
            <input className="input" value={name} onChange={(event) => setName(event.target.value)} />
          </Field>
        </div>
        <Field
          label="definition JSON"
          required
          hint={`节点类型：${NODE_TYPES.join(' / ')}；保存前会校验 JSON 与 nodes 数组`}
        >
          <textarea
            className="input json-editor"
            rows={16}
            value={definitionText}
            spellCheck={false}
            onChange={(event) => {
              setDefinitionText(event.target.value)
              setEditorError(null)
            }}
          />
        </Field>
        {editorError ? <p className="field-error modal-error">{editorError}</p> : null}

        <h4 className="block-title">节点预览</h4>
        {parsedNodes.length ? (
          <div className="table-wrap">
            <table className="table table-compact">
              <thead>
                <tr>
                  <th>id</th>
                  <th>type</th>
                  <th>skill</th>
                  <th>role</th>
                  <th>slaHours</th>
                  <th>next</th>
                </tr>
              </thead>
              <tbody>
                {parsedNodes.map((node, index) => (
                  <tr key={String(node.id ?? index)}>
                    <td className="mono">{String(node.id ?? '—')}</td>
                    <td>{String(node.type ?? '—')}</td>
                    <td className="mono">{String(node.skill ?? '—')}</td>
                    <td className="mono">{String(node.role ?? '—')}</td>
                    <td>{node.slaHours != null ? String(node.slaHours) : '—'}</td>
                    <td className="mono">{node.next != null ? String(node.next) : 'null'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        ) : (
          <p className="field-hint">JSON 解析失败或 nodes 为空，请检查 definition。</p>
        )}
      </Drawer>

      <Modal
        open={Boolean(validateResult)}
        title={validateTarget ? `校验结果 · ${validateTarget.code}` : '校验结果'}
        onClose={() => setValidateResult(null)}
        footer={
          <button type="button" className="btn btn-primary" onClick={() => setValidateResult(null)}>
            关闭
          </button>
        }
      >
        {validateResult ? (
          <>
            <div className="result-badges">
              <Badge tone={validateResult.valid || validateResult.ok ? 'green' : 'red'} dot>
                {validateResult.valid || validateResult.ok ? '校验通过' : '校验失败'}
              </Badge>
              {validateResult.message ? <span className="muted">{validateResult.message}</span> : null}
            </div>
            {validateResult.errors?.length ? (
              <>
                <h4 className="block-title">错误</h4>
                <ul className="error-list">
                  {validateResult.errors.map((error, index) => (
                    <li key={index}>
                      <Icon name="alert" size={14} />
                      <span>{error}</span>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
            {validateResult.warnings?.length ? (
              <>
                <h4 className="block-title">警告</h4>
                <ul className="finding-list">
                  {validateResult.warnings.map((warning, index) => (
                    <li key={index}>
                      <Badge tone="amber">WARN</Badge>
                      <span>{warning}</span>
                    </li>
                  ))}
                </ul>
              </>
            ) : null}
          </>
        ) : null}
      </Modal>

      <ConfirmModal
        open={Boolean(deleteTarget)}
        title="删除流程定义"
        description={
          deleteTarget
            ? `将删除流程「${deleteTarget.name}」（${deleteTarget.code}）；已发布版本立即失效，后续审批回退默认流程。`
            : undefined
        }
        confirmText="确认删除"
        danger
        loading={deleting}
        onCancel={() => {
          if (!deleting) setDeleteTarget(null)
        }}
        onConfirm={remove}
      />
    </>
  )
}
