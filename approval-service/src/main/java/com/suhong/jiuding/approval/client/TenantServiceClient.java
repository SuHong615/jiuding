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
package com.suhong.jiuding.approval.client;

import com.fasterxml.jackson.databind.JsonNode;

/** Contract for calling tenant-service internal endpoints. Implementations may be stubbed in tests. */
public interface TenantServiceClient {

    /**
     * Moves a tenant to the given status through {@code POST /internal/tenants/{id}/status}.
     *
     * @throws DownstreamException when the call fails; callers keep the approval in
     *                             PROVISIONING and retry later.
     */
    void updateStatus(String tenantId, String status, String reason);

    /**
     * Reads the tenant snapshot through {@code GET /internal/tenants/{id}}.
     *
     * @throws DownstreamException when the call fails.
     */
    JsonNode getTenant(String tenantId);
}
