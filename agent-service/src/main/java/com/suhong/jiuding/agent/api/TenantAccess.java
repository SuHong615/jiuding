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
package com.suhong.jiuding.agent.api;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;

/**
 * Tenant scoping helper for agent-service.
 *
 * <p>agent-service is a platform-level service (skill registry + agent runtime),
 * but a tenant-scoped caller must never operate on another tenant: this helper
 * resolves the effective tenant and rejects cross-tenant requests (fail-closed
 * for mismatches, implicit scope fill for tenant admins).
 */
public final class TenantAccess {

    private TenantAccess() {
    }

    public static String resolve(String requestedTenantId) {
        TenantScope scope = TenantContext.get();
        if (scope == null || scope.isPlatformAdmin()) {
            return blankToNull(requestedTenantId);
        }
        String scopeTenant = blankToNull(scope.tenantId());
        String requested = blankToNull(requestedTenantId);
        if (scopeTenant == null) {
            return requested;
        }
        if (requested == null) {
            return scopeTenant;
        }
        if (!scopeTenant.equals(requested)) {
            throw BizException.forbidden("tenant mismatch: token tenant " + scopeTenant
                    + " cannot access " + requested);
        }
        return requested;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
