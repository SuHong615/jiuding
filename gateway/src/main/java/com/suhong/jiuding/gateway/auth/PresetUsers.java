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
package com.suhong.jiuding.gateway.auth;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The four preset users from {@code docs/02-api-contracts.md} §1.
 *
 * <p>Passwords live here as literals because they are demo credentials that must
 * be documented; they are converted to PBKDF2 hashes at startup and never stored.
 * An operator overrides the whole set with the {@code TENANCY_USERS} env var.
 */
final class PresetUsers {

    private PresetUsers() {
    }

    static List<Map<String, Object>> defaults() {
        return List.of(
                Map.of("userId", "u-admin", "username", "admin", "password", "Admin@123",
                        "tenantId", "", "roles", List.of("PLATFORM_ADMIN")),
                Map.of("userId", "u-approver", "username", "approver", "password", "Approver@123",
                        "tenantId", "", "roles", List.of("APPROVER")),
                Map.of("userId", "u-operator", "username", "operator", "password", "Operator@123",
                        "tenantId", "t-acme", "roles", List.of("TENANT_ADMIN")),
                Map.of("userId", "u-auditor", "username", "auditor", "password", "Auditor@123",
                        "tenantId", "", "roles", List.of("AUDITOR")));
    }

    static Set<String> defaultRoles(String username) {
        return switch (username) {
            case "admin" -> Set.of("PLATFORM_ADMIN");
            case "approver" -> Set.of("APPROVER");
            case "operator" -> Set.of("TENANT_ADMIN");
            case "auditor" -> Set.of("AUDITOR");
            default -> Set.of();
        };
    }
}
