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
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * Fail-closed access guard for the tenant identity domain (org units / accounts / ratings).
 *
 * <p>Rules (docs/11 section 2.2/5 and docs/13 section 3):
 * <ul>
 *   <li>Every request needs an authenticated scope; a JWT without tenant id for an
 *       unscoped role fails with 401 ({@link SecurityScope#require()}).</li>
 *   <li>PLATFORM_ADMIN / trusted API-key callers may address every tenant; AUDITOR is
 *       read only (global auditor reads across tenants, docs/13 section 3).</li>
 *   <li>TENANT_ADMIN may read/write its own tenant.</li>
 *   <li>ORG_ADMIN is confined to its own {@code org_units.path} subtree: read/write of org
 *       units and accounts inside the subtree, no root creation, no move outside the subtree,
 *       read only rating; ROLE grants limited to MEMBER/AUDITOR.</li>
 *   <li>MEMBER may only read org units of its own subtree and its own account; every write
 *       and every rating read is 403.</li>
 *   <li>Cross-tenant access is 403 (never 404, to avoid leaking existence).</li>
 * </ul>
 */
@Component
public class TenantAccess {

    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;

    public TenantAccess(TenantRepository tenants, OrgUnitRepository orgUnits) {
        this.tenants = tenants;
        this.orgUnits = orgUnits;
    }

    // ------------------------------------------------------------------ scope basics

    public TenantScope requireScope() {
        return SecurityScope.require();
    }

    public boolean isPlatform(TenantScope scope) {
        return SecurityScope.isPlatformWide(scope);
    }

    public boolean isOrgAdmin(TenantScope scope) {
        return SecurityScope.isOrgAdmin(scope);
    }

    public boolean isMember(TenantScope scope) {
        return SecurityScope.isMember(scope);
    }

    public boolean isOrgScoped(TenantScope scope) {
        return SecurityScope.isOrgScoped(scope);
    }

    /** Resolves a tenant by UUID or tenant code. Soft deleted tenants are 404. */
    public TenantRow requireTenant(String idOrTenantCode) {
        TenantRow row = lookup(idOrTenantCode);
        if (row.deletedAt() != null) {
            throw BizException.notFound("tenant not found: " + idOrTenantCode);
        }
        return row;
    }

    /** Same as {@link #requireTenant(String)} but keeps soft deleted rows addressable. */
    public TenantRow requireTenantIncludingDeleted(String idOrTenantCode) {
        return lookup(idOrTenantCode);
    }

    /** Row level isolation: 403 when a non platform/global auditor addresses another tenant. */
    public void assertSameTenant(TenantScope scope, TenantRow tenant) {
        SecurityScope.assertVisible(scope, tenant);
    }

    /** Tenant level writes (profile/lifecycle/rating assess) require platform or TENANT_ADMIN. */
    public void requireWrite(TenantScope scope) {
        SecurityScope.requireWrite(scope);
    }

    /** Org / account writes additionally accept ORG_ADMIN (subtree enforced separately). */
    public void requireOrgWrite(TenantScope scope) {
        SecurityScope.requireOrgWrite(scope);
    }

    /** Ratings assessment is allowed for platform administrators and tenant administrators. */
    public void requireAssess(TenantScope scope) {
        SecurityScope.requireWrite(scope);
    }

    /** Rating read is forbidden for MEMBER (docs/13 section 3). */
    public void requireRatingRead(TenantScope scope) {
        if (isMember(scope)) {
            throw BizException.forbidden("MEMBER role cannot read tenant ratings");
        }
    }

    /** Global actions (tenant soft delete, rating override) are platform administrator only. */
    public void requirePlatformAdmin(TenantScope scope) {
        if (!SecurityScope.isPlatformWide(scope)) {
            throw BizException.forbidden("platform administrator role required");
        }
    }

    // ------------------------------------------------------------------ organization scope

    /**
     * Resolves the organization unit the caller is bound to (ORG_ADMIN / MEMBER).
     * Missing or unreadable scope is a 403, never a silent tenant-wide widening.
     */
    public OrgUnitRow requireScopeOrgUnit(TenantScope scope, String tenantId) {
        String orgUnitId = scope == null ? null : scope.orgUnitId();
        if (orgUnitId == null || orgUnitId.isBlank()) {
            throw BizException.forbidden("organization scope is missing");
        }
        return orgUnits.findById(tenantId, orgUnitId)
                .orElseThrow(() -> BizException.forbidden("organization scope is not accessible"));
    }

    /** @return null for roles without an organization scope. */
    public OrgUnitRow orgScopeOrNull(TenantScope scope, String tenantId) {
        return isOrgScoped(scope) ? requireScopeOrgUnit(scope, tenantId) : null;
    }

    public boolean isWithinSubtree(OrgUnitRow scopeUnit, String candidatePath) {
        return scopeUnit != null && candidatePath != null
                && (candidatePath.equals(scopeUnit.path()) || candidatePath.startsWith(scopeUnit.path() + "/"));
    }

    public void assertOrgReadable(TenantScope scope, String tenantId, OrgUnitRow target) {
        if (!isOrgScoped(scope)) {
            return;
        }
        OrgUnitRow scopeUnit = requireScopeOrgUnit(scope, tenantId);
        if (!isWithinSubtree(scopeUnit, target.path())) {
            throw BizException.forbidden("org unit is outside of the caller organization scope");
        }
    }

    public void assertOrgWritable(TenantScope scope, String tenantId, OrgUnitRow target) {
        if (SecurityScope.isPlatformWide(scope) || (scope != null && scope.hasRole("TENANT_ADMIN"))) {
            return;
        }
        if (!isOrgAdmin(scope)) {
            throw BizException.forbidden("org unit write requires ORG_ADMIN, TENANT_ADMIN or PLATFORM_ADMIN");
        }
        assertOrgReadable(scope, tenantId, target);
    }

    /** @return null when the calling role is not organization scoped. */
    public OrgUnitRow accountOrgUnit(TenantScope scope, String tenantId, AccountRow account) {
        if (account.orgUnitId() == null) {
            return null;
        }
        return orgUnits.findById(tenantId, account.orgUnitId()).orElse(null);
    }

    public void assertAccountReadable(TenantScope scope, String tenantId, AccountRow account) {
        if (isMember(scope)) {
            if (!Objects.equals(scope.userId(), account.id())) {
                throw BizException.forbidden("MEMBER role can only read its own account");
            }
            return;
        }
        if (!isOrgAdmin(scope)) {
            return;
        }
        OrgUnitRow target = accountOrgUnit(scope, tenantId, account);
        if (target == null) {
            throw BizException.forbidden("account is outside of the caller organization scope");
        }
        OrgUnitRow scopeUnit = requireScopeOrgUnit(scope, tenantId);
        if (!isWithinSubtree(scopeUnit, target.path())) {
            throw BizException.forbidden("account is outside of the caller organization scope");
        }
    }

    public void assertAccountWritable(TenantScope scope, String tenantId, AccountRow account) {
        if (SecurityScope.isPlatformWide(scope) || (scope != null && scope.hasRole("TENANT_ADMIN"))) {
            return;
        }
        if (!isOrgAdmin(scope)) {
            throw BizException.forbidden("account write requires ORG_ADMIN, TENANT_ADMIN or PLATFORM_ADMIN");
        }
        assertAccountReadable(scope, tenantId, account);
    }

    public String actor(TenantScope scope) {
        return SecurityScope.actor(scope);
    }

    private TenantRow lookup(String idOrTenantCode) {
        if (idOrTenantCode == null || idOrTenantCode.isBlank()) {
            throw BizException.notFound("tenant not found");
        }
        return tenants.findById(idOrTenantCode)
                .or(() -> tenants.findByCode(idOrTenantCode))
                .orElseThrow(() -> BizException.notFound("tenant not found: " + idOrTenantCode));
    }
}
