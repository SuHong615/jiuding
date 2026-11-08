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
import { useEffect, useState, type ChangeEvent } from 'react'
import { Drawer } from '../components/Drawer'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { api, errorMessage } from '../api/client'
import type { TenantCreatePayload, TenantView } from '../api/types'
import { PLAN_OPTIONS, planLabel } from '../utils/labels'
import { isPlainObject, parseJsonText } from '../utils/format'

interface TenantFormState {
  tenantCode: string
  name: string
  contactName: string
  contactEmail: string
  contactPhone: string
  plan: string
  region: string
  isolationMode: string
  quotaSeats: string
  quotaStorageGb: string
  quotaApiRpm: string
  source: string
  attributesText: string
}

const EMPTY_FORM: TenantFormState = {
  tenantCode: '',
  name: '',
  contactName: '',
  contactEmail: '',
  contactPhone: '',
  plan: 'PRO',
  region: 'cn-shanghai',
  isolationMode: 'SHARED_SCHEMA',
  quotaSeats: '50',
  quotaStorageGb: '100',
  quotaApiRpm: '600',
  source: 'MANUAL',
  attributesText: '',
}

function formFromTenant(tenant?: TenantView | null): TenantFormState {
  if (!tenant) return EMPTY_FORM
  return {
    tenantCode: tenant.tenantCode ?? '',
    name: tenant.name ?? '',
    contactName: tenant.contactName ?? '',
    contactEmail: tenant.contactEmail ?? '',
    contactPhone: tenant.contactPhone ?? '',
    plan: tenant.plan || 'PRO',
    region: tenant.region || 'cn-shanghai',
    isolationMode: tenant.isolationMode || 'SHARED_SCHEMA',
    quotaSeats: tenant.quotaSeats != null ? String(tenant.quotaSeats) : '',
    quotaStorageGb: tenant.quotaStorageGb != null ? String(tenant.quotaStorageGb) : '',
    quotaApiRpm: tenant.quotaApiRpm != null ? String(tenant.quotaApiRpm) : '',
    source: tenant.source || 'MANUAL',
    attributesText: '',
  }
}

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]{2,}$/
const CODE_RE = /^[a-zA-Z0-9][a-zA-Z0-9_-]{1,63}$/

