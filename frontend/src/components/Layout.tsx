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
import { Link, NavLink, Outlet, useLocation, useNavigate } from 'react-router-dom'
import { api, errorMessage } from '../api/client'
import type { AuthTenantOption } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { Icon, type IconName } from '../components/Icon'
import { useToast } from '../components/Toast'
import { useAsync } from '../hooks/useAsync'
import { roleLabel } from '../utils/labels'

interface NavItem {
  path: string
  label: string
  icon: IconName
  group: '运营' | '能力' | '平台'
  keywords?: string[]
}

const NAV_ITEMS: NavItem[] = [
  { path: '/', label: '运营总览', icon: 'dashboard', group: '运营' },
  { path: '/tenants', label: '租户管理', icon: 'tenants', group: '运营', keywords: ['TENANT', 'ORG', 'ACCOUNT'] },
  { path: '/collection', label: '租户采集', icon: 'collection', group: '运营', keywords: ['TENANT', 'COLLECT'] },
  { path: '/approvals', label: '审批中心', icon: 'approvals', group: '运营', keywords: ['APPROVAL'] },
  { path: '/mcp', label: 'MCP 控制台', icon: 'mcp', group: '能力', keywords: ['MCP'] },
  { path: '/workflows', label: '工作流', icon: 'collection', group: '能力', keywords: ['WORKFLOW', 'TENANT'] },
  { path: '/skills', label: '技能中心', icon: 'skills', group: '能力', keywords: ['SKILL'] },
  { path: '/agents', label: 'Agent 运行', icon: 'agents', group: '能力', keywords: ['AGENT'] },
  { path: '/permissions', label: '权限管理', icon: 'shield', group: '平台', keywords: ['RBAC', 'ROLE', 'PERMISSION'] },
  { path: '/platform/users', label: '平台用户', icon: 'user', group: '平台', keywords: ['PLATFORM_USER', 'USER'] },
  { path: '/platform/sso', label: 'SSO 配置', icon: 'key', group: '平台', keywords: ['SSO', 'OIDC'] },
  { path: '/scim', label: 'SCIM 配置', icon: 'key', group: '平台', keywords: ['SCIM'] },
  { path: '/mcp/keys', label: 'MCP 凭据', icon: 'key', group: '平台', keywords: ['MCP', 'KEY'] },
  { path: '/mcp/audits', label: 'MCP 调用审计', icon: 'file', group: '平台', keywords: ['MCP', 'AUDIT'] },
]

