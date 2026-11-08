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

import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;

import java.util.Set;

/** Binds / clears the thread-local auth context in service level tests (no servlet filter). */
public final class TestSecurity {

    private TestSecurity() {
    }

    public static void platformAdmin() {
        apply(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    public static void tenantAdmin(String tenantId) {
        apply(new Principal("u-op", "operator", tenantId, Set.of("TENANT_ADMIN"), "JWT"));
    }

    public static void apiKey() {
        apply(new Principal("mcp:test", "mcp:test", null, Set.of("MCP_CLIENT"), "API_KEY"));
    }

    public static void auditorWithoutTenant() {
        apply(new Principal("u-auditor", "auditor", null, Set.of("AUDITOR"), "JWT"));
    }

    public static void apply(Principal principal) {
        AuthContext.set(principal);
        TenantContext.set(new TenantScope(principal.tenantId(), principal.userId(), principal.roles()));
    }

    public static void clear() {
        AuthContext.clear();
        TenantContext.clear();
    }
}
