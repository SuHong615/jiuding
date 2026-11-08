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
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError, api, errorMessage } from '../api/client'
import type { LoginResult, OidcStatus } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { useAsync } from '../hooks/useAsync'

interface DemoAccount {
  username: string
  password: string
  label: string
}

const DEMO_ACCOUNTS: DemoAccount[] = [
  { username: 'admin', password: 'Admin@123', label: '平台管理员' },
  { username: 'approver', password: 'Approver@123', label: '审批人' },
  { username: 'operator', password: 'Operator@123', label: '租户管理员' },
  { username: 'auditor', password: 'Auditor@123', label: '审计员' },
]

type LoginMode = 'platform' | 'tenant'
type LoginStep = 'credentials' | 'mfa'

export function LoginPage() {
  const { token, applyLogin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()

  const oidc = useAsync(() => api.get<OidcStatus>('/api/auth/oidc/status'), [])

  const [mode, setMode] = useState<LoginMode>('platform')
  const [step, setStep] = useState<LoginStep>('credentials')
  const [username, setUsername] = useState('admin')
  const [password, setPassword] = useState('Admin@123')
  const [tenantCode, setTenantCode] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [fieldErrors, setFieldErrors] = useState<{
    username?: string
    password?: string
    tenantCode?: string
  }>({})

  // MFA 第二步
  const [challengeToken, setChallengeToken] = useState('')
  const [mfaUsername, setMfaUsername] = useState('')
  const [mfaCode, setMfaCode] = useState('')
  const [mfaUseRecovery, setMfaUseRecovery] = useState(false)
  const [mfaSubmitting, setMfaSubmitting] = useState(false)
  const [mfaError, setMfaError] = useState<string | null>(null)

  useEffect(() => {
    document.title = '登录 · 九鼎中枢'
  }, [])

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/'
    return <Navigate to={from} replace />
  }

  const switchMode = (next: LoginMode) => {
    setMode(next)
    setError(null)
    setFieldErrors({})
    if (next === 'tenant') {
      setUsername('')
      setPassword('')
    } else {
      setUsername('admin')
      setPassword('Admin@123')
      setTenantCode('')
    }
  }

  const validate = (): boolean => {
    const next: { username?: string; password?: string; tenantCode?: string } = {}
    if (!username.trim()) next.username = '请输入用户名'
    if (!password) next.password = '请输入密码'
    if (mode === 'tenant' && !tenantCode.trim()) next.tenantCode = '租户成员登录需填写租户代码'
    setFieldErrors(next)
    return Object.keys(next).length === 0
  }

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setError(null)
    if (!validate()) return
    setSubmitting(true)
    try {
      const payload: { username: string; password: string; tenantCode?: string } = {
        username: username.trim(),
        password,
      }
      if (mode === 'tenant' || tenantCode.trim()) payload.tenantCode = tenantCode.trim()
      const result = await api.post<LoginResult>('/api/auth/login', payload)
      if (result.mfaRequired && result.challengeToken) {
        setChallengeToken(result.challengeToken)
        setMfaUsername(result.username || username)
        setMfaCode('')
        setMfaUseRecovery(false)
        setMfaError(null)
        setStep('mfa')
        return
      }
      applyLogin(result)
      toast.success(`欢迎回来，${result.principal.username}`)
      const from = (location.state as { from?: string } | null)?.from ?? '/'
      navigate(from, { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setError('登录失败：用户名、密码或租户代码不正确')
      } else {
        setError(errorMessage(err))
      }
    } finally {
      setSubmitting(false)
    }
  }

  const submitMfa = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault()
    setMfaError(null)
    if (!mfaCode.trim()) {
      setMfaError(mfaUseRecovery ? '请输入恢复码' : '请输入 6 位验证码')
      return
    }
    if (!mfaUseRecovery && !/^\d{6}$/.test(mfaCode.trim())) {
      setMfaError('验证码为 6 位数字')
      return
    }
    setMfaSubmitting(true)
    try {
      const result = await api.post<LoginResult>('/api/auth/login/mfa', {
        challengeToken,
        code: mfaCode.trim(),
      })
      applyLogin(result)
      toast.success(`欢迎回来，${result.principal.username}`)
      const from = (location.state as { from?: string } | null)?.from ?? '/'
      navigate(from, { replace: true })
    } catch (err) {
      if (err instanceof ApiError && err.status === 401) {
        setMfaError('验证码或恢复码错误，或验证已过期，请重试')
      } else {
        setMfaError(errorMessage(err))
      }
    } finally {
      setMfaSubmitting(false)
    }
  }

  const backToCredentials = () => {
    setStep('credentials')
    setChallengeToken('')
    setMfaCode('')
    setMfaError(null)
    setMfaUseRecovery(false)
  }

  const startSso = () => {
    window.location.assign('/api/auth/oidc/authorize?redirect=true')
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-mark">TC</span>
          <div>
            <strong>九鼎中枢</strong>
            <span>JIUDING HUB</span>
          </div>
        </div>

        {step === 'mfa' ? (
          <>
            <header className="login-head">
              <h1>两步验证</h1>
              <p>
                账号 <strong>{mfaUsername}</strong> 已启用 MFA，请输入验证器 6 位验证码，或使用恢复码。
              </p>
            </header>
            <form onSubmit={submitMfa} noValidate>
              <Field
                label={mfaUseRecovery ? '恢复码' : '验证码'}
                required
                hint={mfaUseRecovery ? '恢复码仅可使用一次' : '来自验证器 App（TOTP，30 秒刷新）'}
              >
                <input
                  className={`input ${mfaUseRecovery ? 'mono' : ''} ${mfaError ? 'has-error' : ''}`}
                  value={mfaCode}
                  inputMode={mfaUseRecovery ? 'text' : 'numeric'}
                  maxLength={mfaUseRecovery ? 64 : 6}
                  autoFocus
                  placeholder={mfaUseRecovery ? '输入一次性恢复码' : '000000'}
                  onChange={(event) => {
                    setMfaCode(event.target.value)
                    setMfaError(null)
                  }}
                />
              </Field>

              {mfaError ? (
                <div className="login-error" role="alert">
                  <Icon name="alert" size={16} />
                  <span>{mfaError}</span>
                </div>
              ) : null}

              <button type="submit" className="btn btn-primary btn-block login-submit" disabled={mfaSubmitting}>
                {mfaSubmitting ? <span className="spinner spinner-light" /> : null}
                {mfaSubmitting ? '验证中…' : '验证并登录'}
              </button>

              <div className="login-mfa-actions">
                <button
                  type="button"
                  className="link-btn"
                  onClick={() => {
                    setMfaUseRecovery((value) => !value)
                    setMfaCode('')
                    setMfaError(null)
                  }}
                >
                  {mfaUseRecovery ? '使用验证器验证码' : '使用恢复码登录'}
                </button>
                <button type="button" className="link-btn" onClick={backToCredentials}>
                  返回上一步
                </button>
              </div>
            </form>
          </>
        ) : (
          <>
            <header className="login-head">
              <h1>登录运营控制台</h1>
              <p>平台账号或租户成员账号均可登录</p>
            </header>

            <div className="login-mode-switch" role="tablist" aria-label="登录方式">
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'platform'}
                className={mode === 'platform' ? 'active' : ''}
                onClick={() => switchMode('platform')}
              >
                平台账号
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'tenant'}
                className={mode === 'tenant' ? 'active' : ''}
                onClick={() => switchMode('tenant')}
              >
                租户成员
              </button>
            </div>
            <p className="login-mode-hint">
              {mode === 'platform'
                ? '平台账号（PLATFORM_ADMIN / APPROVER / AUDITOR）登录无需租户代码。'
                : '租户成员需填写租户管理员提供的租户代码，例如 acme。'}
            </p>

            <form onSubmit={handleSubmit} noValidate>
              <Field label="用户名" required error={fieldErrors.username}>
                <input
                  className={`input ${fieldErrors.username ? 'has-error' : ''}`}
                  value={username}
                  autoComplete="username"
                  placeholder={mode === 'tenant' ? '租户账号用户名' : 'admin'}
                  onChange={(event) => setUsername(event.target.value)}
                />
              </Field>
              <Field label="密码" required error={fieldErrors.password}>
                <input
                  className={`input ${fieldErrors.password ? 'has-error' : ''}`}
                  type="password"
                  value={password}
                  autoComplete="current-password"
                  placeholder="请输入密码"
                  onChange={(event) => setPassword(event.target.value)}
                />
              </Field>
              <Field
                label="租户代码（可选）"
                error={fieldErrors.tenantCode}
                hint={mode === 'tenant' ? '租户代码必填，例如 acme' : '平台账号可留空；填写后按租户成员登录'}
              >
                <input
                  className={`input mono ${fieldErrors.tenantCode ? 'has-error' : ''}`}
                  value={tenantCode}
                  autoComplete="organization"
                  placeholder="如 acme"
                  onChange={(event) => setTenantCode(event.target.value)}
                />
              </Field>

              {error ? (
                <div className="login-error" role="alert">
                  <Icon name="alert" size={16} />
                  <span>{error}</span>
                </div>
              ) : null}

              <button type="submit" className="btn btn-primary btn-block login-submit" disabled={submitting}>
                {submitting ? <span className="spinner spinner-light" /> : null}
                {submitting ? '登录中…' : '登录'}
              </button>
            </form>

            {oidc.data?.enabled ? (
              <>
                <div className="login-divider">
                  <span>或</span>
                </div>
                <button type="button" className="btn btn-ghost btn-block" onClick={startSso}>
                  <Icon name="shield" size={15} />
                  使用{oidc.data.providerName || '企业 SSO'}登录
                </button>
              </>
            ) : null}

            {mode === 'platform' ? (
              <div className="demo-accounts">
                <span className="demo-title">预置平台账号（点击填充）</span>
                <div className="demo-list">
                  {DEMO_ACCOUNTS.map((account) => (
                    <button
                      key={account.username}
                      type="button"
                      className={`demo-account ${username === account.username ? 'selected' : ''}`}
                      onClick={() => {
                        setUsername(account.username)
                        setPassword(account.password)
                        setError(null)
                        setFieldErrors({})
                      }}
                    >
                      <strong>{account.label}</strong>
                      <span>{account.username}</span>
                    </button>
                  ))}
                </div>
              </div>
            ) : (
              <p className="tenant-login-note">
                租户成员账号由租户管理员在「租户详情 → 账号」中创建；首次登录后系统会提示尽快修改初始密码。
              </p>
            )}

            <p className="login-hint">接口异常时请检查网关（http://localhost:8080）与各微服务是否已启动。</p>

            <p className="login-onboarding">
              还没有租户账号？
              <Link to="/onboarding">申请租户入驻 →</Link>
            </p>
          </>
        )}
      </div>
    </div>
  )
}
