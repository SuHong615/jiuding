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
import { api, errorMessage } from '../api/client'
import type { MfaSetupResult, MfaStatus } from '../api/types'
import { useAsync } from '../hooks/useAsync'
import { AsyncBlock } from '../components/PageState'
import { Badge } from '../components/Badge'
import { Field, SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { formatDateTime } from '../utils/format'

export function SecurityMfaPage() {
  const toast = useToast()
  const status = useAsync(() => api.get<MfaStatus>('/api/me/mfa'), [])

  const [setup, setSetup] = useState<MfaSetupResult | null>(null)
  const [settingUp, setSettingUp] = useState(false)
  const [verifyCode, setVerifyCode] = useState('')
  const [verifyError, setVerifyError] = useState<string | null>(null)
  const [verifying, setVerifying] = useState(false)

  const [disableCode, setDisableCode] = useState('')
  const [disableError, setDisableError] = useState<string | null>(null)
  const [disabling, setDisabling] = useState(false)

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

  const startSetup = async () => {
    setSettingUp(true)
    try {
      const result = await api.post<MfaSetupResult>('/api/me/mfa/setup')
      setSetup(result)
      setVerifyCode('')
      setVerifyError(null)
      toast.success('已生成 MFA 绑定信息')
    } catch (err) {
      toast.error(errorMessage(err))
    } finally {
      setSettingUp(false)
    }
  }

  const verifyMfa = async () => {
    setVerifyError(null)
    if (!/^\d{6}$/.test(verifyCode.trim())) {
      setVerifyError('请输入验证器中的 6 位数字验证码')
      return
    }
    setVerifying(true)
    try {
      await api.post<{ enabled: boolean }>('/api/me/mfa/verify', { code: verifyCode.trim() })
      toast.success('两步验证已启用')
      setSetup(null)
      setVerifyCode('')
      status.reload()
    } catch (err) {
      setVerifyError(errorMessage(err))
    } finally {
      setVerifying(false)
    }
  }

  const disableMfa = async () => {
    setDisableError(null)
    if (!disableCode.trim()) {
      setDisableError('请输入验证器验证码或恢复码')
      return
    }
    setDisabling(true)
    try {
      await api.post<unknown>('/api/me/mfa/disable', { code: disableCode.trim() })
      toast.success('两步验证已禁用')
      setDisableCode('')
      status.reload()
    } catch (err) {
      setDisableError(errorMessage(err))
    } finally {
      setDisabling(false)
    }
  }

  return (
    <div className="page">
      <header className="page-head">
        <div className="page-head-main">
          <div>
            <h2>两步验证（MFA）</h2>
            <p>使用 TOTP 验证器保护账号；绑定后登录需要输入 6 位验证码或一次性恢复码。</p>
          </div>
        </div>
        <div className="page-head-actions">
          <button type="button" className="btn btn-ghost btn-sm" onClick={status.reload}>
            <Icon name="refresh" size={15} />
            刷新
          </button>
        </div>
      </header>

      <AsyncBlock loading={status.loading} error={status.error} onRetry={status.reload}>
        {status.data?.enabled ? (
          <div className="detail-grid">
            <div className="detail-main">
              <SectionCard title="当前状态" description="来自 /api/me/mfa">
                <div className="mfa-status">
                  <Badge tone="green" dot>
                    已启用
                  </Badge>
                  <span className="muted">启用时间：{formatDateTime(status.data.confirmedAt)}</span>
                </div>
                <p className="field-hint">
                  登录该账号时会要求输入验证器验证码；恢复码仅在绑定（或重置绑定）时展示一次。
                </p>
              </SectionCard>
            </div>
            <div className="detail-side">
              <SectionCard title="停用两步验证" description="需要当前验证器验证码或恢复码">
                <Field label="验证码 / 恢复码" required>
                  <input
                    className={`input ${disableError ? 'has-error' : ''}`}
                    value={disableCode}
                    placeholder="6 位验证码或恢复码"
                    onChange={(event) => {
                      setDisableCode(event.target.value)
                      setDisableError(null)
                    }}
                  />
                </Field>
                {disableError ? <p className="field-error">{disableError}</p> : null}
                <button
                  type="button"
                  className="btn btn-danger-ghost btn-block"
                  onClick={disableMfa}
                  disabled={disabling}
                >
                  {disabling ? <span className="spinner" /> : null}
                  {disabling ? '停用中…' : '停用两步验证'}
                </button>
              </SectionCard>
            </div>
          </div>
        ) : (
          <div className="detail-grid">
            <div className="detail-main">
              {!setup ? (
                <SectionCard title="未启用两步验证" description="绑定 TOTP 验证器（Google Authenticator / 1Password 等）">
                  <p className="field-hint">
                    点击开始绑定后将生成 Base32 Secret 与 otpauth 链接；在验证器 App 中添加后输入 6 位验证码完成启用。
                  </p>
                  <button type="button" className="btn btn-primary" onClick={startSetup} disabled={settingUp}>
                    {settingUp ? <span className="spinner spinner-light" /> : <Icon name="shield" size={15} />}
                    {settingUp ? '生成中…' : '开始绑定'}
                  </button>
                </SectionCard>
              ) : (
                <SectionCard title="绑定验证器" description="密钥与恢复码仅在本页展示；请立即妥善保存">
                  <div className="mfa-secret">
                    <span className="info-label">Secret（Base32）</span>
                    <code>{setup.secret}</code>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(setup.secret)}>
                      <Icon name="copy" size={14} />
                      复制
                    </button>
                  </div>
                  <div className="mfa-otpauth">
                    <span className="info-label">otpauth URL</span>
                    <code className="mfa-otpauth-code">{setup.otpauthUrl}</code>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => copy(setup.otpauthUrl)}>
                      <Icon name="copy" size={14} />
                      复制
                    </button>
                  </div>

                  <h4 className="block-title">恢复码（仅显示一次）</h4>
                  <p className="field-hint">共 8 个，每个仅可使用一次；请离线保存，丢失后无法找回。</p>
                  <div className="recovery-grid">
                    {(setup.recoveryCodes ?? []).map((recoveryCode) => (
                      <button
                        key={recoveryCode}
                        type="button"
                        className="recovery-code"
                        onClick={() => copy(recoveryCode)}
                        title="点击复制"
                      >
                        {recoveryCode}
                      </button>
                    ))}
                  </div>
                  <button
                    type="button"
                    className="btn btn-ghost btn-sm"
                    onClick={() => copy((setup.recoveryCodes ?? []).join('\n'))}
                  >
                    <Icon name="copy" size={14} />
                    复制全部恢复码
                  </button>

                  <h4 className="block-title">验证并启用</h4>
                  <Field label="验证器 6 位验证码" required>
                    <input
                      className={`input ${verifyError ? 'has-error' : ''}`}
                      value={verifyCode}
                      inputMode="numeric"
                      maxLength={6}
                      placeholder="000000"
                      onChange={(event) => {
                        setVerifyCode(event.target.value)
                        setVerifyError(null)
                      }}
                    />
                  </Field>
                  {verifyError ? <p className="field-error">{verifyError}</p> : null}
                  <button type="button" className="btn btn-primary" onClick={verifyMfa} disabled={verifying}>
                    {verifying ? <span className="spinner spinner-light" /> : null}
                    {verifying ? '验证中…' : '验证并启用'}
                  </button>
                </SectionCard>
              )}
            </div>
            <div className="detail-side">
              <SectionCard title="说明">
                <ul className="hot-reload-list">
                  <li>MFA 仅适用于租户账号；平台账号由平台统一策略管理。</li>
                  <li>TOTP 验证码 30 秒刷新，验证时允许 ±1 个窗口。</li>
                  <li>登录时验证失败次数过多会导致临时锁定，请稍后再试。</li>
                  <li>恢复码与 secret 只显示一次，请务必妥善保存。</li>
                </ul>
              </SectionCard>
            </div>
          </div>
        )}
      </AsyncBlock>
    </div>
  )
}
