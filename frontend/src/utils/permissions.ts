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
import { useMemo } from 'react'
import { useAuth } from '../auth/AuthContext'
import type { Principal } from '../api/types'

export interface Permissions {
  roles: string[]
  isPlatformAdmin: boolean
  isTenantAdmin: boolean
  isOrgAdmin: boolean
  isAuditor: boolean
  isMember: boolean
  isApprover: boolean
  isPlatformUser: boolean
  canCreateTenant: boolean
  canManageTenant: boolean
  canDeleteTenant: boolean
  canManageOrg: boolean
  canCreateRootOrg: boolean
  canManageAccounts: boolean
  canReadAccounts: boolean
  canViewLoginEvents: boolean
  canChangeOwnPassword: boolean
  canExportTenant: boolean
  canOffboardTenant: boolean
  canAssessRating: boolean
  canOverrideRating: boolean
  canReadRating: boolean
  memberSelfOnly: boolean
  assignableAccountRoles: string[]
}

function hasRole(principal: Principal | null, role: string): boolean {
  return Boolean(principal?.roles?.includes(role))
}

/**
 * 依据 docs/13 §3 RBAC 矩阵收敛前端可操作项：
 * - PLATFORM_ADMIN：全部
 * - TENANT_ADMIN：本租户写操作（不可删除租户、不可覆盖评级）
 * - ORG_ADMIN：子树内组织/账号写操作（不可新建根、不可评估）
 * - AUDITOR：只读
 * - MEMBER：只读本人（账号页仅本人）
 * 注：后端仍强校验，前端仅做 UI 收敛。
 */
export function buildPermissions(principal: Principal | null): Permissions {
  const roles = principal?.roles ?? []
  const isPlatformAdmin = hasRole(principal, 'PLATFORM_ADMIN')
  const isTenantAdmin = hasRole(principal, 'TENANT_ADMIN')
  const isOrgAdmin = hasRole(principal, 'ORG_ADMIN')
  const isAuditor = hasRole(principal, 'AUDITOR')
  const isMember = hasRole(principal, 'MEMBER')
  const isApprover = hasRole(principal, 'APPROVER')
  const isPlatformUser = !principal?.tenantId

  const canManageTenant = isPlatformAdmin || isTenantAdmin
  const canManageOrg = isPlatformAdmin || isTenantAdmin || isOrgAdmin
  const canManageAccounts = isPlatformAdmin || isTenantAdmin || isOrgAdmin
  const canReadRating = isPlatformAdmin || isTenantAdmin || isOrgAdmin || isAuditor

  const assignableAccountRoles = isPlatformAdmin || isTenantAdmin
    ? ['TENANT_ADMIN', 'ORG_ADMIN', 'MEMBER', 'AUDITOR']
    : isOrgAdmin
      ? ['MEMBER', 'AUDITOR']
      : []

  return {
    roles,
    isPlatformAdmin,
    isTenantAdmin,
    isOrgAdmin,
    isAuditor,
    isMember,
    isApprover,
    isPlatformUser,
    canCreateTenant: isPlatformAdmin || isTenantAdmin,
    canManageTenant,
    canDeleteTenant: isPlatformAdmin,
    canManageOrg,
    canCreateRootOrg: isPlatformAdmin || isTenantAdmin,
    canManageAccounts,
    canReadAccounts: isPlatformAdmin || isTenantAdmin || isOrgAdmin || isAuditor || isMember,
    // 登录历史：管理员/组织管理员/审计员全部账号；MEMBER 仅本人（后端强制）
    canViewLoginEvents: isPlatformAdmin || isTenantAdmin || isOrgAdmin || isAuditor || isMember,
    // 仅租户登录账号可修改自己的密码（平台账号改密不在本期范围）
    canChangeOwnPassword: Boolean(principal?.tenantId),
    // docs/18 §4：导出见平台管理员/租户管理员/审计员；离户仅平台管理员
    canExportTenant: isPlatformAdmin || isTenantAdmin || isAuditor,
    canOffboardTenant: isPlatformAdmin,
    canAssessRating: isPlatformAdmin || isTenantAdmin,
    canOverrideRating: isPlatformAdmin,
    canReadRating,
    memberSelfOnly: isMember,
    assignableAccountRoles,
  }
}

export function usePermissions(): Permissions {
  const { principal } = useAuth()
  return useMemo(() => buildPermissions(principal), [principal])
}
