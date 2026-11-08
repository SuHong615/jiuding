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
import { useEffect } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { AgentCatalogItem, AgentRunView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { Badge } from '../components/Badge'
import { ErrorBlock, LoadingBlock } from '../components/PageState'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { JsonView } from '../components/JsonView'
import { formatDateTime, formatDuration, truncate } from '../utils/format'
import { agentTypeLabel, runStatusLabel, runStatusTone } from '../utils/labels'

function InfoRow({ label, value }: { label: string; value: string | number | null | undefined }) {
  return (
    <div className="info-row">
      <span className="info-label">{label}</span>
      <span className="info-value">{value ?? '—'}</span>
    </div>
  )
}

function inputSummary(input: unknown): string {
  if (input === undefined || input === null) return '—'
  if (typeof input === 'string') return truncate(input, 200)
  try {
    return truncate(JSON.stringify(input), 240)
  } catch {
    return String(input)
  }
}

export function AgentRunDetailPage() {
  const { runId = '' } = useParams<{ runId: string }>()
  const { data, loading, error, reload } = useAsync(
    () => api.get<AgentRunView>(`/api/agents/runs/${runId}`),
    [runId],
  )
  const catalog = useAsync(() => api.get<AgentCatalogItem[]>('/api/agents/catalog'), [])

  // RUNNING 状态自动轮询，便于观察执行进度
  useEffect(() => {
    if (data?.status !== 'RUNNING') return
    const timer = window.setInterval(() => reload(), 4000)
    return () => window.clearInterval(timer)
  }, [data?.status, reload])

  if (loading && !data) {
    return (
      <div className="page">
        <LoadingBlock text="正在加载 Agent 运行…" />
      </div>
    )
  }

  if (error && !data) {
    return (
      <div className="page">
        <Link to="/agents" className="back-link">
          <Icon name="arrowLeft" size={15} />
          返回 Agent 运行列表
        </Link>
        <ErrorBlock message={error} onRetry={reload} />
      </div>
    )
  }

  if (!data) {
    return (
      <div className="page">
        <ErrorBlock message="运行记录不存在" onRetry={reload} />
      </div>
    )
  }

  const catalogItem = (catalog.data ?? []).find((item) => item.agentType === data.agentType)
  const displayName = data.displayName || catalogItem?.displayName || agentTypeLabel(data.agentType)
  const purpose = data.purpose || catalogItem?.description || '—'
  const steps = data.steps ?? []
  const conclusion = data.summary || '运行完成，可查看下方结构化输出。'

  return (
    <div className="page">
      <Link to="/agents" className="back-link">
        <Icon name="arrowLeft" size={15} />
        返回 Agent 运行列表
      </Link>

      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>{displayName}</h2>
            <p>
              <span className="mono">{data.runId}</span>
              <span className="muted"> · {formatDateTime(data.createdAt)}</span>
            </p>
          </div>
        </div>
        <div className="page-head-actions">
          <Badge tone={runStatusTone(data.status)} dot>
            {runStatusLabel(data.status)}
          </Badge>
          <button type="button" className="btn btn-ghost btn-sm" onClick={reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      {data.status === 'RUNNING' ? (
        <div className="alert alert-blue" role="status">
          <Icon name="clock" size={16} />
          <div>
            <strong>运行中</strong>
            <p>页面每 4 秒自动刷新，完成前显示的是已产生的步骤。</p>
          </div>
        </div>
      ) : null}

      <SectionCard title="本次运行在做什么" description="Agent 名称、目的、输入摘要与结论">
        <div className="run-purpose-grid">
          <div className="run-purpose-block">
            <span className="run-purpose-label">Agent</span>
            <strong>{displayName}</strong>
            <p>{purpose}</p>
            {catalogItem?.inputs?.length ? <p className="field-hint">输入：{catalogItem.inputs.join('、')}</p> : null}
            {catalogItem?.outputs?.length ? <p className="field-hint">输出：{catalogItem.outputs.join('、')}</p> : null}
          </div>
          <div className="run-purpose-block">
            <span className="run-purpose-label">输入摘要</span>
            <p className="mono run-purpose-input">{inputSummary(data.input)}</p>
            <details className="raw-details">
              <summary>查看完整输入 JSON</summary>
              <JsonView value={data.input} empty="本次运行未记录输入" maxHeight={180} />
            </details>
          </div>
          <div className="run-purpose-block">
            <span className="run-purpose-label">结论</span>
            <p>{conclusion}</p>
            <Badge tone={runStatusTone(data.status)}>{runStatusLabel(data.status)}</Badge>
          </div>
        </div>
      </SectionCard>

      <div className="detail-grid">
        <div className="detail-main">
          <SectionCard title="ReAct 步骤" description="中文步骤标题；thought → action → observation，原始 JSON 可展开">
            {steps.length ? (
              <ol className="step-list">
                {steps.map((step) => {
                  const stepTitle =
                    step.title ||
                    step.actionLabel ||
                    (step.action ? `调用工具 ${step.action}` : `步骤 ${step.index + 1}`)
                  return (
                    <li key={step.index} className="step-item">
                      <span className="step-index">{step.index + 1}</span>
                      <div className="step-body">
                        <div className="step-title-row">
                          <strong className="step-title-text">{stepTitle}</strong>
                          {step.action ? <span className="chip chip-blue">{step.action}</span> : null}
                        </div>
                        {step.thought ? (
                          <div className="step-block">
                            <span className="step-label">思考</span>
                            <p>{step.thought}</p>
                          </div>
                        ) : null}
                        {step.actionInput ? (
                          <div className="step-block">
                            <span className="step-label">动作参数</span>
                            <code className="inline-json">{step.actionInput}</code>
                          </div>
                        ) : null}
                        {step.observation ? (
                          <div className="step-block">
                            <span className="step-label">观察结果</span>
                            <pre className="json-view step-observation">{step.observation}</pre>
                          </div>
                        ) : null}
                        <details className="raw-details">
                          <summary>原始步骤 JSON</summary>
                          <JsonView value={step} maxHeight={220} />
                        </details>
                      </div>
                    </li>
                  )
                })}
              </ol>
            ) : (
              <p className="muted">
                {data.status === 'RUNNING' ? '步骤生成中…' : '本次运行没有记录步骤'}
              </p>
            )}
          </SectionCard>

          <SectionCard title="结构化输出" description="Agent 最终 output（JSON，可折叠查看）">
            <details className="raw-details" open>
              <summary>展开 / 收起原始输出 JSON</summary>
              <JsonView value={data.output} empty="暂无输出" />
            </details>
          </SectionCard>
        </div>

        <div className="detail-side">
          <SectionCard title="运行信息">
            <div className="info-grid">
              <InfoRow label="运行 ID" value={data.runId} />
              <InfoRow label="Agent" value={`${displayName}（${data.agentType}）`} />
              <InfoRow label="状态" value={runStatusLabel(data.status)} />
              <InfoRow label="模型" value={data.model} />
              <InfoRow label="Token 用量" value={data.tokensUsed} />
              <InfoRow label="耗时" value={formatDuration(data.durationMs)} />
              <InfoRow label="租户" value={data.tenantId} />
              <InfoRow label="创建时间" value={formatDateTime(data.createdAt)} />
            </div>
          </SectionCard>
          <SectionCard title="Agent 说明">
            <p className="field-hint">{catalogItem?.description || purpose}</p>
            {catalogItem?.exampleInput !== undefined ? (
              <details className="raw-details">
                <summary>示例输入</summary>
                <JsonView value={catalogItem.exampleInput} maxHeight={220} />
              </details>
            ) : null}
          </SectionCard>
        </div>
      </div>
    </div>
  )
}
