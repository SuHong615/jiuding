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
package com.suhong.jiuding.tenant.scim;

import java.time.LocalDateTime;

/** Row projection of {@code scim_tokens} (docs/16 section 7.1). */
public record ScimTokenRow(
        String id,
        String tenantId,
        String name,
        String tokenPrefix,
        String tokenHash,
        String status,
        String createdBy,
        LocalDateTime createdAt,
        LocalDateTime lastUsedAt,
        LocalDateTime revokedAt) {
}
