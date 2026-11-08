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
import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from 'react'
import { api, clearAuthStorage, getStoredPrincipal, getToken, PRINCIPAL_KEY, saveAuth, TOKEN_KEY } from '../api/client'
import type { LoginRequest, LoginResult, MePermissions, Principal } from '../api/types'

interface AuthContextValue {
  token: string | null
  principal: Principal | null
  mePermissions: string[] | null
  login: (username: string, password: string, tenantCode?: string) => Promise<Principal>
  applyLogin: (result: LoginResult) => void
  loginWithToken: (token: string) => Promise<Principal>
  switchTenant: (tenantCode: string) => Promise<Principal>
  logout: () => void
  hasRole: (...roles: string[]) => boolean
  hasPermission: (permission: string) => boolean
  isPlatformAdmin: boolean
  updatePrincipal: (patch: Partial<Principal>) => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [token, setToken] = useState<string | null>(() => getToken())
  const [principal, setPrincipal] = useState<Principal | null>(() => getStoredPrincipal())
  const [mePermissions, setMePermissions] = useState<string[] | null>(null)

  // v5：/api/me/permissions 驱动导航与操作收敛；接口不可用时保持 null，回退角色判断
  useEffect(() => {
    if (!token) {
      setMePermissions(null)
      return
    }
    let active = true
    api
      .get<MePermissions>('/api/me/permissions')
      .then((result) => {
        if (!active) return
        const permissions = Array.isArray(result?.permissions) ? result.permissions : []
        setMePermissions(permissions)
      })
      .catch(() => {
        if (active) setMePermissions(null)
      })
    return () => {
      active = false
    }
  }, [token])

  // 统一 api client 在 401 时广播事件，这里同步清理内存态，路由守卫随即跳转登录页
  useEffect(() => {
    const handler = () => {
      clearAuthStorage()
      setToken(null)
      setPrincipal(null)
    }
    window.addEventListener('tenancy:unauthorized', handler)
    return () => window.removeEventListener('tenancy:unauthorized', handler)
  }, [])

  const login = useCallback(async (username: string, password: string, tenantCode?: string) => {
    const payload: LoginRequest = { username: username.trim(), password }
    if (tenantCode?.trim()) payload.tenantCode = tenantCode.trim()
    const result = await api.post<LoginResult>('/api/auth/login', payload)
    saveAuth(result)
    setToken(result.token)
    setPrincipal(result.principal)
    return result.principal
  }, [])

  // 登录页/MFA/SSO 成功后统一写入会话
  const applyLogin = useCallback((result: LoginResult) => {
    saveAuth(result)
    setToken(result.token)
    setPrincipal(result.principal)
  }, [])

  // SSO 回调：先写入 token，再通过 /api/auth/me 拉取 principal
  const loginWithToken = useCallback(async (token: string) => {    try {
      localStorage.setItem(TOKEN_KEY, token)
    } catch {
      /* localStorage 不可用时忽略 */
    }
    try {
      const principal = await api.get<Principal>('/api/auth/me')
      const result: LoginResult = { token, tokenType: 'Bearer', expiresIn: 0, principal }
      saveAuth(result)
      setToken(token)
      setPrincipal(principal)
      return principal
    } catch (err) {
      clearAuthStorage()
      setToken(null)
      setPrincipal(null)
      throw err
    }
  }, [])

  // v10：顶栏租户切换；服务端用当前账号凭据换发目标租户 JWT
  const switchTenant = useCallback(
    async (tenantCode: string) => {
      const result = await api.post<LoginResult>('/api/auth/switch-tenant', { tenantCode })
      applyLogin(result)
      return result.principal
    },
    [applyLogin],
  )

  const logout = useCallback(() => {
    clearAuthStorage()
    setToken(null)
    setPrincipal(null)
  }, [])

  // 修改密码等场景局部更新 principal（如 mustChangePassword=false），并同步 localStorage
  const updatePrincipal = useCallback((patch: Partial<Principal>) => {
    setPrincipal((prev) => {
      if (!prev) return prev
      const next = { ...prev, ...patch }
      try {
        localStorage.setItem(PRINCIPAL_KEY, JSON.stringify(next))
      } catch {
        /* localStorage 不可用时忽略 */
      }
      return next
    })
  }, [])

  const hasRole = useCallback(
    (...roles: string[]) => {
      if (!principal) return false
      return roles.some((role) => principal.roles?.includes(role))
    },
    [principal],
  )

  const hasPermission = useCallback(
    (permission: string) => {
      // 无 /api/me/permissions 依据时返回 true，由既有角色判断兜底
      if (!mePermissions) return true
      if (mePermissions.includes('*')) return true
      return mePermissions.includes(permission)
    },
    [mePermissions],
  )

  const value = useMemo<AuthContextValue>(
    () => ({
      token,
      principal,
      mePermissions,
      login,
      applyLogin,
      loginWithToken,
      switchTenant,
      logout,
      hasRole,
      hasPermission,
      isPlatformAdmin: Boolean(principal?.roles?.includes('PLATFORM_ADMIN')),
      updatePrincipal,
    }),
    [token, principal, mePermissions, login, applyLogin, loginWithToken, switchTenant, logout, hasRole, hasPermission, updatePrincipal],
  )

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth(): AuthContextValue {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth 必须在 AuthProvider 内使用')
  return ctx
}
