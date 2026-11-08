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

import com.suhong.jiuding.common.security.Principal;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Public projection of a token principal (docs/13 §2.3).
 *
 * <p>{@code authType} distinguishes the two login channels: {@code PLATFORM} for the
 * gateway preset users and {@code TENANT} for tenant-directory accounts.
 * {@code mustChangePassword} is only known right after tenant authentication, so it is
 * omitted from {@code /api/auth/me} JWT-derived responses.
 */
public record PrincipalView(String userId, String username, String tenantId, Set<String> roles,
                            String orgUnitId, Boolean mustChangePassword, String authType) {

    public static final String AUTH_PLATFORM = "PLATFORM";
    public static final String AUTH_TENANT = "TENANT";

    public static PrincipalView from(Principal principal, String authType) {
        return from(principal, authType, null);
    }

    public static PrincipalView from(Principal principal, String authType, Boolean mustChangePassword) {
        return new PrincipalView(
                principal.userId(),
                principal.username(),
                principal.tenantId(),
                principal.roles() == null ? Set.of() : new LinkedHashSet<>(principal.roles()),
                principal.orgUnitId(),
                mustChangePassword,
                authType);
    }
}
