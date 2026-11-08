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

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Platform account returned by tenant-service
 * {@code POST /internal/platform-users/authenticate} (docs/20 §2/§3).
 */
public record PlatformUserResult(String userId, String username, String displayName,
                                 Set<String> roles, String status) {

    public PlatformUserResult {
        roles = roles == null ? Set.of() : Set.copyOf(new LinkedHashSet<>(roles));
    }

    public boolean isActive() {
        return status == null || status.isBlank() || "ACTIVE".equalsIgnoreCase(status);
    }
}
