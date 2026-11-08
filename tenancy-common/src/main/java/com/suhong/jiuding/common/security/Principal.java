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
package com.suhong.jiuding.common.security;

import java.util.Set;

/** Authenticated caller: either a JWT principal or an API key. */
public record Principal(String userId, String username, String tenantId, Set<String> roles,
                        String authType, String orgUnitId) {

    /** Backwards-compatible constructor for callers without an organization scope. */
    public Principal(String userId, String username, String tenantId, Set<String> roles, String authType) {
        this(userId, username, tenantId, roles, authType, null);
    }

    public boolean isPlatformAdmin() {
        return roles != null && (roles.contains("PLATFORM_ADMIN") || roles.contains("SYS_ADMIN"));
    }

    public boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }

    public com.suhong.jiuding.common.tenant.TenantScope toScope() {
        return new com.suhong.jiuding.common.tenant.TenantScope(tenantId,
                userId, roles == null ? Set.of() : roles, orgUnitId);
    }

    public static Principal apiKey(String keyId) {
        return new Principal(keyId, keyId, null, Set.of("MCP_CLIENT"), "API_KEY");
    }

    public static Principal internalService() {
        return new Principal("internal", "internal-service", null, Set.of("INTERNAL"), "INTERNAL_TOKEN");
    }
}
