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
package com.suhong.jiuding.mcp.credentials;

import com.suhong.jiuding.mcp.support.Iso8601;

/** Public projection of an MCP API key (never contains the clear-text key or its hash). */
public record ApiKeyView(String id,
                         String name,
                         String keyPrefix,
                         String status,
                         String ownerUsername,
                         String tenantId,
                         String createdAt,
                         String expiresAt,
                         String lastUsedAt,
                         String revokedAt) {

    public static ApiKeyView of(ApiKeyRow row) {
        return new ApiKeyView(row.id(), row.name(), row.keyPrefix(), row.status(),
                row.ownerUsername(), row.tenantId(),
                Iso8601.format(row.createdAt()), Iso8601.format(row.expiresAt()),
                Iso8601.format(row.lastUsedAt()), Iso8601.format(row.revokedAt()));
    }
}
