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
import { useRef, useState } from 'react'
import { api, errorMessage } from '../api/client'
import type { CollectJobView, PageResult } from '../api/types'
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
import { collectModeLabel, jobStatusLabel, jobStatusTone } from '../utils/labels'

const MAX_CSV_BYTES = 5 * 1024 * 1024
const PAGE_SIZE = 10

const SAMPLE_RECORDS = `[
  {
    "tenantCode": "t-demo-01",
    "name": "示例租户 A",
    "contactEmail": "ops@demo-a.example",
    "plan": "PRO",
    "region": "cn-shanghai",
    "quotaSeats": 50,
    "quotaStorageGb": 100,
    "quotaApiRpm": 600
  },
  {
    "tenantCode": "t-demo-02",
    "name": "示例租户 B",
    "contactEmail": "ops@demo-b.example",
    "plan": "ENTERPRISE",
    "region": "cn-beijing",
    "quotaSeats": 200,
    "quotaStorageGb": 500,
    "quotaApiRpm": 2000
  }
]`

export function CollectionPage() {
  const toast = useToast()
  const fileInputRef = useRef<HTMLInputElement | null>(null)

  const [page, setPage] = useState(0)
  const query = `?page=${page}&size=${PAGE_SIZE}`
  const jobs = useAsync(
    () => api.get<PageResult<CollectJobView>>(`/api/tenants/collect/jobs${query}`),
    [query],
  )
  const jobItems = pageItems(jobs.data)

  const [jobId, setJobId] = useState<string | null>(null)
  const jobDetail = useAsync(
    () =>
      jobId
        ? api.get<CollectJobView>(`/api/tenants/collect/jobs/${jobId}`)
        : Promise.resolve(null),
    [jobId],
  )

  // CSV 上传
  const [csvFile, setCsvFile] = useState<File | null>(null)
  const [csvSource, setCsvSource] = useState('manual-upload')
  const [dragging, setDragging] = useState(false)
  const [uploading, setUploading] = useState(false)

  const pickFile = (file: File | null) => {
    if (!file) return
    if (!file.name.toLowerCase().endsWith('.csv')) {
      toast.error('仅支持 .csv 文件')
      return
    }
    if (file.size > MAX_CSV_BYTES) {
      toast.error('CSV 文件不能超过 5MB')
      return
    }
    setCsvFile(file)
  }

  const handleDrop = (event: React.DragEvent<HTMLDivElement>) => {
    event.preventDefault()
    setDragging(false)
    pickFile(event.dataTransfer.files?.[0] ?? null)
  }

  const uploadCsv = async () => {
    if (!csvFile) {
      toast.error('请先选择 CSV 文件')
      return
    }
    const form = new FormData()
    form.append('file', csvFile)
    setUploading(true)
    try {
      const job = await api.upload<CollectJobView>(
        `/api/tenants/collect/csv?sourceName=${encodeURIComponent(csvSource.trim() || 'manual-upload')}`,
        form,
      )
      toast.success('CSV 已提交，正在后台采集')
      setCsvFile(null)
      if (fileInputRef.current) fileInputRef.current.value = ''
      jobs.reload()
      if (job?.id) setJobId(job.id)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setUploading(false)
    }
  }

  // 内联 JSON 批量导入
  const [jsonText, setJsonText] = useState(SAMPLE_RECORDS)
  const [jsonSource, setJsonSource] = useState('manual-batch')
  const [jsonAutoSubmit, setJsonAutoSubmit] = useState(false)
  const [jsonError, setJsonError] = useState<string | null>(null)
  const [importing, setImporting] = useState(false)

  const importJson = async () => {
    setJsonError(null)
    let parsed: unknown
    try {
      parsed = JSON.parse(jsonText)
    } catch (err) {
      setJsonError(`JSON 解析失败：${err instanceof Error ? err.message : '格式不正确'}`)
      return
    }
    if (!Array.isArray(parsed) || parsed.length === 0) {
      setJsonError('records 必须是非空 JSON 数组')
      return
    }
    for (let i = 0; i < parsed.length; i += 1) {
      const record = parsed[i]
      if (typeof record !== 'object' || record === null || Array.isArray(record)) {
        setJsonError(`第 ${i + 1} 条记录必须是 JSON 对象`)
        return
      }
      const item = record as Record<string, unknown>
      if (!item.tenantCode || !item.name) {
        setJsonError(`第 ${i + 1} 条记录缺少必填字段 tenantCode / name`)
        return
      }
    }
    setImporting(true)
    try {
      const job = await api.post<CollectJobView>('/api/tenants/collect', {
        sourceName: jsonSource.trim() || 'manual-batch',
        mode: 'MANUAL_BATCH',
        autoSubmit: jsonAutoSubmit,
        records: parsed,
      })
      toast.success(`已提交 ${parsed.length} 条记录，正在后台采集`)
      jobs.reload()
      if (job?.id) setJobId(job.id)
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setImporting(false)
    }
  }

  const openJob = (id: string) => setJobId(id)

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>租户采集</h2>
            <p>通过 CSV 上传或内联 JSON 批量导入租户；任务异步执行并记录成功 / 重复 / 失败明细</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={jobs.reload}>
            <Icon name="refresh" size={15} />
            刷新任务
          </button>
        </div>
      </header>

      <div className="grid-2">
        <SectionCard
          title="CSV 批量上传"
          description="UTF-8 编码，表头 tenantCode,name,contactEmail,plan,region,quotaSeats…，≤ 5MB"
        >
          <div
            className={`dropzone ${dragging ? 'dragging' : ''}`}
            onDragOver={(event) => {
              event.preventDefault()
              setDragging(true)
            }}
            onDragLeave={() => setDragging(false)}
            onDrop={handleDrop}
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
              accept=".csv,text/csv"
              hidden
              onChange={(event) => pickFile(event.target.files?.[0] ?? null)}
            />
            <span className="dropzone-icon">
              <Icon name="upload" size={26} />
            </span>
            {csvFile ? (
              <>
                <strong>{csvFile.name}</strong>
                <span className="muted">{(csvFile.size / 1024).toFixed(1)} KB · 点击可重新选择</span>
              </>
            ) : (
              <>
                <strong>拖拽 CSV 文件到此处，或点击选择</strong>
                <span className="muted">仅支持 .csv，单文件 ≤ 5MB</span>
              </>
            )}
          </div>
          <div className="form-grid form-grid-2">
            <div className="field">
              <label className="field-label">来源名称</label>
              <input
                className="input"
                value={csvSource}
                placeholder="manual-upload"
                onChange={(event) => setCsvSource(event.target.value)}
              />
            </div>
            <div className="field field-actions">
              <button
                type="button"
                className="btn btn-primary"
                onClick={uploadCsv}
                disabled={uploading || !csvFile}
              >
                {uploading ? <span className="spinner spinner-light" /> : <Icon name="upload" size={15} />}
                {uploading ? '上传中…' : '开始上传'}
              </button>
            </div>
          </div>
        </SectionCard>

        <SectionCard title="内联 JSON 批量导入" description="records 为 TenantCreateRequest 数组，后台异步处理">
          <div className="form-grid form-grid-2">
            <div className="field">
              <label className="field-label">来源名称</label>
              <input
                className="input"
                value={jsonSource}
                placeholder="manual-batch"
                onChange={(event) => setJsonSource(event.target.value)}
              />
            </div>
            <label className="checkbox-row checkbox-inline">
              <input
                type="checkbox"
                checked={jsonAutoSubmit}
                onChange={(event) => setJsonAutoSubmit(event.target.checked)}
              />
              <span>创建后自动提交审批</span>
            </label>
          </div>
          <div className="field">
            <label className="field-label">records（JSON 数组）</label>
            <textarea
              className={`input json-editor ${jsonError ? 'has-error' : ''}`}
              rows={9}
              value={jsonText}
              spellCheck={false}
              onChange={(event) => {
                setJsonText(event.target.value)
                setJsonError(null)
              }}
            />
            {jsonError ? <p className="field-error">{jsonError}</p> : null}
          </div>
          <div className="form-actions">
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setJsonText(SAMPLE_RECORDS)}>
              重置样例
            </button>
            <button type="button" className="btn btn-primary" onClick={importJson} disabled={importing}>
              {importing ? <span className="spinner spinner-light" /> : <Icon name="play" size={15} />}
              {importing ? '提交中…' : '开始导入'}
            </button>
          </div>
        </SectionCard>
      </div>

      <SectionCard
        title="采集任务"
        description="任务状态：执行中 / 成功 / 部分成功 / 失败"
        actions={
          <button type="button" className="icon-btn" onClick={jobs.reload} title="刷新任务列表">
            <Icon name="refresh" size={15} />
          </button>
        }
      >
        <AsyncBlock
          loading={jobs.loading}
          error={jobs.error}
          onRetry={jobs.reload}
          empty={!jobItems.length}
          emptyText="暂无采集任务"
          emptyHint="上传 CSV 或提交 JSON 批量导入后，任务会出现在这里"
        >
          <div className="table-wrap">
            <table className="table">
              <thead>
                <tr>
                  <th>任务 ID</th>
                  <th>来源</th>
                  <th>模式</th>
                  <th>状态</th>
                  <th>总数</th>
                  <th>成功</th>
                  <th>重复</th>
                  <th>失败</th>
                  <th>创建时间</th>
                  <th className="align-right">操作</th>
                </tr>
              </thead>
              <tbody>
                {jobItems.map((job) => (
                  <tr key={job.id}>
                    <td className="mono">{job.id}</td>
                    <td>{job.sourceName || '—'}</td>
                    <td className="muted">{collectModeLabel(job.mode)}</td>
                    <td>
                      <Badge tone={jobStatusTone(job.status)} dot>
                        {jobStatusLabel(job.status)}
                      </Badge>
                    </td>
                    <td>{job.total}</td>
                    <td className="text-green">{job.imported}</td>
                    <td className="text-amber">{job.duplicated}</td>
                    <td className={job.failed > 0 ? 'text-red' : 'muted'}>{job.failed}</td>
                    <td className="muted nowrap">{formatDateTime(job.createdAt)}</td>
                    <td className="align-right">
                      <button type="button" className="btn btn-ghost btn-sm" onClick={() => openJob(job.id)}>
                        详情
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <Pager
            page={page}
            totalPages={jobs.data?.totalPages}
            total={jobs.data?.totalElements ?? jobItems.length}
            onPage={setPage}
            disabled={jobs.loading}
          />
        </AsyncBlock>
      </SectionCard>

      <Drawer
        open={Boolean(jobId)}
        title="采集任务详情"
        subtitle={jobId ?? undefined}
        width={560}
        onClose={() => setJobId(null)}
      >
        <AsyncBlock
          loading={jobDetail.loading}
          error={jobDetail.error}
          onRetry={jobDetail.reload}
          empty={!jobDetail.data}
          emptyText="未找到任务"
        >
          {jobDetail.data ? (
            <>
              <div className="detail-meta">
                <Badge tone={jobStatusTone(jobDetail.data.status)} dot>
                  {jobStatusLabel(jobDetail.data.status)}
                </Badge>
                <span className="muted">{collectModeLabel(jobDetail.data.mode)}</span>
                <span className="mono muted">{jobDetail.data.sourceName}</span>
              </div>
              <div className="stat-grid stat-grid-4">
                <div className="stat">
                  <span>总数</span>
                  <strong>{jobDetail.data.total}</strong>
                </div>
                <div className="stat">
                  <span>成功</span>
                  <strong className="text-green">{jobDetail.data.imported}</strong>
                </div>
                <div className="stat">
                  <span>重复</span>
                  <strong className="text-amber">{jobDetail.data.duplicated}</strong>
                </div>
                <div className="stat">
                  <span>失败</span>
                  <strong className={jobDetail.data.failed > 0 ? 'text-red' : ''}>
                    {jobDetail.data.failed}
                  </strong>
                </div>
              </div>
              <div className="info-grid">
                <div className="info-row">
                  <span className="info-label">创建时间</span>
                  <span className="info-value">{formatDateTime(jobDetail.data.createdAt)}</span>
                </div>
                <div className="info-row">
                  <span className="info-label">完成时间</span>
                  <span className="info-value">{formatDateTime(jobDetail.data.finishedAt)}</span>
                </div>
                <div className="info-row">
                  <span className="info-label">任务 ID</span>
                  <span className="info-value mono">{jobDetail.data.id}</span>
                </div>
              </div>
              <h4 className="block-title">错误明细（最多 20 条）</h4>
              {jobDetail.data.errors?.length ? (
                <ul className="error-list">
                  {jobDetail.data.errors.map((error, index) => (
                    <li key={index}>
                      <Icon name="alert" size={14} />
                      <span>{error}</span>
                    </li>
                  ))}
                </ul>
              ) : (
                <p className="muted">无错误记录</p>
              )}
            </>
          ) : null}
        </AsyncBlock>
      </Drawer>
    </div>
  )
}
