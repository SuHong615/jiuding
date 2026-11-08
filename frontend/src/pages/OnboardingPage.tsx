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
import { Link } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { OnboardingRequest, OnboardingResult, OnboardingStatusView } from '../api/types'
import { Badge } from '../components/Badge'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'
import { onboardingStatusLabel, onboardingStatusTone, planLabel } from '../utils/labels'

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/

interface FormState {
  companyName: string
  tenantCode: string
  contactName: string
  contactEmail: string
  contactPhone: string
  plan: string
  region: string
  industry: string
  notes: string
}

const EMPTY_FORM: FormState = {
  companyName: '',
  tenantCode: '',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  plan: 'PRO',
  region: 'cn-shanghai',
  industry: '',
  notes: '',
}

export function OnboardingPage() {
  const toast = useToast()
  const [form, setForm] = useState<FormState>(EMPTY_FORM)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)
  const [result, setResult] = useState<OnboardingResult | null>(null)

  const [queryNo, setQueryNo] = useState('')
  const [querying, setQuerying] = useState(false)
  const [queryError, setQueryError] = useState<string | null>(null)
  const [statusView, setStatusView] = useState<OnboardingStatusView | null>(null)

  const setText =
    (key: keyof FormState) =>
    (event: React.ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
      setForm((prev) => ({ ...prev, [key]: event.target.value }))
      setErrors((prev) => {
        if (!prev[key]) return prev
        const next = { ...prev }
        delete next[key]
        return next
      })
    }

  const validate = (): boolean => {
    const next: Record<string, string> = {}
    if (!form.companyName.trim()) next.companyName = '请输入公司名称'
    if (!form.contactName.trim()) next.contactName = '请输入联系人'
    if (!form.contactEmail.trim()) next.contactEmail = '请输入联系邮箱'
    else if (!EMAIL_RE.test(form.contactEmail.trim())) next.contactEmail = '邮箱格式不正确'
    if (form.tenantCode.trim() && !/^[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}$/.test(form.tenantCode.trim())) {
      next.tenantCode = '2-64 位字母/数字/-/_，需以字母或数字开头'
    }
    setErrors(next)
    return Object.keys(next).length === 0
  }

  const submit = async () => {
    if (!validate()) return
    setSubmitting(true)
    try {
      const payload: OnboardingRequest = {
        companyName: form.companyName.trim(),
        tenantCode: form.tenantCode.trim() || undefined,
        contactName: form.contactName.trim(),
        contactEmail: form.contactEmail.trim(),
        contactPhone: form.contactPhone.trim() || undefined,
        plan: form.plan,
        region: form.region.trim() || 'cn-shanghai',
        industry: form.industry.trim() || undefined,
        notes: form.notes.trim() || undefined,
      }
      const response = await api.post<OnboardingResult>('/api/public/onboarding', payload)
      setResult(response)
      setQueryNo(response.applicationNo)
      toast.success('入驻申请已提交')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  const queryStatus = async () => {
    const applicationNo = queryNo.trim()
    if (!applicationNo) {
      setQueryError('请输入申请编号')
      return
    }
    setQuerying(true)
    setQueryError(null)
    setStatusView(null)
    try {
      const response = await api.get<OnboardingStatusView>(
        `/api/public/onboarding/${encodeURIComponent(applicationNo)}`,
      )
      setStatusView(response)
    } catch (err) {
      setQueryError(errorMessage(err))
    } finally {
      setQuerying(false)
    }
  }

  const copy = (value: string) => {
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
    <div className="onboarding-page">
      <div className="onboarding-card">
        <header className="onboarding-head">
          <div className="login-brand onboarding-brand">
            <span className="brand-mark">TC</span>
            <div>
              <strong>多租户中心</strong>
              <span>Tenancy Console</span>
            </div>
          </div>
          <Link to="/login" className="btn btn-ghost btn-sm">
            <Icon name="arrowLeft" size={15} />
            返回登录
          </Link>
        </header>

        <h1 className="onboarding-title">申请租户入驻</h1>
        <p className="onboarding-sub">
          填写公司信息后提交，系统将自动进行合规审核；符合条件自动开通，否则进入人工审批。
        </p>

        <div className="onboarding-grid">
          <div className="onboarding-form">
            <h3>公司信息</h3>
            <div className="form-grid">
              <Field label="公司名称" required error={errors.companyName}>
                <input className={`input ${errors.companyName ? 'has-error' : ''}`} value={form.companyName} placeholder="Acme 云计算有限公司" onChange={setText('companyName')} />
              </Field>
              <Field label="租户代码" error={errors.tenantCode} hint="可选，由小写字母/数字/- 组成；留空由系统生成">
                <input className={`input mono ${errors.tenantCode ? 'has-error' : ''}`} value={form.tenantCode} placeholder="如 acme" onChange={setText('tenantCode')} />
              </Field>
              <Field label="联系人" required error={errors.contactName}>
                <input className={`input ${errors.contactName ? 'has-error' : ''}`} value={form.contactName} placeholder="张三" onChange={setText('contactName')} />
              </Field>
              <Field label="联系邮箱" required error={errors.contactEmail}>
                <input className={`input ${errors.contactEmail ? 'has-error' : ''}`} value={form.contactEmail} placeholder="ops@acme.example" onChange={setText('contactEmail')} />
              </Field>
              <Field label="联系电话">
                <input className="input" value={form.contactPhone} placeholder="+86 138 0000 0000" onChange={setText('contactPhone')} />
              </Field>
              <Field label="计划">
                <select className="select" value={form.plan} onChange={setText('plan')}>
                  <option value="FREE">{planLabel('FREE')}</option>
                  <option value="PRO">{planLabel('PRO')}</option>
                  <option value="ENTERPRISE">{planLabel('ENTERPRISE')}</option>
                </select>
              </Field>
              <Field label="区域">
                <input className="input" value={form.region} placeholder="cn-shanghai" onChange={setText('region')} />
              </Field>
              <Field label="行业">
                <input className="input" value={form.industry} placeholder="如 saas / fintech" onChange={setText('industry')} />
              </Field>
            </div>
            <Field label="备注">
              <textarea className="input" rows={3} value={form.notes} placeholder="其他需要说明的信息（可选）" onChange={setText('notes')} />
            </Field>
            <button type="button" className="btn btn-primary btn-block" onClick={submit} disabled={submitting}>
              {submitting ? <span className="spinner spinner-light" /> : null}
              {submitting ? '提交中…' : '提交入驻申请'}
            </button>

            {result ? (
              <div className="onboarding-success">
                <div className="onboarding-success-head">
                  <Badge tone={onboardingStatusTone(result.status)} dot>
                    {onboardingStatusLabel(result.status)}
                  </Badge>
                  <span>申请已提交</span>
                </div>
                <div className="application-no">
                  <span>申请编号</span>
                  <code>{result.applicationNo}</code>
                  <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(result.applicationNo)}>
                    <Icon name="copy" size={14} />
                    复制
                  </button>
                </div>
                {result.tenantCode ? (
                  <p className="field-hint">租户代码：<span className="mono">{result.tenantCode}</span></p>
                ) : null}
                <p className="field-hint">{result.message || '请保留申请编号，用于查询审核进度。'}</p>
              </div>
            ) : null}
          </div>

          <aside className="onboarding-status">
            <h3>查询申请状态</h3>
            <Field label="申请编号">
              <input
                className="input mono"
                value={queryNo}
                placeholder="如 APP-20261108-0001"
                onChange={(event) => {
                  setQueryNo(event.target.value)
                  setQueryError(null)
                }}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') queryStatus()
                }}
              />
            </Field>
            <button type="button" className="btn btn-ghost btn-block" onClick={queryStatus} disabled={querying}>
              {querying ? <span className="spinner" /> : <Icon name="search" size={15} />}
              {querying ? '查询中…' : '查询状态'}
            </button>
            {queryError ? <p className="field-error">{queryError}</p> : null}
            {statusView ? (
              <div className="status-query-result">
                <div className="status-query-title">
                  <Badge tone={onboardingStatusTone(statusView.status)} dot>
                    {onboardingStatusLabel(statusView.status)}
                  </Badge>
                  <strong>{statusView.applicationNo}</strong>
                </div>
                <div className="info-grid">
                  <div className="info-row"><span className="info-label">公司</span><span className="info-value">{statusView.companyName || '—'}</span></div>
                  <div className="info-row"><span className="info-label">租户代码</span><span className="info-value mono">{statusView.tenantCode || '—'}</span></div>
                  <div className="info-row"><span className="info-label">计划</span><span className="info-value">{planLabel(statusView.plan)}</span></div>
                  <div className="info-row"><span className="info-label">Agent 决策</span><span className="info-value">{statusView.agentDecision || '—'}</span></div>
                  <div className="info-row"><span className="info-label">风险等级</span><span className="info-value">{statusView.agentRiskLevel || '—'}</span></div>
                  <div className="info-row"><span className="info-label">更新时间</span><span className="info-value">{formatDateTime(statusView.updatedAt)}</span></div>
                </div>
                {statusView.message ? <p className="field-hint">{statusView.message}</p> : null}
                {statusView.status === 'PENDING_MANUAL' ? (
                  <p className="field-hint">申请正在等待平台审批人处理，可通过申请编号随时查询。</p>
                ) : null}
              </div>
            ) : null}
            <div className="onboarding-tips">
              <strong>流程说明</strong>
              <ol>
                <li>提交公司与联系人信息，系统创建租户草稿。</li>
                <li>Agent 进行风险审查：低风险且非企业版自动开通。</li>
                <li>其余情况进入人工审批，审批通过后自动开通。</li>
              </ol>
            </div>
          </aside>
        </div>
      </div>
    </div>
  )
}
