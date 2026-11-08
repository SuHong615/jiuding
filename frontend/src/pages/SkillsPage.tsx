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
import { Link } from 'react-router-dom'
import { api, downloadFile, errorMessage } from '../api/client'
import type { SkillView } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { useAuth } from '../auth/AuthContext'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { Modal } from '../components/Modal'
import { Pager } from '../components/Pager'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'
import {
  skillSourceLabel,
  skillStatusLabel,
  skillStatusTone,
  skillTypeLabel,
  skillTypeTone,
} from '../utils/labels'

const MAX_ZIP_BYTES = 1024 * 1024
const PAGE_SIZE = 9

const STATUS_OPTIONS = ['DRAFT', 'PENDING_REVIEW', 'APPROVED', 'REJECTED', 'DISABLED']
const SOURCE_OPTIONS = ['BUILTIN', 'UPLOADED']

export function SkillsPage() {
  const toast = useToast()
  const { isPlatformAdmin, hasRole, hasPermission } = useAuth()
  const canManage = isPlatformAdmin || hasRole('TENANT_ADMIN') || hasPermission('SKILL_MANAGE')
  const canReview = isPlatformAdmin

  const [status, setStatus] = useState('')
  const [source, setSource] = useState('')
  const [enabled, setEnabled] = useState('')
  const [keywordInput, setKeywordInput] = useState('')
  const [keyword, setKeyword] = useState('')
  const [page, setPage] = useState(0)
  const [refreshKey, setRefreshKey] = useState(0)

  const query = useMemo(() => {
    const params = new URLSearchParams()
    if (status) params.set('status', status)
    if (source) params.set('source', source)
    if (enabled) params.set('enabled', enabled)
    return params.toString()
  }, [status, source, enabled])

  const skills = useAsync(
    () => api.get<SkillView[]>(`/api/skills${query ? `?${query}` : ''}`),
    [query, refreshKey],
  )
  const items = skills.data ?? []
  const filtered = useMemo(() => {
    const term = keyword.trim().toLowerCase()
    if (!term) return items
    return items.filter((skill) =>
      [skill.id, skill.name, skill.description, skill.type]
        .filter(Boolean)
        .some((value) => String(value).toLowerCase().includes(term)),
    )
  }, [items, keyword])
  const totalPages = Math.max(1, Math.ceil(filtered.length / PAGE_SIZE))
  const currentPage = Math.min(page, totalPages - 1)
  const pageItems = filtered.slice(currentPage * PAGE_SIZE, currentPage * PAGE_SIZE + PAGE_SIZE)

  const reload = () => {
    skills.reload()
    setRefreshKey((value) => value + 1)
  }

  const fileInputRef = useRef<HTMLInputElement | null>(null)
  const [uploadOpen, setUploadOpen] = useState(false)
  const [uploadFile, setUploadFile] = useState<File | null>(null)
  const [submitForReview, setSubmitForReview] = useState(true)
  const [uploading, setUploading] = useState(false)

  const [busyId, setBusyId] = useState<string | null>(null)
  const [reviewTarget, setReviewTarget] = useState<SkillView | null>(null)
  const [reviewDecision, setReviewDecision] = useState<'APPROVE' | 'REJECT'>('APPROVE')
  const [reviewComment, setReviewComment] = useState('')
  const [reviewError, setReviewError] = useState<string | null>(null)
  const [reviewing, setReviewing] = useState(false)

  const pickUploadFile = (file: File | null) => {
    if (!file) return
    if (!file.name.toLowerCase().endsWith('.zip')) {
      toast.error('仅支持 .zip 技能包')
      return
    }
    if (file.size > MAX_ZIP_BYTES) {
      toast.error('技能包不能超过 1MB')
      return
    }
    setUploadFile(file)
  }

  const openUpload = () => {
    setUploadFile(null)
    setSubmitForReview(true)
    setUploadOpen(true)
    if (fileInputRef.current) fileInputRef.current.value = ''
  }

  const uploadSkill = async () => {
    if (!uploadFile) {
      toast.error('请选择技能包 zip')
      return
    }
    const form = new FormData()
    form.append('file', uploadFile)
    form.append('submitForReview', submitForReview ? 'true' : 'false')
    setUploading(true)
    try {
      await api.upload<SkillView>(
        `/api/skills/upload?submitForReview=${submitForReview ? 'true' : 'false'}`,
        form,
      )
      toast.success(submitForReview ? '技能包已上传并提交审核' : '技能包已上传为草稿')
      setUploadOpen(false)
      reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setUploading(false)
    }
  }

  const downloadTemplate = async () => {
    try {
      await downloadFile('/api/skills/template', 'skill-template.zip')
      toast.success('模板已开始下载')
    } catch (err) {
      toast.error(errorMessage(err))
    }
  }

  const toggleEnabled = async (skill: SkillView) => {
    setBusyId(skill.id)
    try {
      await api.post<SkillView>(`/api/skills/${skill.id}/${skill.enabled ? 'disable' : 'enable'}`)
      toast.success(skill.enabled ? '技能已停用' : '技能已启用')
      reload()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const openReview = (skill: SkillView) => {
    setReviewTarget(skill)
    setReviewDecision('APPROVE')
    setReviewComment('')
    setReviewError(null)
  }

  const submitDecision = async () => {
    if (!reviewTarget) return
    setReviewError(null)
    if (reviewDecision === 'REJECT' && reviewComment.trim().length < 2) {
      setReviewError('驳回必须填写审核意见（至少 2 个字符）')
      return
    }
    setReviewing(true)
    try {
      await api.post<SkillView>(`/api/skills/${reviewTarget.id}/review`, {
        decision: reviewDecision,
        comment: reviewComment.trim() || undefined,
      })
      toast.success(reviewDecision === 'APPROVE' ? '审核通过' : '已驳回技能')
      setReviewTarget(null)
      reload()
    } catch (err) {
      setReviewError(errorMessage(err))
    } finally {
      setReviewing(false)
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>技能中心</h2>
            <p>卡片化管理技能包：上传、审核、启停与版本；详情页支持代码编辑、调试与审查。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={downloadTemplate}>
            <Icon name="download" size={15} />
            下载模板
          </button>
          <button type="button" className="btn btn-ghost btn-sm" onClick={reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
          {canManage ? (
            <button type="button" className="btn btn-primary btn-sm" onClick={openUpload}>
              <Icon name="upload" size={15} />
              上传技能
            </button>
          ) : null}
        </div>
      </header>

      <div className="card">
        <div className="filters">
          <div className="filter-item">
            <span className="filter-label">审核状态</span>
            <select
              className="select"
              value={status}
              onChange={(event) => {
                setStatus(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部状态</option>
              {STATUS_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {skillStatusLabel(option)}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-item">
            <span className="filter-label">来源</span>
            <select
              className="select"
              value={source}
              onChange={(event) => {
                setSource(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部来源</option>
              {SOURCE_OPTIONS.map((option) => (
                <option key={option} value={option}>
                  {skillSourceLabel(option)}
                </option>
              ))}
            </select>
          </div>
          <div className="filter-item">
            <span className="filter-label">启用</span>
            <select
              className="select"
              value={enabled}
              onChange={(event) => {
                setEnabled(event.target.value)
                setPage(0)
              }}
            >
              <option value="">全部</option>
              <option value="true">已启用</option>
              <option value="false">未启用</option>
            </select>
          </div>
          <div className="filter-item filter-grow">
            <span className="filter-label">搜索</span>
            <div className="input-with-icon">
              <Icon name="search" size={15} />
              <input
                className="input"
                value={keywordInput}
                placeholder="技能名称 / ID / 描述"
                onChange={(event) => {
                  setKeywordInput(event.target.value)
                  setKeyword(event.target.value.trim())
                  setPage(0)
                }}
              />
            </div>
          </div>
          <div className="filter-actions">
            <span className="muted small-count">共 {filtered.length} 个技能</span>
          </div>
        </div>

        <div className="card-body">
          <AsyncBlock
            loading={skills.loading}
            error={skills.error}
            onRetry={skills.reload}
            empty={!pageItems.length}
            emptyText="暂无匹配技能"
            emptyHint="调整筛选条件，或上传新的技能包"
          >
            <div className="skill-grid">
              {pageItems.map((skill) => (
                <article key={`${skill.id}-${skill.version}`} className="card skill-card">
                  <header className="skill-card-head">
                    <span className="skill-icon">
                      <Icon name="skills" size={20} />
                    </span>
                    <div className="skill-title">
                      <h3 title={skill.name || skill.id}>{skill.name || skill.id}</h3>
                      <span className="mono">{skill.id}</span>
                    </div>
                    <Badge tone={skillTypeTone(skill.type)}>{skillTypeLabel(skill.type)}</Badge>
                  </header>

                  <p className="skill-desc skill-card-desc" title={skill.description ?? undefined}>
                    {skill.description || '暂无描述'}
                  </p>

                  <div className="skill-card-meta">
                    <Badge tone={skillStatusTone(skill.status)} dot>
                      {skillStatusLabel(skill.status)}
                    </Badge>
                    <Badge tone={skill.enabled ? 'green' : 'gray'} dot>
                      {skill.enabled ? '已启用' : '未启用'}
                    </Badge>
                    <Badge tone={skill.source === 'UPLOADED' ? 'blue' : 'gray'}>
                      {skillSourceLabel(skill.source)}
                    </Badge>
                  </div>

                  <div className="skill-card-info">
                    <span>
                      最新版本 <strong className="mono">v{skill.version || '—'}</strong>
                    </span>
                    <span>更新时间 {formatDateTime(skill.updatedAt || skill.createdAt)}</span>
                  </div>

                  <footer className="skill-card-foot">
                    <Link to={`/skills/${skill.id}`} className="link-btn" aria-label="打开技能详情">
                      打开详情
                      <Icon name="arrowRight" size={13} />
                    </Link>
                    <div className="skill-card-actions">
                      {canManage && skill.status === 'APPROVED' ? (
                        <button
                          type="button"
                          className="link-btn"
                          disabled={busyId === skill.id}
                          onClick={() => toggleEnabled(skill)}
                          aria-label={skill.enabled ? '停用技能' : '启用技能'}
                        >
                          {busyId === skill.id ? '处理中…' : skill.enabled ? '停用' : '启用'}
                        </button>
                      ) : null}
                      {canReview && skill.status === 'PENDING_REVIEW' ? (
                        <button
                          type="button"
                          className="link-btn"
                          onClick={() => openReview(skill)}
                          aria-label="审核技能"
                        >
                          审核
                        </button>
                      ) : null}
                    </div>
                  </footer>
                </article>
              ))}
            </div>
            <Pager
              page={currentPage}
              totalPages={totalPages}
              total={filtered.length}
              onPage={setPage}
              disabled={skills.loading}
            />
          </AsyncBlock>
        </div>
      </div>

      <Modal
        open={uploadOpen}
        title="上传技能包"
        description="zip 包需包含 SKILL.md（可含 rules.yaml），大小 ≤ 1MB；与内置技能 id 冲突会被拒绝。"
        onClose={() => {
          if (!uploading) setUploadOpen(false)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setUploadOpen(false)} disabled={uploading}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={uploadSkill} disabled={uploading}>
              {uploading ? <span className="spinner spinner-light" /> : <Icon name="upload" size={15} />}
              {uploading ? '上传中…' : '上传'}
            </button>
          </>
        }
      >
        <div
          className="dropzone"
          onClick={() => fileInputRef.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(event) => {
            if (event.key === 'Enter' || event.key === ' ') fileInputRef.current?.click()
          }}
        >
          <input
            ref={fileInputRef}
            type="file"
            accept=".zip,application/zip"
            hidden
            onChange={(event) => pickUploadFile(event.target.files?.[0] ?? null)}
          />
          <span className="dropzone-icon">
            <Icon name="upload" size={26} />
          </span>
          {uploadFile ? (
            <>
              <strong>{uploadFile.name}</strong>
              <span className="muted">{(uploadFile.size / 1024).toFixed(1)} KB</span>
            </>
          ) : (
            <>
              <strong>点击选择技能包 zip</strong>
              <span className="muted">仅支持 .zip，单文件 ≤ 1MB</span>
            </>
          )}
        </div>
        <label className="checkbox-row">
          <input
            type="checkbox"
            checked={submitForReview}
            onChange={(event) => setSubmitForReview(event.target.checked)}
          />
          <span>上传后直接提交审核</span>
        </label>
      </Modal>

      <Modal
        open={Boolean(reviewTarget)}
        title={reviewTarget ? `审核技能 · ${reviewTarget.name || reviewTarget.id}` : '审核技能'}
        description="平台管理员审核：通过后技能可启用；驳回必须填写意见。"
        onClose={() => {
          if (!reviewing) setReviewTarget(null)
        }}
        footer={
          <>
            <button type="button" className="btn btn-ghost" onClick={() => setReviewTarget(null)} disabled={reviewing}>
              取消
            </button>
            <button type="button" className="btn btn-primary" onClick={submitDecision} disabled={reviewing}>
              {reviewing ? <span className="spinner spinner-light" /> : null}
              提交审核结果
            </button>
          </>
        }
      >
        <div className="decision-group">
          <label className={`decision-option ${reviewDecision === 'APPROVE' ? 'selected approve' : ''}`}>
            <input type="radio" checked={reviewDecision === 'APPROVE'} onChange={() => setReviewDecision('APPROVE')} />
            <span><strong>通过</strong><em>允许启用并热加载</em></span>
          </label>
          <label className={`decision-option ${reviewDecision === 'REJECT' ? 'selected reject' : ''}`}>
            <input type="radio" checked={reviewDecision === 'REJECT'} onChange={() => setReviewDecision('REJECT')} />
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
      </Modal>
    </div>
  )
}
