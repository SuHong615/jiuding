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
package com.suhong.jiuding.tenant.permission;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Maps {@link RequirePermission} semantic aliases onto the seeded v5 permission codes
 * (docs/15 section 7.1). The catalogue rows are not changed, so the documented built-in
 * matrices keep their existing counts; aliases are a many-to-one compatibility layer.
 */
public final class PermissionCatalog {

    private static final Map<String, Set<String>> ALIASES = Map.ofEntries(
            Map.entry("tenant:read", Set.of("TENANT_READ")),
            Map.entry("tenant:write", Set.of(
                    "TENANT_UPDATE", "TENANT_SUBMIT", "TENANT_LIFECYCLE", "TENANT_DELETE")),
            Map.entry("org:read", Set.of("ORG_READ")),
            Map.entry("org:write", Set.of(
                    "ORG_CREATE", "ORG_UPDATE", "ORG_MOVE", "ORG_DELETE")),
            Map.entry("account:read", Set.of("ACCOUNT_READ", "ACCOUNT_LOGIN_EVENTS_READ")),
            Map.entry("account:write", Set.of(
                    "ACCOUNT_CREATE", "ACCOUNT_UPDATE", "ACCOUNT_IMPORT", "ACCOUNT_CHANGE_PASSWORD")),
            Map.entry("account:security", Set.of(
                    "ACCOUNT_STATUS", "ACCOUNT_PASSWORD_RESET", "ACCOUNT_DELETE", "ACCOUNT_UNLOCK")),
            Map.entry("rating:read", Set.of("RATING_READ")),
            Map.entry("rating:assess", Set.of("RATING_ASSESS")),
            Map.entry("rating:override", Set.of("RATING_OVERRIDE")),
            Map.entry("rbac:read", Set.of("RBAC_READ")),
            Map.entry("rbac:write", Set.of("RBAC_MANAGE")),
            Map.entry("audit:read", Set.of("TENANT_AUDIT_READ", "TENANT_AUDIT_EXPORT")));

    private PermissionCatalog() {
    }

    /**
     * Resolves annotation values to the union of database permission codes.
     * Unknown aliases containing {@code :} resolve to no codes (fail closed); non alias
     * values are treated as literal permission codes.
     */
    public static Set<String> requiredCodes(String[] values) {
        Set<String> codes = new LinkedHashSet<>();
        if (values == null) {
            return codes;
        }
        for (String raw : values) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String value = raw.trim();
            Set<String> mapped = ALIASES.get(value.toLowerCase(Locale.ROOT));
            if (mapped != null) {
                codes.addAll(mapped);
            } else if (!value.contains(":")) {
                codes.add(value.toUpperCase(Locale.ROOT));
            }
        }
        return codes;
    }

    /** @return the database codes backing one semantic alias (empty when unknown). */
    public static Set<String> codesOf(String alias) {
        if (alias == null || alias.isBlank()) {
            return Set.of();
        }
        return ALIASES.getOrDefault(alias.trim().toLowerCase(Locale.ROOT), Set.of());
    }

    /** Visible alias list, useful for documentation and diagnostics. */
    public static Set<String> aliases() {
        return ALIASES.keySet();
    }
}
