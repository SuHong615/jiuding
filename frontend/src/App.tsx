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
import type { ReactNode } from 'react'
import { Link, Navigate, Route, Routes, useLocation } from 'react-router-dom'
import { useAuth } from './auth/AuthContext'
import { Layout } from './components/Layout'
import { Icon } from './components/Icon'
import { LoginPage } from './pages/LoginPage'
import { OverviewPage } from './pages/OverviewPage'
import { TenantsPage } from './pages/TenantsPage'
import { TenantDetailPage } from './pages/TenantDetailPage'
import { CollectionPage } from './pages/CollectionPage'
import { ApprovalsPage } from './pages/ApprovalsPage'
import { ApprovalDetailPage } from './pages/ApprovalDetailPage'
import { SkillsPage } from './pages/SkillsPage'
import { SkillDetailPage } from './pages/SkillDetailPage'
import { AgentsPage } from './pages/AgentsPage'
import { AgentRunDetailPage } from './pages/AgentRunDetailPage'
import { McpPage } from './pages/McpPage'
import { OnboardingPage } from './pages/OnboardingPage'
import { PermissionsPage } from './pages/PermissionsPage'
import { SsoCallbackPage } from './pages/SsoCallbackPage'
import { SecurityMfaPage } from './pages/SecurityMfaPage'
import { ScimPage } from './pages/ScimPage'
import { PlatformUsersPage } from './pages/PlatformUsersPage'
import { WorkflowsPage } from './pages/WorkflowsPage'
import { SsoProvidersPage } from './pages/SsoProvidersPage'

function RequireAuth({ children }: { children: ReactNode }) {
  const { token } = useAuth()
  const location = useLocation()

  if (!token) {
    return (
      <Navigate
        to="/login"
        replace
        state={{ from: `${location.pathname}${location.search}` }}
      />
    )
  }
  return <>{children}</>
}

function NotFoundPage() {
  return (
    <div className="page">
      <div className="card">
        <div className="state-block">
          <div className="state-icon state-icon-empty">
            <Icon name="alert" size={22} />
          </div>
          <h4>页面不存在</h4>
          <p>请检查地址，或返回运营总览继续操作。</p>
          <Link to="/" className="btn btn-primary btn-sm">
            返回运营总览
          </Link>
        </div>
      </div>
    </div>
  )
}

export function App() {
  return (
    <Routes>
      <Route path="/login" element={<LoginPage />} />
      <Route path="/onboarding" element={<OnboardingPage />} />
      <Route path="/sso-callback" element={<SsoCallbackPage />} />
      <Route
        path="/"
        element={
          <RequireAuth>
            <Layout />
          </RequireAuth>
        }
      >
        <Route index element={<OverviewPage />} />
        <Route path="tenants" element={<TenantsPage />} />
        <Route path="tenants/:id" element={<TenantDetailPage />} />
        <Route path="collection" element={<CollectionPage />} />
        <Route path="approvals" element={<ApprovalsPage />} />
        <Route path="approvals/:id" element={<ApprovalDetailPage />} />
        <Route path="skills" element={<SkillsPage />} />
        <Route path="skills/:id" element={<SkillDetailPage />} />
        <Route path="agents" element={<AgentsPage />} />
        <Route path="agents/:runId" element={<AgentRunDetailPage />} />
        <Route path="mcp" element={<McpPage />} />
        <Route path="mcp/keys" element={<McpPage focus="keys" />} />
        <Route path="mcp/audits" element={<McpPage focus="audits" />} />
        <Route path="workflows" element={<WorkflowsPage />} />
        <Route path="permissions" element={<PermissionsPage />} />
        <Route path="platform/users" element={<PlatformUsersPage />} />
        <Route path="platform/sso" element={<SsoProvidersPage />} />
        <Route path="security/mfa" element={<SecurityMfaPage />} />
        <Route path="scim" element={<ScimPage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Route>
    </Routes>
  )
}
