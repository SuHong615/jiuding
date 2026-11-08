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
import { useEffect, useState } from 'react'
import { Link, useParams, useSearchParams } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type {
  SkillDebugResult,
  SkillDetail,
  SkillMatchedRule,
  SkillReviewView,
  SkillVersionView,
  SkillUpdatePayload,
} from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock, EmptyBlock, ErrorBlock, LoadingBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { ConfirmModal } from '../components/ConfirmModal'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { JsonView } from '../components/JsonView'
import { Modal } from '../components/Modal'
import { Timeline } from '../components/Timeline'
import { useToast } from '../components/Toast'
import { formatDateTime, formatDuration, parseJsonText, safeStringify } from '../utils/format'
import {
  decisionLabel,
  severityTone,
  skillReviewActionLabel,
  skillStatusLabel,
  skillStatusTone,
  skillTypeLabel,
  skillTypeTone,
} from '../utils/labels'

const TABS = [
  { key: 'content', label: '内容' },
  { key: 'code', label: '代码' },
  { key: 'review', label: '审查' },
  { key: 'debug', label: '调试' },
  { key: 'versions', label: '版本' },
  { key: 'enable', label: '启用' },
] as const

type TabKey = (typeof TABS)[number]['key']

const SAMPLE_INPUT = `{
  "tenantCode": "t-demo-01",
  "name": "示例租户",
  "plan": "PRO",
  "region": "cn-shanghai",
  "quotaSeats": 120,
  "quotaStorageGb": 200,
  "quotaApiRpm": 1200,
  "contactEmail": "ops@demo.example",
  "attributes": { "industry": "saas" }
}`

function bumpVersion(version: string | undefined): string {
  const parts = (version || '1.0.0').split('.')
  const patch = Number(parts[2] || 0)
  if (Number.isNaN(patch)) return '1.0.1'
  return `${parts[0] || '1'}.${parts[1] || '0'}.${patch + 1}`
}

function manifestRows(detail: SkillDetail): Array<{ label: string; value: string }> {
  const manifest = (detail.manifest ?? {}) as Record<string, unknown>
  const pick = (key: string, fallback?: unknown): string => {
    const value = manifest[key] ?? fallback
    if (value === undefined || value === null) return '—'
    if (Array.isArray(value)) return value.join('、')
    if (typeof value === 'object') return safeStringify(value)
    return String(value)
  }
  return [
    { label: 'id', value: pick('id', detail.id) },
    { label: 'name', value: pick('name', detail.name) },
    { label: 'version', value: pick('version', detail.version) },
    { label: 'type', value: skillTypeLabel(pick('type', detail.type)) },
    { label: 'description', value: pick('description', detail.description) },
    { label: 'triggers', value: pick('triggers', detail.triggers) },
    { label: 'tools', value: pick('tools', detail.tools) },
  ]
}

function ruleLabel(rule: SkillMatchedRule | string): { id: string; message: string; severity?: string } {
  if (typeof rule === 'string') return { id: rule, message: '' }
  return {
    id: rule.ruleId || rule.id || rule.rule || '—',
    message: rule.message || rule.description || '',
    severity: rule.severity,
  }
}

