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

import java.util.Map;

/** Contract for calling agent-service skill execution. Implementations may be stubbed in tests. */
public interface AgentServiceClient {

    /**
     * Runs a skill and returns the unwrapped {@code data} node of the response
     * (an {@code ApiResponse} envelope is transparently unwrapped).
     *
     * @throws DownstreamException when the service is unreachable, returns a non-2xx
     *                             status, an error envelope or an empty payload.
     */
    JsonNode runSkill(String skillId, Map<String, Object> input, String tenantId);
}
