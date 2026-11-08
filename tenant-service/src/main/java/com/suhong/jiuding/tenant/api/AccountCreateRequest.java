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
package com.suhong.jiuding.tenant.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request payload for creating a tenant account (docs/11 section 6.3).
 *
 * <p>When {@code initialPassword} is absent a one-time temporary password is generated and
 * returned exactly once in the create response.
 */
public record AccountCreateRequest(
        @NotBlank
        @Size(max = 64)
        String username,
        @NotBlank
        @Size(max = 128)
        String displayName,
        @Email
        @Size(max = 128)
        String email,
        @Size(max = 32)
        String phone,
        String orgUnitId,
        @Size(max = 24)
        String role,
        String initialPassword) {
}
