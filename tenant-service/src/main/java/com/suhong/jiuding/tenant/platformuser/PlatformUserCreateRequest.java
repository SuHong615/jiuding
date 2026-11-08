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
package com.suhong.jiuding.tenant.platformuser;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/** Body of {@code POST /api/platform/users} (docs/20 section 3). */
public record PlatformUserCreateRequest(
        @NotBlank
        @Size(max = 64)
        String username,
        String password,
        @Size(max = 128)
        String displayName,
        @Email
        @Size(max = 128)
        String email,
        List<String> roles,
        @Size(max = 16)
        String status) {
}