export function TenantCreateDrawer({
  open,
  tenant,
  onClose,
  onSaved,
}: {
  open: boolean
  tenant?: TenantView | null
  onClose: () => void
  onSaved: (tenant: TenantView) => void
}) {
  const toast = useToast()
  const isEdit = Boolean(tenant)
  const [form, setForm] = useState<TenantFormState>(() => formFromTenant(tenant))
  const [autoSubmit, setAutoSubmit] = useState(false)
  const [errors, setErrors] = useState<Record<string, string>>({})
  const [submitting, setSubmitting] = useState(false)

  useEffect(() => {
    if (!open) return
    setForm(formFromTenant(tenant))
    setAutoSubmit(false)
    setErrors({})
  }, [open, tenant])

  const setText =
    (key: keyof TenantFormState) =>
    (event: ChangeEvent<HTMLInputElement | HTMLSelectElement | HTMLTextAreaElement>) => {
      const value = event.target.value
      setForm((prev) => ({ ...prev, [key]: value }))
      setErrors((prev) => {
        if (!prev[key]) return prev
        const next = { ...prev }
        delete next[key]
        return next
      })
    }

  const toNumber = (value: string): number | undefined => {
    if (!value.trim()) return undefined
    return Number(value)
  }

  const validate = (): boolean => {
    const next: Record<string, string> = {}
    if (!form.tenantCode.trim()) next.tenantCode = '请输入租户编码'
    else if (!CODE_RE.test(form.tenantCode.trim()))
      next.tenantCode = '2-64 位字母/数字/-/_，需以字母或数字开头'
    if (!form.name.trim()) next.name = '请输入租户名称'
    else if (form.name.trim().length > 128) next.name = '名称不能超过 128 个字符'
    if (form.contactEmail.trim() && !EMAIL_RE.test(form.contactEmail.trim()))
      next.contactEmail = '邮箱格式不正确'
    if (form.contactPhone.trim() && !/^[0-9+\-\s()]{6,32}$/.test(form.contactPhone.trim()))
      next.contactPhone = '联系电话格式不正确'
    for (const [key, label] of [
      ['quotaSeats', '席位配额'],
      ['quotaStorageGb', '存储配额'],
      ['quotaApiRpm', 'API 配额'],
    ] as const) {
      const raw = form[key].trim()
      if (raw && (!/^\d+$/.test(raw) || Number(raw) < 0)) {
        next[key] = `${label}必须为非负整数`
      }
    }
    const attributesResult = parseJsonText(form.attributesText)
    if (!attributesResult.ok) next.attributesText = attributesResult.error
    else if (attributesResult.value !== undefined && !isPlainObject(attributesResult.value))
      next.attributesText = 'attributes 必须是 JSON 对象（{...}）'
    setErrors(next)
    return Object.keys(next).length === 0
  }

  const handleSubmit = async () => {
    if (!validate()) return
    const attributesResult = parseJsonText(form.attributesText)
    if (!attributesResult.ok) return
    const payload: TenantCreatePayload = {
      tenantCode: form.tenantCode.trim(),
      name: form.name.trim(),
      contactName: form.contactName.trim() || undefined,
      contactEmail: form.contactEmail.trim() || undefined,
      contactPhone: form.contactPhone.trim() || undefined,
      plan: form.plan,
      region: form.region.trim() || 'cn-shanghai',
      isolationMode: form.isolationMode,
      quotaSeats: toNumber(form.quotaSeats),
      quotaStorageGb: toNumber(form.quotaStorageGb),
      quotaApiRpm: toNumber(form.quotaApiRpm),
      source: form.source,
      attributes:
        attributesResult.value === undefined
          ? undefined
          : (attributesResult.value as Record<string, unknown>),
      autoSubmit: !isEdit && autoSubmit,
    }
    setSubmitting(true)
    try {
      const saved = isEdit && tenant
        ? await api.put<TenantView>(`/api/tenants/${tenant.id}`, payload)
        : await api.post<TenantView>('/api/tenants', payload)
      toast.success(isEdit ? `租户「${saved.name || payload.name}」已更新` : `租户「${saved.name || payload.name}」已创建`)
      onSaved(saved)
      onClose()
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <Drawer
      open={open}
      title={isEdit ? '编辑租户' : '新建租户'}
      subtitle={
        isEdit
          ? `修改租户 ${tenant?.tenantCode ?? ''} 的基础信息、配额与扩展属性`
          : '创建后可立即提交审批，或保存为草稿稍后提交'
      }
      width={600}
      onClose={onClose}
      footer={
        <>
          <button type="button" className="btn btn-ghost" onClick={onClose} disabled={submitting}>
            取消
          </button>
          <button type="button" className="btn btn-primary" onClick={handleSubmit} disabled={submitting}>
            {submitting ? <span className="spinner spinner-light" /> : <Icon name="plus" size={15} />}
            {submitting ? '保存中…' : isEdit ? '保存修改' : '创建租户'}
          </button>
        </>
      }
    >
      <div className="form-section">
        <h4>基础信息</h4>
        <div className="form-grid">
          <Field label="租户编码" required error={errors.tenantCode} hint="全局唯一，如 t-acme">
            <input
              className={`input ${errors.tenantCode ? 'has-error' : ''}`}
              value={form.tenantCode}
              placeholder="t-acme"
              onChange={setText('tenantCode')}
            />
          </Field>
          <Field label="租户名称" required error={errors.name}>
            <input
              className={`input ${errors.name ? 'has-error' : ''}`}
              value={form.name}
              placeholder="Acme 云计算有限公司"
              onChange={setText('name')}
            />
          </Field>
          <Field label="联系人">
            <input className="input" value={form.contactName} placeholder="张三" onChange={setText('contactName')} />
          </Field>
          <Field label="联系邮箱" error={errors.contactEmail}>
            <input
              className={`input ${errors.contactEmail ? 'has-error' : ''}`}
              value={form.contactEmail}
              placeholder="ops@acme.example"
              onChange={setText('contactEmail')}
            />
          </Field>
          <Field label="联系电话" error={errors.contactPhone}>
            <input
              className={`input ${errors.contactPhone ? 'has-error' : ''}`}
              value={form.contactPhone}
              placeholder="+86 138 0000 0000"
              onChange={setText('contactPhone')}
            />
          </Field>
          <Field label="来源">
            <select className="select" value={form.source} onChange={setText('source')}>
              <option value="MANUAL">手工创建</option>
              <option value="IMPORT">批量导入</option>
              <option value="API">API 接入</option>
              <option value="DISCOVERY">自动发现</option>
            </select>
          </Field>
        </div>
      </div>

      <div className="form-section">
        <h4>套餐与部署</h4>
        <div className="form-grid">
          <Field label="计划">
            <select className="select" value={form.plan} onChange={setText('plan')}>
              {PLAN_OPTIONS.map((plan) => (
                <option key={plan} value={plan}>
                  {planLabel(plan)}
                </option>
              ))}
            </select>
          </Field>
          <Field label="区域">
            <input className="input" value={form.region} placeholder="cn-shanghai" onChange={setText('region')} />
          </Field>
          <Field label="隔离模式" hint="原型阶段仅记录，实际均为共享表行级隔离">
            <select className="select" value={form.isolationMode} onChange={setText('isolationMode')}>
              <option value="SHARED_SCHEMA">共享表（行级隔离）</option>
              <option value="DEDICATED_SCHEMA">独立 Schema（预留）</option>
              <option value="DEDICATED_DB">独立数据库（预留）</option>
            </select>
          </Field>
          <Field label="席位配额" error={errors.quotaSeats}>
            <input
              className={`input ${errors.quotaSeats ? 'has-error' : ''}`}
              value={form.quotaSeats}
              inputMode="numeric"
              onChange={setText('quotaSeats')}
            />
          </Field>
          <Field label="存储配额（GB）" error={errors.quotaStorageGb}>
            <input
              className={`input ${errors.quotaStorageGb ? 'has-error' : ''}`}
              value={form.quotaStorageGb}
              inputMode="numeric"
              onChange={setText('quotaStorageGb')}
            />
          </Field>
          <Field label="API 配额（RPM）" error={errors.quotaApiRpm}>
            <input
              className={`input ${errors.quotaApiRpm ? 'has-error' : ''}`}
              value={form.quotaApiRpm}
              inputMode="numeric"
              onChange={setText('quotaApiRpm')}
            />
          </Field>
        </div>
      </div>

      <div className="form-section">
        <h4>扩展属性</h4>
        <Field
          label="attributes（JSON 对象，可选）"
          hint={isEdit ? '编辑时留空表示保留原有扩展属性' : '例如 {"industry":"saas","domain":"acme.example"}'}
          error={errors.attributesText}
        >
          <textarea
            className={`input json-editor ${errors.attributesText ? 'has-error' : ''}`}
            rows={4}
            value={form.attributesText}
            spellCheck={false}
            placeholder='{"industry":"saas"}'
            onChange={setText('attributesText')}
          />
        </Field>
        {!isEdit ? (
          <label className="checkbox-row">
            <input
              type="checkbox"
              checked={autoSubmit}
              onChange={(event) => setAutoSubmit(event.target.checked)}
            />
            <span>
              创建后自动提交审批
              <em>（提交后状态进入风险审查，需等待审批流程）</em>
            </span>
          </label>
        ) : null}
      </div>
    </Drawer>
  )
}
