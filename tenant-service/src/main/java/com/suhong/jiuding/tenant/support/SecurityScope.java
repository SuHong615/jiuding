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
package com.suhong.jiuding.tenant.support;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.repository.TenantRow;

/**
 * Fail-closed tenant scope resolution (docs/11 section 5 RBAC matrix).
 *
 * <p>Access rules:
 * <ul>
 *   <li>PLATFORM_ADMIN / SYS_ADMIN sees every tenant and may write.</li>
 *   <li>Trusted machine callers authenticated with a static API key (MCP) are platform wide.</li>
 *   <li>AUDITOR with a tenant id is read only inside that tenant; AUDITOR without a tenant
 *       id (the platform preset {@code auditor} user) is <b>read only across every tenant</b>.
 *       Reads are allowed, every write path raises 403.</li>
 *   <li>Every other role is confined to its own tenant; a JWT without {@code tenantId}
 *       is rejected with 401 instead of silently widening the scope.</li>
 * </ul>
 * Internal {@code /internal/**} calls are authenticated by tenancy-common's AuthFilter
 * (X-Internal-Token) and never go through this class.
 */
public final class SecurityScope {

    private SecurityScope() {
    }

    /**
     * Resolves the caller scope for read paths. Platform administrators, trusted API-key
     * machine callers and auditors (scoped or global) are accepted; any other JWT without
     * a tenant id fails closed with 401.
     */
    public static TenantScope require() {
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        if (isPlatformWide(scope) || scope.hasRole("AUDITOR")) {
            return scope;
        }
        if (scope.tenantId() == null || scope.tenantId().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        return scope;
    }

    public static boolean isPlatformWide(TenantScope scope) {
        if (scope == null) {
            return false;
        }
        if (scope.isPlatformAdmin()) {
            return true;
        }
        Principal principal = AuthContext.get();
        return principal != null && "API_KEY".equals(principal.authType());
    }

    /** AUDITOR without a tenant id: cross-tenant read-only (docs/11 section 5). */
    public static boolean isGlobalAuditor(TenantScope scope) {
        return scope != null && scope.hasRole("AUDITOR")
                && (scope.tenantId() == null || scope.tenantId().isBlank());
    }

    /** Writes require PLATFORM_ADMIN, a trusted machine caller or the tenant administrator. */
    public static void requireWrite(TenantScope scope) {
        if (isPlatformWide(scope) || (scope != null && scope.hasRole("TENANT_ADMIN"))) {
            return;
        }
        throw BizException.forbidden("platform administrator or tenant administrator role required");
    }

    /**
     * Organization / account writes additionally accept ORG_ADMIN. The organization subtree
     * boundary itself is enforced by {@code TenantAccess}.
     */
    public static void requireOrgWrite(TenantScope scope) {
        if (isPlatformWide(scope)
                || (scope != null && (scope.hasRole("TENANT_ADMIN") || scope.hasRole("ORG_ADMIN")))) {
            return;
        }
        throw BizException.forbidden(
                "org unit/account write requires ORG_ADMIN, TENANT_ADMIN or PLATFORM_ADMIN");
    }

    public static boolean isOrgAdmin(TenantScope scope) {
        return scope != null && scope.hasRole("ORG_ADMIN");
    }

    public static boolean isMember(TenantScope scope) {
        return scope != null && scope.hasRole("MEMBER");
    }

    public static boolean isOrgScoped(TenantScope scope) {
        return isOrgAdmin(scope) || isMember(scope);
    }

    /** Global actions (tenant soft delete) accept only a platform administrator JWT. */
    public static void requireJwtPlatformAdmin(TenantScope scope) {
        Principal principal = AuthContext.get();
        boolean jwt = principal != null && "JWT".equals(principal.authType());
        if (!jwt || scope == null || !scope.isPlatformAdmin()) {
            throw BizException.forbidden("platform administrator JWT required");
        }
    }

    public static void requirePlatformWide(TenantScope scope) {
        if (!isPlatformWide(scope)) {
            throw BizException.forbidden("platform administrator role required");
        }
    }

    /**
     * @return null for platform wide and global-auditor callers (no tenant filter), otherwise
     *         the tenant id/code the caller is confined to.
     */
    public static String scopedTenantId(TenantScope scope) {
        if (isPlatformWide(scope) || isGlobalAuditor(scope)) {
            return null;
        }
        return require().tenantId();
    }

    /** Row level isolation check: 403 when the caller tries to touch another tenant. */
    public static void assertVisible(TenantScope scope, TenantRow row) {
        if (isPlatformWide(scope) || isGlobalAuditor(scope)) {
            return;
        }
        String tenantId = scope.tenantId();
        if (tenantId != null && (tenantId.equals(row.id()) || tenantId.equals(row.tenantCode()))) {
            return;
        }
        throw new BizException(ErrorCode.FORBIDDEN, "tenant is outside of the caller tenant scope");
    }

    public static String actor(TenantScope scope) {
        Principal principal = AuthContext.get();
        if (principal != null && principal.userId() != null && !principal.userId().isBlank()) {
            return principal.userId();
        }
        if (scope != null && scope.userId() != null && !scope.userId().isBlank()) {
            return scope.userId();
        }
        return "system";
    }
}
