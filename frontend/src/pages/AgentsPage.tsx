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
import { Link, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { AgentCatalogItem, AgentOverview, AgentRunView, PageResult } from '../api/types'
import { pageItems } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { Drawer } from '../components/Drawer'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Pager } from '../components/Pager'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'
import { isPlainObject, parseJsonText } from '../utils/format'
import {
  AGENT_TYPE_OPTIONS,
  agentTypeLabel,
  runStatusLabel,
  runStatusTone,
} from '../utils/labels'

const PAGE_SIZE = 10
const SAMPLE_INPUT = `{
  "tenantCode": "t-demo-01",
  "name": "示例租户",
  "plan": "PRO",
  "region": "cn-shanghai",
  "quotaSeats": 120,
  "contactEmail": "ops@demo.example"
}`

export function AgentsPage() {
  const toast = useToast()
  const navigate = useNavigate()

  const overview = useAsync(() => api.get<AgentOverview>('/api/agents/overview'), [])
  const catalog = useAsync(() => api.get<AgentCatalogItem[]>('/api/agents/catalog'), [])
  const catalogItems = catalog.data ?? []
  const catalogMap = useMemo(() => {
    const map = new Map<string, AgentCatalogItem>()
    catalogItems.forEach((item) => map.set(item.agentType, item))
    return map
  }, [catalogItems])
  const [agentType, setAgentType] = useState('')
  const [page, setPage] = useState(0)

  const query = useMemo(() => {
    const params = new URLSearchParams()
    if (agentType) params.set('agentType', agentType)
    params.set('page', String(page))
    params.set('size', String(PAGE_SIZE))
    return params.toString()
  }, [agentType, page])

  const runs = useAsync(
    () => api.get<PageResult<AgentRunView>>(`/api/agents/runs?${query}`),
    [query],
  )
  const runItems = pageItems(runs.data)

  const [drawerOpen, setDrawerOpen] = useState(false)
  const [formType, setFormType] = useState('risk-review')
  const [formTenant, setFormTenant] = useState('')
  const [formSteps, setFormSteps] = useState('6')
  const [formInput, setFormInput] = useState(SAMPLE_INPUT)
  const [formError, setFormError] = useState<string | null>(null)
  const [submitting, setSubmitting] = useState(false)

  const exampleFor = (type: string): string => {
    const example = catalogMap.get(type)?.exampleInput
    if (example === undefined || example === null) return SAMPLE_INPUT
    try {
      return JSON.stringify(example, null, 2)
    } catch {
      return SAMPLE_INPUT
    }
  }

  const openDrawer = () => {
    setFormType('risk-review')
    setFormTenant('')
    setFormSteps('6')
    setFormInput(exampleFor('risk-review'))
    setFormError(null)
    setDrawerOpen(true)
  }

  const changeFormType = (type: string) => {
    setFormType(type)
    setFormInput(exampleFor(type))
  }

  const createRun = async () => {
    setFormError(null)
    const parsed = parseJsonText(formInput)
    if (!parsed.ok) {
      setFormError(parsed.error)
      return
    }
    const input = parsed.value ?? {}
    if (!isPlainObject(input)) {
      setFormError('input 必须是 JSON 对象（{...}）')
      return
    }
    const steps = Number(formSteps)
    if (!Number.isInteger(steps) || steps < 1 || steps > 20) {
      setFormError('maxSteps 必须是 1-20 之间的整数')
      return
    }
    setSubmitting(true)
    try {
      const run = await api.post<AgentRunView>('/api/agents/runs', {
        agentType: formType,
        tenantId: formTenant.trim() || undefined,
        input,
        maxSteps: steps,
      })
      toast.success(`Agent 运行已创建：${run.runId}`)
      setDrawerOpen(false)
      runs.reload()
      overview.reload()
      if (run?.runId) navigate(`/agents/${run.runId}`)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  const models =
    typeof overview.data?.models === 'string'
      ? overview.data.models
      : Array.isArray(overview.data?.models)
        ? overview.data.models.join(' / ')
        : '—'

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>Agent 运行</h2>
            <p>AgentScope ReActAgent 运行轨迹：thought → action → observation，支持离线 ScriptedModel</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={() => { runs.reload(); overview.reload() }}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          <button type="button" className="btn btn-primary btn-sm" onClick={openDrawer}>
            <Icon name="play" size={15} />
            发起运行
          </button>
        </div>
      </header>

      <div className="kpi-grid kpi-grid-compact">
        <div className="card mini-stat">
          <span>运行总数</span>
          <strong>{overview.data?.runs ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>今日运行</span>
          <strong>{overview.data?.todayRuns ?? '—'}</strong>
        </div>
        <div className="card mini-stat">
          <span>已加载技能</span>
          <strong>{overview.data?.skills ?? '—'}</strong>
        </div>
        <div className="card mini-stat mini-stat-wide">
          <span>模型</span>
          <strong className="mono">{models}</strong>
        </div>
      </div>

      <SectionCard
        title="Agent 能力目录"
        description="来自 /api/agents/catalog：每类 Agent 的职责、输入与输出"
      >
        <AsyncBlock
          loading={catalog.loading}
          error={catalog.error}
          onRetry={catalog.reload}
          empty={!catalogItems.length}
          emptyText="暂无 Agent 目录"
        >
          <div className="agent-catalog-grid">
            {catalogItems.map((item) => (
              <article key={item.agentType} className="agent-catalog-card">
                <header>
                  <span className="agent-catalog-icon">
                    <Icon name="agents" size={16} />
                  </span>
                  <div>
                    <strong>{item.displayName || agentTypeLabel(item.agentType)}</strong>
                    <span className="mono">{item.agentType}</span>
                  </div>
                </header>
                <p>{item.description || '暂无说明'}</p>
                <div className="agent-catalog-meta">
                  {item.inputs?.length ? <span>输入：{item.inputs.join('、')}</span> : null}
                  {item.outputs?.length ? <span>输出：{item.outputs.join('、')}</span> : null}
                </div>
              </article>
            ))}
          </div>
        </AsyncBlock>
      </SectionCard>

      <SectionCard
        title="运行记录"
        description="Agent 名称 + 目的 + 状态；点击查看 ReAct 步骤与结构化输出"
        actions={
          <div className="filter-item">
            <select
              className="select"
              value={agentType}
              onChange={(event) => {
                setAgentType(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部 Agent</option>
              {AGENT_TYPE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {agentTypeLabel(option)}
                </option>
              ))}
            </select>
          </div>
        }
      >
        <AsyncBlock
          loading={runs.loading}
          error={runs.error}
          onRetry={runs.reload}
          empty={!runItems.length}
          emptyText="暂无 Agent 运行记录"
          emptyHint="点击右上角「发起运行」创建一次 Agent 运行"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>Agent</th>
                  <th>状态</th>
                  <th>模型</th>
                  <th>Token</th>
                  <th>耗时</th>
                  <th>创建时间</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {runItems.map((run) => {
                  const catalogItem = catalogMap.get(run.agentType)
                  const displayName = run.displayName || catalogItem?.displayName || agentTypeLabel(run.agentType)
                  const purpose = run.purpose || catalogItem?.description || '—'
                  return (
                    <tr key={run.runId}>
                      <td>
                        <div className="cell-title">
                          <Link to={`/agents/${run.runId}`}>{displayName}</Link>
                        </div>
                        <div className="cell-sub">
                          <span className="mono">{run.runId}</span>
                          <span>{purpose}</span>
                        </div>
                      </td>
                      <td>
                        <Badge tone={runStatusTone(run.status)} dot>
                          {runStatusLabel(run.status)}
                        </Badge>
                        {run.summary ? <div className="cell-sub">{run.summary}</div> : null}
                      </td>
                      <td className="mono muted">{run.model || '—'}</td>
                      <td>{run.tokensUsed ?? '—'}</td>
                      <td className="muted">{run.durationMs != null ? `${run.durationMs} ms` : '—'}</td>
                      <td className="muted nowrap">{formatDateTime(run.createdAt)}</td>
                      <td className="align-right">
                        <Link to={`/agents/${run.runId}`} className="btn btn-ghost btn-sm">
                          详情
                        </Link>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pager
            page={page}
            totalPages={runs.data?.totalPages}
            total={runs.data?.totalElements ?? runItems.length}
            onPage={setPage}
            disabled={runs.loading}
          />
        </AsyncBlock>
      </SectionCard>

      <Drawer
        open={drawerOpen}
        title="发起 Agent 运行"
        subtitle="运行创建后异步执行，可在详情页查看 ReAct 步骤"
        width={560}
        onClose={() => {
          if (!submitting) setDrawerOpen(false)
        }}
        footer={
          <>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => setDrawerOpen(false)}
              disabled={submitting}
            >
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={createRun} disabled={submitting}>
              {submitting ? <span className="spinner spinner-light" /> : <Icon name="play" size={15} />}
              {submitting ? '创建中…' : '开始运行'}
            </button>
          </>
        }
      >
        <div className="form-grid">
          <div className="field">
            <label className="field-label">
              Agent 类型<em className="required">*</em>
            </label>
            <select className="select" value={formType} onChange={(event) => changeFormType(event.target.value)}>
              {AGENT_TYPE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {agentTypeLabel(option)}（{option}）
                </option>
              ))}
            </select>
            {catalogMap.get(formType) ? (
              <p className="field-hint">
                {catalogMap.get(formType)?.displayName}：{catalogMap.get(formType)?.description}
              </p>
            ) : null}
          </div>
          <div className="field">
            <label className="field-label">tenantId（可选）</label>
            <input
              className="input"
              value={formTenant}
              placeholder="如 t-acme"
              onChange={(event) => setFormTenant(event.target.value)}
            />
          </div>
          <div className="field">
            <label className="field-label">maxSteps</label>
            <input
              className="input"
              value={formSteps}
              inputMode="numeric"
              onChange={(event) => setFormSteps(event.target.value)}
            />
          </div>
        </div>
        <div className="field">
          <label className="field-label">
            input（JSON 对象）<em className="required">*</em>
          </label>
          <textarea
            className={`input json-editor ${formError ? 'has-error' : ''}`}
            rows={10}
            value={formInput}
            spellCheck={false}
            onChange={(event) => {
              setFormInput(event.target.value)
              setFormError(null)
            }}
          />
          {formError ? <p className="field-error">{formError}</p> : null}
        </div>
        <button
          type="button"
          className="btn btn-ghost btn-sm"
          onClick={() => setFormInput(SAMPLE_INPUT)}
        >
          重置示例输入
        </button>
      </Drawer>
    </div>
  )
}
