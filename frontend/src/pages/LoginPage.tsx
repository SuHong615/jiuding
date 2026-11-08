import { useEffect, useState } from 'react'
import { Link, Navigate, useLocation, useNavigate } from 'react-router-dom'
import { ApiError, api, errorMessage } from '../api/client'
import type { AuthTenantOption, LoginResult, OidcProvider } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Field } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'
import { useAsync } from '../hooks/useAsync'

type LoginMode = 'platform' | 'tenant'
type LoginStep = 'credentials' | 'mfa' | 'tenant'

export function LoginPage() {
  const { token, applyLogin } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()

  const providers = useAsync(() => api.get<OidcProvider[]>('/api/auth/oidc/providers'), [])
  const ssoProviders = providers.data ?? []

  const [mode, setMode] = useState<LoginMode>('platform')
  const [step, setStep] = useState<LoginStep>('credentials')
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
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

  // 多租户选择
  const [tenantOptions, setTenantOptions] = useState<AuthTenantOption[]>([])
  const [pendingCredentials, setPendingCredentials] = useState<{ username: string; password: string } | null>(null)

  useEffect(() => {
    document.title = '登录 · 九鼎中枢'
  }, [])

  if (token) {
    const from = (location.state as { from?: string } | null)?.from ?? '/'
    return <Navigate to={from} replace />
  }

  const goHome = () => {
    const from = (location.state as { from?: string } | null)?.from ?? '/'
    navigate(from, { replace: true })
  }

  const handleLoginResult = (result: LoginResult) => {
    if (result.mfaRequired && result.challengeToken) {
      setChallengeToken(result.challengeToken)
      setMfaUsername(result.username || username)
      setMfaCode('')
      setMfaUseRecovery(false)
      setMfaError(null)
      setStep('mfa')
      return
    }
    if (result.tenantSelectionRequired) {
      setTenantOptions(result.tenants ?? [])
      setPendingCredentials({ username: username.trim(), password })
      setStep('tenant')
      return
    }
    applyLogin(result)
    toast.success(`欢迎回来，${result.principal.username}`)
    goHome()
  }

  const performLogin = async (
    credentials: { username: string; password: string },
    explicitTenantCode?: string,
  ) => {
    setError(null)
    setSubmitting(true)
    try {
      const payload: { username: string; password: string; tenantCode?: string } = { ...credentials }
      if (explicitTenantCode?.trim()) payload.tenantCode = explicitTenantCode.trim()
      const result = await api.post<LoginResult>('/api/auth/login', payload)
      handleLoginResult(result)
    } catch (err) {
      if (step === 'tenant') {
        setError(err instanceof ApiError && err.status === 401 ? '租户选择失败：凭据或租户不匹配' : errorMessage(err))
      } else if (err instanceof ApiError && err.status === 401) {
        setError('登录失败：用户名、密码或租户代码不正确')
      } else {
        setError(errorMessage(err))
      }
    } finally {
      setSubmitting(false)
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
    if (!validate()) return
    const payload: { username: string; password: string; tenantCode?: string } = {
      username: username.trim(),
      password,
    }
    if (mode === 'tenant' || tenantCode.trim()) payload.tenantCode = tenantCode.trim()
    setSubmitting(true)
    setError(null)
    try {
      const result = await api.post<LoginResult>('/api/auth/login', payload)
      handleLoginResult(result)
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

  const selectTenant = async (tenant: AuthTenantOption) => {
    if (!pendingCredentials) return
    await performLogin(pendingCredentials, tenant.tenantCode)
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
      goHome()
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
    setTenantOptions([])
    setPendingCredentials(null)
    setError(null)
  }

  const startSso = (provider: OidcProvider) => {
    window.location.assign(provider.loginUrl || `/api/auth/oidc/authorize?redirect=true&provider=${encodeURIComponent(provider.code)}`)
  }

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-mark">TC</span>
          <div>
            <strong>九鼎中枢</strong>
            <span>Tenancy Console</span>
          </div>
        </div>

        {step === 'tenant' ? (
          <>
            <header className="login-head">
              <h1>选择租户</h1>
              <p>账号 <strong>{pendingCredentials?.username}</strong> 关联多个租户，请选择要登录的租户。</p>
            </header>
            <div className="tenant-select-list">
              {tenantOptions.length ? (
                tenantOptions.map((tenant) => (
                  <button
                    key={tenant.tenantCode}
                    type="button"
                    className="tenant-select-item"
                    disabled={submitting}
                    onClick={() => selectTenant(tenant)}
                  >
                    <span>
                      <strong>{tenant.name || tenant.tenantCode}</strong>
                      <em className="mono">{tenant.tenantCode}</em>
                    </span>
                    <Icon name="arrowRight" size={15} />
                  </button>
                ))
              ) : (
                <p className="field-hint">未返回可登录租户，请返回重试。</p>
              )}
            </div>
            {error ? (
              <div className="login-error" role="alert">
                <Icon name="alert" size={16} />
                <span>{error}</span>
              </div>
            ) : null}
            <div className="login-mfa-actions">
              <button type="button" className="link-btn" onClick={backToCredentials}>
                返回上一步
              </button>
            </div>
          </>
        ) : step === 'mfa' ? (
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
              <p>使用平台账号或租户成员账号登录</p>
            </header>

            <div className="login-mode-switch" role="tablist" aria-label="登录方式">
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'platform'}
                className={mode === 'platform' ? 'active' : ''}
                onClick={() => {
                  setMode('platform')
                  setError(null)
                  setFieldErrors({})
                }}
              >
                平台账号
              </button>
              <button
                type="button"
                role="tab"
                aria-selected={mode === 'tenant'}
                className={mode === 'tenant' ? 'active' : ''}
                onClick={() => {
                  setMode('tenant')
                  setError(null)
                  setFieldErrors({})
                }}
              >
                租户成员
              </button>
            </div>
            <p className="login-mode-hint">
              {mode === 'platform'
                ? '平台账号可直接登录；填写租户代码则同时指定目标租户。'
                : '租户成员需填写租户管理员提供的租户代码，例如 acme。'}
            </p>

            <form onSubmit={handleSubmit} noValidate>
              <Field label="用户名" required error={fieldErrors.username}>
                <input
                  className={`input ${fieldErrors.username ? 'has-error' : ''}`}
                  value={username}
                  autoComplete="username"
                  placeholder={mode === 'tenant' ? '租户账号用户名' : '请输入用户名'}
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
                hint={mode === 'tenant' ? '租户代码必填，例如 acme' : '留空时若账号关联多个租户，将要求选择租户'}
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

            {ssoProviders.length ? (
              <>
                <div className="login-divider">
                  <span>企业 SSO</span>
                </div>
                <div className="sso-provider-list">
                  {ssoProviders.map((provider) => (
                    <button
                      key={provider.code}
                      type="button"
                      className="btn btn-ghost btn-block"
                      onClick={() => startSso(provider)}
                    >
                      <Icon name="shield" size={15} />
                      使用 {provider.name} 登录
                    </button>
                  ))}
                </div>
              </>
            ) : null}

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