export function Layout() {
  const { token, principal, logout, mePermissions, isPlatformAdmin, switchTenant } = useAuth()
  const navigate = useNavigate()
  const location = useLocation()
  const toast = useToast()

  const tenants = useAsync(async () => {
    if (!token) return []
    const result = await api.get<AuthTenantOption[] | { tenants?: AuthTenantOption[] }>('/api/auth/tenants')
    return Array.isArray(result) ? result : (result?.tenants ?? [])
  }, [token])
  const tenantOptions = tenants.data ?? []
  const [switchingTenant, setSwitchingTenant] = useState(false)
  const currentTenantCode =
    tenantOptions.find((tenant) => tenant.tenantId === principal?.tenantId)?.tenantCode ?? ''

  const handleSwitchTenant = async (tenantCode: string) => {
    if (!tenantCode) return
    setSwitchingTenant(true)
    try {
      await switchTenant(tenantCode)
      toast.success('租户已切换')
      window.location.assign('/')
    } catch (err) {
      toast.error(errorMessage(err))
      setSwitchingTenant(false)
    }
  }

  const canSeeNav = (item: NavItem): boolean => {
    if (item.path === '/permissions') {
      if (isPlatformAdmin) return true
      if (!mePermissions) return false
      if (mePermissions.includes('*')) return true
      return mePermissions.some((code) => /RBAC|ROLE|PERMISSION/i.test(code))
    }
    if (item.path === '/scim') {
      if (isPlatformAdmin) return true
      if (!mePermissions) return false
      if (mePermissions.includes('*')) return true
      return mePermissions.some((code) => /SCIM/i.test(code))
    }
    if (item.path.startsWith('/platform/') || item.path === '/mcp/keys' || item.path === '/mcp/audits') {
      if (isPlatformAdmin) return true
      if (!mePermissions) return false
      if (mePermissions.includes('*')) return true
      const keywords = item.keywords ?? []
      return keywords.some((keyword) => mePermissions.some((code) => code.toUpperCase().includes(keyword)))
    }
    // 无 /api/me/permissions 依据时回退角色判断：默认展示（后端仍强制校验）
    if (!mePermissions || !mePermissions.length) return true
    if (mePermissions.includes('*')) return true
    const keywords = item.keywords ?? []
    if (!keywords.length) return true
    return keywords.some((keyword) =>
      mePermissions.some((code) => code.toUpperCase().includes(keyword)),
    )
  }
  const visibleItems = NAV_ITEMS.filter(canSeeNav)

  const active =
    visibleItems.filter((item) =>
      item.path === '/' ? location.pathname === '/' : location.pathname.startsWith(item.path),
    ).sort((a, b) => b.path.length - a.path.length)[0] ?? visibleItems[0] ?? NAV_ITEMS[0]

  useEffect(() => {
    document.title = `${active.label} · 九鼎中枢`
  }, [active.label])

  const handleLogout = () => {
    logout()
    navigate('/login', { replace: true })
  }

  const roleText = (principal?.roles ?? []).map((role) => roleLabel(role)).join(' / ') || '—'

  const renderItem = (item: NavItem) => (
    <NavLink
      key={item.path}
      to={item.path}
      end={item.path === '/'}
      className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
    >
      <span className="nav-icon">
        <Icon name={item.icon} size={17} />
      </span>
      <span className="nav-label">{item.label}</span>
    </NavLink>
  )

  return (
    <div className="app-shell">
      <aside className="sidebar">
        <div className="brand">
          <span className="brand-mark">TC</span>
          <div className="brand-text">
            <strong>九鼎中枢</strong>
            <span>JIUDING HUB</span>
          </div>
          <span className="env-badge">DEV</span>
        </div>
        <nav className="nav">
          {(['运营', '能力', '平台'] as const).map((group) => {
            const items = visibleItems.filter((item) => item.group === group)
            if (!items.length) return null
            return (
              <div key={group} className="nav-group">
                <span className="nav-group-title">{group}</span>
                {items.map(renderItem)}
              </div>
            )
          })}
        </nav>
        <div className="sidebar-foot">
          <span className="env-chip">
            <span className="dot dot-up" />
            Gateway :8080
          </span>
          <span className="sidebar-version">v1.1.0</span>
        </div>
      </aside>

      <div className="main">
        <header className="topbar">
          <nav className="breadcrumb" aria-label="面包屑">
            <span>九鼎中枢</span>
            <span className="sep">/</span>
            <strong>{active.label}</strong>
          </nav>
          <div className="topbar-actions">
            {tenantOptions.length > 1 ? (
              <select
                className="select select-compact tenant-switcher"
                value={currentTenantCode}
                disabled={switchingTenant}
                title="切换当前租户"
                onChange={(event) => handleSwitchTenant(event.target.value)}
              >
                <option value="">切换租户</option>
                {tenantOptions.map((tenant) => (
                  <option key={tenant.tenantCode} value={tenant.tenantCode}>
                    {tenant.name || tenant.tenantCode}（{tenant.tenantCode}）
                  </option>
                ))}
              </select>
            ) : null}
            <span className="context-chip" title={`当前角色：${roleText}`}>
              <Icon name="user" size={13} />
              {roleText}
            </span>
            <span className="context-chip" title={principal?.tenantId ? `当前租户：${principal.tenantId}` : '平台级视角'}>
              <Icon name="tenants" size={13} />
              {principal?.tenantId ? `租户 ${principal.tenantId}` : '平台级视角'}
            </span>
            {principal?.orgUnitId ? (
              <span className="context-chip" title={`当前组织：${principal.orgUnitId}`}>
                <Icon name="agents" size={13} />
                组织 {principal.orgUnitId}
              </span>
            ) : null}
            {principal?.tenantId ? (
              <Link to="/security/mfa" className="context-chip" title="两步验证（MFA）">
                <Icon name="shield" size={13} />
                两步验证
              </Link>
            ) : null}
            <div className="user-chip">
              <span className="avatar">{(principal?.username ?? 'U').slice(0, 1).toUpperCase()}</span>
              <div className="user-meta">
                <strong>{principal?.username ?? '未登录'}</strong>
                <span>{principal?.authType === 'TENANT' ? '租户成员' : '平台账号'}</span>
              </div>
            </div>
            <button type="button" className="btn btn-ghost btn-sm" onClick={handleLogout}>
              <Icon name="logout" size={15} />
              退出
            </button>
          </div>
        </header>

        {principal?.mustChangePassword ? (
          <div className="global-alert" role="status">
            <Icon name="alert" size={15} />
            <div>
              <strong>当前账号仍使用初始密码</strong>
              <span>请尽快修改密码，避免账号被他人使用（本期仅提示，不强制跳转改密）。</span>
            </div>
          </div>
        ) : null}

        <main className="content">
          <Outlet />
        </main>
      </div>
    </div>
  )
}
