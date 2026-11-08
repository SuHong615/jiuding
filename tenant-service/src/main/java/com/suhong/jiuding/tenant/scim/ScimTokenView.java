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

import com.suhong.jiuding.tenant.support.TimeUtil;

/** Token metadata returned by the platform token management API (never the hash). */
public record ScimTokenView(
        String id,
        String tenantId,
        String name,
        String prefix,
        String status,
        String createdBy,
        String createdAt,
        String lastUsedAt,
        String revokedAt) {

    public static ScimTokenView of(ScimTokenRow row) {
        return new ScimTokenView(row.id(), row.tenantId(), row.name(), row.tokenPrefix(),
                row.status(), row.createdBy(), TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.lastUsedAt()), TimeUtil.iso(row.revokedAt()));
    }
}
