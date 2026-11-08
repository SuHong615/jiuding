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

/** In-memory user record of the gateway. Password is stored as a PBKDF2 hash. */
public record TenantUser(String userId, String username, String tenantId, Set<String> roles, String passwordHash) {

    public TenantUser {
        roles = roles == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(roles));
    }

    public Principal toPrincipal() {
        return new Principal(userId, username, tenantId, roles, "JWT");
    }
}
