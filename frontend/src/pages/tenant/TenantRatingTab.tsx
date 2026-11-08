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
import { useState } from 'react'
import { ApiError, api, errorMessage } from '../../api/client'
import type {
  RatingAssessRequest,
  RatingCurrentView,
  RatingDims,
  RatingOverrideRequest,
  RatingView,
  PageResult,
  TenantView,
} from '../../api/types'
import { pageItems, pageTotal } from '../../api/types'
import { useAsync } from '../../hooks/useAsync'
import { AsyncBlock, EmptyBlock } from '../../components/PageState'
import { Badge } from '../../components/Badge'
import { Field, SectionCard } from '../../components/Forms'
import { Icon } from '../../components/Icon'
import { Modal } from '../../components/Modal'
import { Pager } from '../../components/Pager'
import { RatingBadge } from '../../components/RatingBadge'
import { RatingTrendChart } from '../../components/RatingTrendChart'
import { useToast } from '../../components/Toast'
import { formatDateTime } from '../../utils/format'
import { RATING_DIMENSIONS, RATING_GRADE_OPTIONS, ratingSourceLabel } from '../../utils/labels'

const PAGE_SIZE = 10

function deltaClass(delta: number | null): string {
  if (delta == null || delta === 0) return 'delta-flat'
  return delta > 0 ? 'delta-up' : 'delta-down'
}

function deltaText(delta: number | null): string {
  if (delta == null || delta === 0) return '—'
  return delta > 0 ? `↑ +${delta}` : `↓ ${delta}`
}

function numeric(value: unknown): number | null {
  return typeof value === 'number' && Number.isFinite(value) ? value : null
}

function extractDims(rating?: RatingCurrentView | null): RatingDims | null {
  if (!rating) return null
  const source = rating.dims
  const raw = source
    ? [source.business, source.compliance, source.financial, source.security, source.service]
    : [rating.dimBusiness, rating.dimCompliance, rating.dimFinancial, rating.dimSecurity, rating.dimService]
  const values = raw.map(numeric)
  if (values.some((value) => value === null)) return null
  return {
    business: values[0] as number,
    compliance: values[1] as number,
    financial: values[2] as number,
    security: values[3] as number,
    service: values[4] as number,
  }
}