export function SkillDetailPage() {
  const { id = '' } = useParams<{ id: string }>()
  const toast = useToast()
  const { isPlatformAdmin, hasRole, hasPermission } = useAuth()
  const canManage = isPlatformAdmin || hasRole('TENANT_ADMIN') || hasPermission('SKILL_MANAGE')
  const canReview = isPlatformAdmin

  const [searchParams, setSearchParams] = useSearchParams()
  const rawTab = searchParams.get('tab')
  const tab: TabKey = TABS.some((item) => item.key === rawTab) ? (rawTab as TabKey) : 'content'

  const detail = useAsync(() => api.get<SkillDetail>(`/api/skills/${id}`), [id])
  const versions = useAsync(() => api.get<SkillVersionView[]>(`/api/skills/${id}/versions`), [id])
  const reviews = useAsync(() => api.get<SkillReviewView[]>(`/api/skills/${id}/reviews`), [id])

  const setTab = (next: TabKey) => {
    const params = new URLSearchParams(searchParams)
    if (next === 'content') params.delete('tab')
    else params.set('tab', next)
    setSearchParams(params, { replace: true })
  }

  const [rulesYaml, setRulesYaml] = useState('')
  const [codeError, setCodeError] = useState<string | null>(null)
  const [savingCode, setSavingCode] = useState(false)
  const [newVersionOpen, setNewVersionOpen] = useState(false)
  const [newVersion, setNewVersion] = useState('')
  const [versionSource, setVersionSource] = useState<SkillVersionView | null>(null)

  const [reviewDecision, setReviewDecision] = useState<'APPROVE' | 'REJECT'>('APPROVE')
  const [reviewComment, setReviewComment] = useState('')
  const [reviewError, setReviewError] = useState<string | null>(null)
  const [reviewBusy, setReviewBusy] = useState(false)
  const [deleteOpen, setDeleteOpen] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const [debugInput, setDebugInput] = useState(SAMPLE_INPUT)
  const [debugVersion, setDebugVersion] = useState('')
  const [debugResult, setDebugResult] = useState<SkillDebugResult | null>(null)
  const [debugError, setDebugError] = useState<string | null>(null)
  const [debugging, setDebugging] = useState(false)

  const [selectedVersion, setSelectedVersion] = useState('')
  const [versionDetail, setVersionDetail] = useState<SkillVersionView | null>(null)
  const [versionLoading, setVersionLoading] = useState(false)
  const [versionError, setVersionError] = useState<string | null>(null)

  useEffect(() => {
    if (!detail.data) return
    setRulesYaml(detail.data.rulesYaml ?? '')
    if (!debugVersion) setDebugVersion(detail.data.version || '')
  }, [detail.data, debugVersion])

  const versionItems = versions.data ?? []
  const currentVersion =
    versionItems.find((item) => item.enabled)?.version ||
    detail.data?.version ||
    versionItems[0]?.version ||
    ''

  const reviewEntries = (reviews.data ?? []).map((review) => ({
    title: skillReviewActionLabel(review.action),
    time: review.at,
    actor: review.actor,
    detail: review.comment || undefined,
    tone: review.action === 'REJECT' ? ('red' as const) : review.action === 'APPROVE' ? ('green' as const) : ('blue' as const),
  }))

  const reloadAll = () => {
    detail.reload()
    versions.reload()
    reviews.reload()
  }

  const openNewVersion = (source?: SkillVersionView | null) => {
    setVersionSource(source ?? null)
    setNewVersion(source?.version ? bumpVersion(source.version) : bumpVersion(detail.data?.version))
    setCodeError(null)
    setNewVersionOpen(true)
  }

  const saveCode = async (versionOverride?: string) => {
    if (!detail.data) return
    const targetVersion = versionOverride ?? detail.data.version
    setCodeError(null)
    if (versionOverride && !/^\d+\.\d+\.\d+$/.test(versionOverride)) {
      setCodeError('版本号需为 x.y.z 格式')
      return
    }
    setSavingCode(true)
    try {
      const payload: SkillUpdatePayload = {
        version: targetVersion,
        rulesYaml,
        description: versionSource?.description || detail.data.description || undefined,
        instructions: versionSource?.instructions || undefined,
      }
      await api.put<SkillDetail>(`/api/skills/${id}`, payload)
      toast.success(versionOverride ? `已创建新版本 v${targetVersion}` : '代码已保存（草稿）')
      setNewVersionOpen(false)
      setVersionSource(null)
      reloadAll()
    } catch (err) {
      const message = errorMessage(err)
      if (versionOverride) setCodeError(message)
      else toast.error(message)
    } finally {
      setSavingCode(false)
    }
  }

  const submitReview = async () => {
    setReviewBusy(true)
    try {
      await api.post<SkillDetail>(`/api/skills/${id}/submit-review`)
      toast.success('已提交审核')
      reloadAll()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setReviewBusy(false)
    }
  }

  const decideReview = async () => {
    setReviewError(null)
    if (reviewDecision === 'REJECT' && reviewComment.trim().length < 2) {
      setReviewError('驳回必须填写审核意见（至少 2 个字符）')
      return
    }
    setReviewBusy(true)
    try {
      await api.post<SkillDetail>(`/api/skills/${id}/review`, {
        decision: reviewDecision,
        comment: reviewComment.trim() || undefined,
      })
      toast.success(reviewDecision === 'APPROVE' ? '审核通过' : '已驳回技能')
      setReviewComment('')
      reloadAll()
    } catch (err) {
      setReviewError(errorMessage(err))
    } finally {
      setReviewBusy(false)
    }
  }

  const toggleEnabled = async () => {
    if (!detail.data) return
    setReviewBusy(true)
    try {
      await api.post<SkillDetail>(`/api/skills/${id}/${detail.data.enabled ? 'disable' : 'enable'}`)
      toast.success(detail.data.enabled ? '技能已停用' : '技能已启用并触发热加载')
      reloadAll()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setReviewBusy(false)
    }
  }

  const deleteSkill = async () => {
    setDeleting(true)
    try {
      await api.delete<unknown>(`/api/skills/${id}`)
      toast.success('技能已删除（DISABLED）')
      setDeleteOpen(false)
      window.location.assign('/skills')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setDeleting(false)
    }
  }

  const runDebug = async () => {
    setDebugError(null)
    const parsed = parseJsonText(debugInput)
    if (!parsed.ok) {
      setDebugError(parsed.error)
      return
    }
    setDebugging(true)
    try {
      const result = await api.post<SkillDebugResult>(`/api/skills/${id}/debug`, {
        input: parsed.value ?? {},
        version: debugVersion || undefined,
      })
      setDebugResult(result)
    } catch (err) {
      setDebugError(errorMessage(err))
      toast.error('调试运行失败')
    } finally {
      setDebugging(false)
    }
  }

  const viewVersion = async (version: string) => {
    setSelectedVersion(version)
    setVersionLoading(true)
    setVersionError(null)
    setVersionDetail(null)
    try {
      const result = await api.get<SkillVersionView>(
        `/api/skills/${id}/versions/${encodeURIComponent(version)}`,
      )
      setVersionDetail(result)
    } catch (err) {
      setVersionError(errorMessage(err))
    } finally {
      setVersionLoading(false)
    }
  }

  if (detail.loading && !detail.data) {
    return (
      <div className="page">
        <LoadingBlock text="正在加载技能详情…" />
      </div>
    )
  }

  if (detail.error && !detail.data) {
    return (
      <div className="page">
        <Link to="/skills" className="back-link">
          <Icon name="arrowLeft" size={15} />
          返回技能中心
        </Link>
        <ErrorBlock message={detail.error} onRetry={detail.reload} />
      </div>
    )
  }

  const skill = detail.data
  if (!skill) {
    return (
      <div className="page">
        <ErrorBlock message="技能不存在或未加载" onRetry={detail.reload} />
      </div>
    )
  }

  const status = skill.status || ''
  const editable = canManage && (status === 'DRAFT' || status === 'REJECTED')
  const manifest = manifestRows(skill)
  const matchedRules = debugResult?.matchedRules ?? []
  const debugActions = debugResult?.actions ?? []

  return (
    <div className="page">
      <Link to="/skills" className="back-link">
        <Icon name="arrowLeft" size={15} />
        返回技能中心
      </Link>

      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>{skill.name || skill.id}</h2>
            <p>
              <span className="mono">{skill.id}</span>
              <span className="muted"> · v{skill.version || '—'}</span>
            </p>
          </div>
        </div>
        <div className="page-head-actions">
          <Badge tone={skillTypeTone(skill.type)}>{skillTypeLabel(skill.type)}</Badge>
          <Badge tone={skillStatusTone(status)} dot>
            {skillStatusLabel(status)}
          </Badge>
          <Badge tone={skill.enabled ? 'green' : 'gray'} dot>
            {skill.enabled ? '已启用' : '未启用'}
          </Badge>
          <button type="button" className="btn btn-ghost btn-sm" onClick={reloadAll}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <div className="tabs" role="tablist" aria-label="技能详情分区">
        {TABS.map((item) => (
          <button
            key={item.key}
            type="button"
            role="tab"
            aria-selected={tab === item.key}
            className={`tab ${tab === item.key ? 'active' : ''}`}
            onClick={() => setTab(item.key)}
          >
            {item.label}
            {item.key === 'versions' && versions.data ? <span className="tab-count">{versions.data.length}</span> : null}
          </button>
        ))}
      </div>

      <div role="tabpanel">
        {tab === 'content' ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="SKILL.md 内容" description="Agent 注入正文（只读）">
                {skill.instructions ? (
                  <pre className="skill-content-pre">{skill.instructions}</pre>
                ) : (
                  <EmptyBlock text="该版本暂无 SKILL.md 正文" />
                )}
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="Manifest" description="frontmatter 字段">
                <div className="info-grid">
                  {manifest.map((row) => (
                    <div key={row.label} className="info-row">
                      <span className="info-label">{row.label}</span>
                      <span className="info-value">{row.value}</span>
                    </div>
                  ))}
                </div>
                {skill.manifest ? (
                  <details className="raw-details">
                    <summary>原始 manifest JSON</summary>
                    <JsonView value={skill.manifest} maxHeight={240} />
                  </details>
                ) : null}
              </SectionCard>
            </div>
          </div>
        ) : null}

        {tab === 'code' ? (
          <SectionCard
            title="rules.yaml 源码"
            description="仅 DRAFT / REJECTED 状态可编辑；保存后写入草稿版本"
            actions={
              <div className="card-head-actions">
                <button
                  type="button"
                  className="btn btn-ghost btn-sm"
                  onClick={() => openNewVersion()}
                  disabled={!editable || savingCode}
                >
                  另存为新版本
                </button>
                <button
                  type="button"
                  className="btn btn-primary btn-sm"
                  onClick={() => saveCode()}
                  disabled={!editable || savingCode}
                >
                  {savingCode ? <span className="spinner spinner-light" /> : null}
                  {savingCode ? '保存中…' : '保存代码'}
                </button>
              </div>
            }
          >
            {!editable ? (
              <div className="alert alert-blue">
                <Icon name="info" size={15} />
                <span>
                  当前状态为 {skillStatusLabel(status)}，代码只读。仅草稿 / 已驳回状态可编辑；可在「审查」页提交审核。
                </span>
              </div>
            ) : null}
            <div className="code-editor">
              <textarea
                className="input json-editor"
                rows={20}
                value={rulesYaml}
                disabled={!editable}
                spellCheck={false}
                placeholder="skillId: ...&#10;rules: []"
                onChange={(event) => setRulesYaml(event.target.value)}
              />
            </div>
            {codeError ? <p className="field-error modal-error">{codeError}</p> : null}
          </SectionCard>
        ) : null}

        {tab === 'review' ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="审查状态" description="技能审核流程：草稿 → 待审核 → 通过/驳回">
                <div className="status-strip">
                  <div className="status-strip-main">
                    <Badge tone={skillStatusTone(status)} dot>
                      {skillStatusLabel(status)}
                    </Badge>
                    <span className="muted">审核人：{skill.reviewedBy || '—'}</span>
                    <span className="muted">审核时间：{formatDateTime(skill.reviewedAt)}</span>
                  </div>
                  <div className="card-head-actions">
                    {canManage && (status === 'DRAFT' || status === 'REJECTED') ? (
                      <button type="button" className="btn btn-primary btn-sm" onClick={submitReview} disabled={reviewBusy}>
                        提交审核
                      </button>
                    ) : null}
                    {canManage && skill.source === 'UPLOADED' ? (
                      <button type="button" className="btn btn-danger-ghost btn-sm" onClick={() => setDeleteOpen(true)}>
                        删除技能
                      </button>
                    ) : null}
                  </div>
                </div>
                {skill.reviewComment ? (
                  <div className="alert alert-red">
                    <Icon name="alert" size={15} />
                    <span>审核意见：{skill.reviewComment}</span>
                  </div>
                ) : null}

                {canReview && status === 'PENDING_REVIEW' ? (
                  <div className="review-panel">
                    <div className="decision-group">
                      <label className={`decision-option ${reviewDecision === 'APPROVE' ? 'selected approve' : ''}`}>
                        <input
                          type="radio"
                          checked={reviewDecision === 'APPROVE'}
                          onChange={() => setReviewDecision('APPROVE')}
                        />
                        <span><strong>通过</strong><em>允许启用并热加载</em></span>
                      </label>
                      <label className={`decision-option ${reviewDecision === 'REJECT' ? 'selected reject' : ''}`}>
                        <input
                          type="radio"
                          checked={reviewDecision === 'REJECT'}
                          onChange={() => setReviewDecision('REJECT')}
                        />
                        <span><strong>驳回</strong><em>需填写审核意见</em></span>
                      </label>
                    </div>
                    <Field label="审核意见" required={reviewDecision === 'REJECT'}>
                      <textarea
                        className={`input ${reviewError ? 'has-error' : ''}`}
                        rows={3}
                        value={reviewComment}
                        placeholder={reviewDecision === 'REJECT' ? '请填写驳回原因（必填）' : '可选：审核备注'}
                        onChange={(event) => {
                          setReviewComment(event.target.value)
                          setReviewError(null)
                        }}
                      />
                    </Field>
                    {reviewError ? <p className="field-error">{reviewError}</p> : null}
                    <button type="button" className="btn btn-primary btn-block" onClick={decideReview} disabled={reviewBusy}>
                      {reviewBusy ? <span className="spinner spinner-light" /> : null}
                      提交审核结果
                    </button>
                  </div>
                ) : null}
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="审核历史" description="来自 /api/skills/{id}/reviews">
                <AsyncBlock
                  loading={reviews.loading}
                  error={reviews.error}
                  onRetry={reviews.reload}
                  empty={!reviewEntries.length}
                  emptyText="暂无审核记录"
                >
                  <Timeline items={reviewEntries} />
                </AsyncBlock>
              </SectionCard>
            </div>
          </div>
        ) : null}

        {tab === 'debug' ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="调试输入" description="输入 JSON 后运行规则，不落库；可选择历史版本">
                <div className="form-grid form-grid-2">
                  <Field label="版本">
                    <select className="select" value={debugVersion} onChange={(event) => setDebugVersion(event.target.value)}>
                      <option value="">当前版本（v{skill.version || '—'}）</option>
                      {versionItems.map((item) => (
                        <option key={item.version} value={item.version}>
                          v{item.version} · {skillStatusLabel(item.status)}
                        </option>
                      ))}
                    </select>
                  </Field>
                </div>
                <Field label="input（JSON）">
                  <textarea
                    className={`input json-editor ${debugError ? 'has-error' : ''}`}
                    rows={14}
                    value={debugInput}
                    spellCheck={false}
                    onChange={(event) => {
                      setDebugInput(event.target.value)
                      setDebugError(null)
                    }}
                  />
                </Field>
                {debugError ? <p className="field-error">{debugError}</p> : null}
                <button type="button" className="btn btn-primary" onClick={runDebug} disabled={debugging}>
                  {debugging ? <span className="spinner spinner-light" /> : <Icon name="play" size={15} />}
                  {debugging ? '运行中…' : '运行调试'}
                </button>
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="调试结果" description="决策 / 动作 / 命中规则 / 耗时">
                {!debugResult ? (
                  <EmptyBlock text="尚未运行" hint="填写输入并点击「运行调试」查看结果" />
                ) : (
                  <>
                    <div className="result-badges">
                      <Badge tone="blue" dot>{decisionLabel(debugResult.decision || debugResult.verdict)}</Badge>
                      {debugResult.version ? <Badge tone="gray">v{debugResult.version}</Badge> : null}
                      <span className="muted">耗时 {formatDuration(debugResult.durationMs)}</span>
                    </div>
                    <h4 className="block-title">命中规则</h4>
                    {matchedRules.length ? (
                      <ul className="finding-list">
                        {matchedRules.map((rule, index) => {
                          const parsed = ruleLabel(rule)
                          return (
                            <li key={`${parsed.id}-${index}`}>
                              <Badge tone={severityTone(parsed.severity)}>{parsed.severity || 'INFO'}</Badge>
                              <span>
                                <strong className="mono">{parsed.id}</strong>
                                {parsed.message ? ` · ${parsed.message}` : ''}
                              </span>
                            </li>
                          )
                        })}
                      </ul>
                    ) : (
                      <p className="muted">无命中规则</p>
                    )}
                    <h4 className="block-title">动作</h4>
                    {debugActions.length ? (
                      <ul className="action-list">
                        {debugActions.map((action, index) => (
                          <li key={index}>
                            <span className="chip chip-blue">{action.action}</span>
                            {action.message ? <span>{action.message}</span> : null}
                            {action.value !== undefined ? (
                              <code className="inline-json">{safeStringify(action.value)}</code>
                            ) : null}
                          </li>
                        ))}
                      </ul>
                    ) : (
                      <p className="muted">无动作</p>
                    )}
                    {debugResult.notes?.length ? (
                      <>
                        <h4 className="block-title">Notes</h4>
                        <ul className="finding-list">
                          {debugResult.notes.map((note, index) => (
                            <li key={index}>{note}</li>
                          ))}
                        </ul>
                      </>
                    ) : null}
                    <details className="raw-details">
                      <summary>原始调试结果 JSON</summary>
                      <JsonView value={debugResult} maxHeight={240} />
                    </details>
                  </>
                )}
              </SectionCard>
            </div>
          </div>
        ) : null}

        {tab === 'versions' ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="版本列表" description="全部版本及审核 / 启用状态">
                <AsyncBlock
                  loading={versions.loading}
                  error={versions.error}
                  onRetry={versions.reload}
                  empty={!versionItems.length}
                  emptyText="暂无版本数据"
                  emptyHint="后端未提供版本接口或该技能尚未生成版本"
                >
                  <div className="table-wrap">
                    <table className="table">
                      <thead>
                        <tr>
                          <th>版本</th>
                          <th>状态</th>
                          <th>启用</th>
                          <th>审核人</th>
                          <th>更新时间</th>
                          <th className="align-right">操作</th>
                        </tr>
                      </thead>
                      <tbody>
                        {versionItems.map((version) => (
                          <tr key={version.version}>
                            <td className="mono">v{version.version}</td>
                            <td>
                              <Badge tone={skillStatusTone(version.status)} dot>
                                {skillStatusLabel(version.status)}
                              </Badge>
                            </td>
                            <td>
                              <Badge tone={version.enabled ? 'green' : 'gray'}>
                                {version.enabled ? '已启用' : '未启用'}
                              </Badge>
                            </td>
                            <td className="muted">{version.reviewedBy || '—'}</td>
                            <td className="muted nowrap">{formatDateTime(version.updatedAt || version.createdAt)}</td>
                            <td className="align-right nowrap">
                              <button type="button" className="link-btn" onClick={() => viewVersion(version.version)}>
                                查看
                              </button>
                              <button
                                type="button"
                                className="link-btn"
                                disabled={!canManage}
                                onClick={() => openNewVersion(version)}
                              >
                                基于此版本新建
                              </button>
                            </td>
                          </tr>
                        ))}
                      </tbody>
                    </table>
                  </div>
                </AsyncBlock>
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="版本内容" description={selectedVersion ? `v${selectedVersion}` : '点击左侧「查看」'}>
                {versionLoading ? (
                  <LoadingBlock text="正在加载版本内容…" />
                ) : versionError ? (
                  <ErrorBlock message={versionError} onRetry={() => viewVersion(selectedVersion)} />
                ) : versionDetail ? (
                  <>
                    <div className="info-grid">
                      <div className="info-row"><span className="info-label">版本</span><span className="info-value mono">v{versionDetail.version}</span></div>
                      <div className="info-row"><span className="info-label">状态</span><span className="info-value">{skillStatusLabel(versionDetail.status)}</span></div>
                      <div className="info-row"><span className="info-label">审核人</span><span className="info-value">{versionDetail.reviewedBy || '—'}</span></div>
                    </div>
                    <h4 className="block-title">SKILL.md</h4>
                    <pre className="skill-content-pre small-pre">{versionDetail.instructions || '—'}</pre>
                    <h4 className="block-title">rules.yaml</h4>
                    <pre className="skill-content-pre small-pre">{versionDetail.rulesYaml || '—'}</pre>
                    {versionDetail.manifest ? (
                      <details className="raw-details">
                        <summary>manifest JSON</summary>
                        <JsonView value={versionDetail.manifest} maxHeight={200} />
                      </details>
                    ) : null}
                  </>
                ) : (
                  <EmptyBlock text="尚未选择版本" hint="点击左侧版本行的「查看」加载内容" />
                )}
              </SectionCard>
            </div>
          </div>
        ) : null}

        {tab === 'enable' ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="启用开关" description="启用后会自动触发热加载，Agent 与 skill.run 立即生效">
                <div className="enable-panel">
                  <div className="enable-status">
                    <Badge tone={skill.enabled ? 'green' : 'gray'} dot>
                      {skill.enabled ? '已启用' : '未启用'}
                    </Badge>
                    <span className="muted">当前生效版本：v{currentVersion || '—'}</span>
                  </div>
                  <button
                    type="button"
                    className={`btn ${skill.enabled ? 'btn-danger-ghost' : 'btn-primary'}`}
                    onClick={toggleEnabled}
                    disabled={!canManage || reviewBusy}
                  >
                    {reviewBusy ? <span className="spinner spinner-light" /> : null}
                    {skill.enabled ? '停用技能' : '启用技能'}
                  </button>
                  {!canManage ? <p className="field-hint">当前角色无启停权限。</p> : null}
                </div>
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="热加载说明">
                <ul className="hot-reload-list">
                  <li>内置技能来自 `skills/` 目录，只读加载。</li>
                  <li>上传技能仅在审核通过且启用后进入注册表。</li>
                  <li>启用 / 停用后 agent-service 自动 reload，无需重启。</li>
                  <li>当前生效版本：<span className="mono">v{currentVersion || '—'}</span>。</li>
                  <li>调试运行不落库；历史版本可通过「版本」页查看。</li>
                </ul>
              </SectionCard>
            </div>
          </div>
        ) : null}
      </div>

      <Modal
        open={newVersionOpen}
        title="另存为新版本"
        description={
          versionSource
            ? `基于 v${versionSource.version} 创建新草稿版本`
            : '基于当前代码创建新草稿版本（仅 DRAFT / REJECTED 可保存）'
        }
        onClose={() => {
          if (!savingCode) setNewVersionOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setNewVersionOpen(false)} disabled={savingCode}>
              取消
            </button>
            <button
              type="button"
              className="btn btn-primary"
              onClick={() => saveCode(newVersion)}
              disabled={savingCode}
            >
              {savingCode ? <span className="spinner spinner-light" /> : null}
              创建新版本
            </button>
          </>
        }
      >
        <Field label="新版本号" required hint="格式 x.y.z，例如 1.0.1">
          <input className="input mono" value={newVersion} onChange={(event) => setNewVersion(event.target.value)} />
        </Field>
        {codeError ? <p className="field-error modal-error">{codeError}</p> : null}
      </Modal>

      <ConfirmModal
        open={deleteOpen}
        title="删除技能"
        description={`将删除技能「${skill.name || skill.id}」（仅上传技能，逻辑删除为 DISABLED）；删除后需重新上传才能恢复。`}
        confirmText="确认删除"
        danger
        loading={deleting}
        onCancel={() => {
          if (!deleting) setDeleteOpen(false)
        }}
        onConfirm={deleteSkill}
      />
    </div>
  )
}
