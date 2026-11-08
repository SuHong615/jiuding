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

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Request payload for creating an org unit (docs/11 section 6.2). */
public record OrgUnitCreateRequest(
        String parentId,
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$",
                message = "must start with a letter/digit and contain only letters, digits, '-' or '_'")
        String code,
        @NotBlank
        @Size(max = 128)
        String name,
        @NotBlank
        @Size(max = 16)
        String type,
        Integer sortOrder,
        String managerAccountId) {
}