export function TenantRatingTab({
  tenantId,
  tenant,
  canManage,
  isAdmin,
  onTenantUpdated,
}: {
  tenantId: string
  tenant: TenantView
  canManage: boolean
  isAdmin: boolean
  onTenantUpdated: () => void
}) {
  const toast = useToast()

  const [page, setPage] = useState(0)
  const current = useAsync(async () => {
    try {
      return await api.get<RatingCurrentView>(`/api/tenants/${tenantId}/ratings/current`)
    } catch (err) {
      if (err instanceof ApiError && (err.status === 404 || err.code === 404)) return null
      throw err
    }
  }, [tenantId])
  const history = useAsync(
    () => api.get<PageResult<RatingView>>(`/api/tenants/${tenantId}/ratings?page=${page}&size=${PAGE_SIZE}`),
    [tenantId, page],
  )
  // 趋势固定取最近 10 次，不受历史分页影响
  const trend = useAsync(
    () => api.get<PageResult<RatingView>>(`/api/tenants/${tenantId}/ratings?page=0&size=10`),
    [tenantId],
  )

  const [assessOpen, setAssessOpen] = useState(false)
  const [assessNote, setAssessNote] = useState('')
  const [assessing, setAssessing] = useState(false)

  const [overrideOpen, setOverrideOpen] = useState(false)
  const [overrideGrade, setOverrideGrade] = useState('A')
  const [overrideScore, setOverrideScore] = useState('80')
  const [overrideReason, setOverrideReason] = useState('')
  const [overrideError, setOverrideError] = useState<string | null>(null)
  const [overriding, setOverriding] = useState(false)

  const grade = current.data?.grade || tenant.ratingGrade || ''
  const score = current.data?.score ?? tenant.ratingScore
  const dims = extractDims(current.data)
  const assessedAt = current.data?.assessedAt || current.data?.createdAt || tenant.ratingAssessedAt
  const historyItems = pageItems(history.data)
  const trendPoints = pageItems(trend.data)
    .slice()
    .reverse()
    .map((rating) => ({ score: rating.score, grade: rating.grade, at: rating.createdAt }))

  const previousTrend = trendPoints.length >= 2 ? trendPoints[trendPoints.length - 2] : null
  const currentDelta =
    typeof current.data?.scoreDelta === 'number'
      ? current.data.scoreDelta
      : previousTrend && typeof score === 'number'
        ? score - previousTrend.score
        : null
  const previousGrade = current.data?.previousGrade ?? previousTrend?.grade ?? null
  const currentGradeChanged =
    current.data?.gradeChanged ?? Boolean(previousGrade && grade && previousGrade !== grade)

  const historyDelta = (rating: RatingView, index: number) => {
    if (typeof rating.scoreDelta === 'number') {
      return {
        delta: rating.scoreDelta,
        previousGrade: rating.previousGrade ?? null,
        gradeChanged: Boolean(rating.gradeChanged),
      }
    }
    const previous = historyItems[index + 1]
    if (!previous) return { delta: null, previousGrade: null, gradeChanged: false }
    return {
      delta: rating.score - previous.score,
      previousGrade: previous.grade,
      gradeChanged: rating.grade !== previous.grade,
    }
  }

  const refreshAll = () => {
    current.reload()
    history.reload()
    trend.reload()
    onTenantUpdated()
  }

  const submitAssess = async () => {
    setAssessing(true)
    try {
      const payload: RatingAssessRequest = assessNote.trim() ? { note: assessNote.trim() } : {}
      await api.post<RatingCurrentView>(`/api/tenants/${tenantId}/ratings/assess`, payload)
      toast.success('评级已重新评估')
      setAssessOpen(false)
      setAssessNote('')
      refreshAll()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setAssessing(false)
    }
  }

  const openOverride = () => {
    setOverrideGrade(grade && RATING_GRADE_OPTIONS.includes(grade as (typeof RATING_GRADE_OPTIONS)[number]) ? grade : 'A')
    setOverrideScore(typeof score === 'number' ? String(score) : '80')
    setOverrideReason('')
    setOverrideError(null)
    setOverrideOpen(true)
  }

  const submitOverride = async () => {
    setOverrideError(null)
    const parsedScore = Number(overrideScore)
    if (!Number.isInteger(parsedScore) || parsedScore < 0 || parsedScore > 100) {
      setOverrideError('评分必须为 0-100 的整数')
      return
    }
    if (!overrideReason.trim() || overrideReason.trim().length < 2) {
      setOverrideError('人工覆盖必须填写原因（至少 2 个字符）')
      return
    }
    setOverriding(true)
    try {
      const payload: RatingOverrideRequest = {
        grade: overrideGrade,
        score: parsedScore,
        reason: overrideReason.trim(),
      }
      await api.post<RatingView>(`/api/tenants/${tenantId}/ratings/override`, payload)
      toast.success('评级已人工覆盖')
      setOverrideOpen(false)
      refreshAll()
    } catch (err) {
      setOverrideError(errorMessage(err))
    } finally {
      setOverriding(false)
    }
  }

  return (
    <div className="rating-layout">
      <SectionCard
        title="当前评级"
        description="由 tenant-rating 规则与五维加权评分生成；人工覆盖仅平台管理员可用"
        actions={
          <div className="card-head-actions">
            {canManage ? (
              <button type="button" className="btn btn-primary btn-sm" onClick={() => setAssessOpen(true)}>
                <Icon name="refresh" size={15} />
                重新评估
              </button>
            ) : null}
            {isAdmin ? (
              <button type="button" className="btn btn-ghost btn-sm" onClick={openOverride}>
                人工覆盖
              </button>
            ) : null}
          </div>
        }
      >
        <AsyncBlock
          loading={current.loading}
          error={current.error}
          onRetry={current.reload}
        >
          {grade || typeof score === 'number' ? (
            <>
              <div className="rating-hero">
                <RatingBadge grade={grade} size="lg" />
                <div className="rating-summary">
                  <div className="rating-score-row">
                    <div className="rating-score">
                      {typeof score === 'number' ? score : '—'}
                      <span>/ 100</span>
                    </div>
                    <span className={`delta ${deltaClass(currentDelta)}`}>{deltaText(currentDelta)}</span>
                    {currentGradeChanged && previousGrade ? (
                      <span className="grade-change">
                        {previousGrade} → {grade}
                      </span>
                    ) : null}
                  </div>
                  <p className="muted">
                    来源：{ratingSourceLabel(current.data?.source)} · 评估人：
                    {current.data?.assessedBy || '—'} · {formatDateTime(assessedAt)}
                  </p>
                  {current.data?.note ? <p className="muted">备注：{current.data.note}</p> : null}
                </div>
              </div>

              {dims ? (
                <div className="dim-list">
                  {RATING_DIMENSIONS.map((dimension) => {
                    const value = dims[dimension.key]
                    return (
                      <div key={dimension.key} className="dim-row">
                        <div className="dim-head">
                          <span>
                            {dimension.label}
                            <em className="dim-weight">{dimension.weight}%</em>
                          </span>
                          <strong>{value}</strong>
                        </div>
                        <div className="dim-bar">
                          <span
                            className="dim-fill"
                            style={{ width: `${Math.min(Math.max(value, 0), 100)}%` }}
                          />
                        </div>
                      </div>
                    )
                  })}
                </div>
              ) : (
                <p className="muted dims-empty">当前评级未返回五维分数。</p>
              )}
            </>
          ) : (
            <EmptyBlock
              text="暂无评级"
              hint={canManage ? '点击右上角「重新评估」生成第一份评级' : '平台尚未完成该租户评级'}
            />
          )}
        </AsyncBlock>
      </SectionCard>

      <SectionCard title="评分趋势" description="最近 10 次评级综合分（纯 SVG 折线，0–100）">
        <AsyncBlock
          loading={trend.loading}
          error={trend.error}
          onRetry={trend.reload}
          empty={!trendPoints.length}
          emptyText="暂无评级趋势"
          emptyHint="完成评级评估后将展示最近 10 次综合分变化"
        >
          <RatingTrendChart points={trendPoints} />
        </AsyncBlock>
      </SectionCard>

      <SectionCard title="评级历史" description="append-only 历史记录（倒序）">
        <AsyncBlock
          loading={history.loading}
          error={history.error}
          onRetry={history.reload}
          empty={!historyItems.length}
          emptyText="暂无评级历史"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>评估时间</th>
                  <th>等级</th>
                  <th>分数</th>
                  <th>变化</th>
                  <th>来源</th>
                  <th>评估人</th>
                  <th>备注</th>
                </tr>
              </thead>
              <tbody>
                {historyItems.map((rating, index) => {
                  const delta = historyDelta(rating, index)
                  return (
                    <tr key={rating.id} className={delta.gradeChanged ? 'rating-row-changed' : ''}>
                      <td className="muted nowrap">{formatDateTime(rating.createdAt)}</td>
                      <td>
                        <div className="rating-grade-cell">
                          <RatingBadge grade={rating.grade} size="sm" />
                          {delta.gradeChanged && delta.previousGrade ? (
                            <span className="grade-change" title="等级发生变化">
                              {delta.previousGrade} → {rating.grade}
                            </span>
                          ) : null}
                        </div>
                      </td>
                      <td>{rating.score}</td>
                      <td>
                        <span className={`delta ${deltaClass(delta.delta)}`}>{deltaText(delta.delta)}</span>
                      </td>
                      <td>
                        <Badge tone={rating.source === 'MANUAL' ? 'amber' : 'gray'}>
                          {ratingSourceLabel(rating.source)}
                        </Badge>
                      </td>
                      <td className="muted">{rating.assessedBy || '—'}</td>
                      <td className="muted rating-note">{rating.note || '—'}</td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
          </div>
          <Pager
            page={page}
            totalPages={history.data?.totalPages}
            total={pageTotal(history.data)}
            onPage={setPage}
            disabled={history.loading}
          />
        </AsyncBlock>
      </SectionCard>

      <Modal
        open={assessOpen}
        title="重新评估租户评级"
        description="将基于当前租户画像与规则引擎重新计算五维分数、等级并写入历史。"
        onClose={() => {
          if (!assessing) setAssessOpen(false)
        }}
        footer={
          <>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => setAssessOpen(false)}
              disabled={assessing}
            >
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={submitAssess} disabled={assessing}>
              {assessing ? <span className="spinner spinner-light" /> : null}
              确认评估
            </button>
          </>
        }
      >
        <Field label="备注" hint="可选，写入评级历史">
          <textarea
            className="input"
            rows={3}
            value={assessNote}
            placeholder="例如：季度例行评估"
            onChange={(event) => setAssessNote(event.target.value)}
          />
        </Field>
      </Modal>

      <Modal
        open={overrideOpen}
        title="人工覆盖评级"
        description="仅平台管理员可操作；覆盖会写入 source=MANUAL 历史并记录审计。"
        onClose={() => {
          if (!overriding) setOverrideOpen(false)
        }}
        footer={
          <>
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => setOverrideOpen(false)}
              disabled={overriding}
            >
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={submitOverride} disabled={overriding}>
              {overriding ? <span className="spinner spinner-light" /> : null}
              确认覆盖
            </button>
          </>
        }
      >
        <div className="form-grid form-grid-2">
          <Field label="等级" required>
            <select
              className="select"
              value={overrideGrade}
              onChange={(event) => setOverrideGrade(event.target.value)}
            >
              {RATING_GRADE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {option}
                </option>
              ))}
            </select>
          </Field>
          <Field label="分数（0-100）" required>
            <input
              className="input"
              value={overrideScore}
              inputMode="numeric"
              onChange={(event) => setOverrideScore(event.target.value)}
            />
          </Field>
        </div>
        <Field label="覆盖原因" required hint="至少 2 个字符，将写入历史与审计">
          <textarea
            className="input"
            rows={3}
            value={overrideReason}
            placeholder="例如：客户资质复核后上调评级"
            onChange={(event) => setOverrideReason(event.target.value)}
          />
        </Field>
        {overrideError ? <p className="field-error modal-error">{overrideError}</p> : null}
      </Modal>
    </div>
  )
}
