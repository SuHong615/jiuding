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
import { Link, useNavigate } from 'react-router-dom'
import { errorMessage } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { ErrorBlock, LoadingBlock } from '../components/PageState'
import { SectionCard } from '../components/Forms'
import { Icon } from '../components/Icon'
import { useToast } from '../components/Toast'

export function SsoCallbackPage() {
  const { loginWithToken } = useAuth()
  const navigate = useNavigate()
  const toast = useToast()
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let active = true
    const rawHash = window.location.hash.replace(/^#/, '')
    const params = new URLSearchParams(rawHash)
    const ssoError = params.get('error')
    // 解析后立即清除 URL hash，避免 token 留在地址栏/历史记录
    window.history.replaceState(null, '', window.location.pathname)

    if (ssoError) {
      setError(`企业 SSO 登录失败：${decodeURIComponent(ssoError)}`)
      setLoading(false)
      return
    }
    const token = params.get('token')
    if (!token) {
      setError('回调地址缺少登录凭据（token），请重新发起 SSO 登录')
      setLoading(false)
      return
    }

    loginWithToken(token)
      .then((principal) => {
        if (!active) return
        toast.success(`欢迎回来，${principal.username}`)
        setLoading(false)
        navigate('/', { replace: true })
      })
      .catch((err) => {
        if (!active) return
        setError(errorMessage(err))
        setLoading(false)
      })

    return () => {
      active = false
    }
  }, [loginWithToken, navigate, toast])

  return (
    <div className="login-page">
      <div className="login-card">
        <div className="login-brand">
          <span className="brand-mark">TC</span>
          <div>
            <strong>多租户中心</strong>
            <span>Tenancy Console</span>
          </div>
        </div>

        <header className="login-head">
          <h1>企业 SSO 登录</h1>
          <p>正在处理企业身份提供方的回调…</p>
        </header>

        {loading ? (
          <LoadingBlock text="正在验证 SSO 登录信息…" />
        ) : (
          <SectionCard>
            <ErrorBlock message={error ?? 'SSO 登录失败，请重试'} />
            <div className="sso-callback-actions">
              <Link to="/login" className="btn btn-primary btn-sm">
                <Icon name="arrowLeft" size={14} />
                返回登录
              </Link>
              <button type="button" className="btn btn-ghost btn-sm" onClick={() => window.location.reload()}>
                重试
              </button>
            </div>
          </SectionCard>
        )}
      </div>
    </div>
  )
}
