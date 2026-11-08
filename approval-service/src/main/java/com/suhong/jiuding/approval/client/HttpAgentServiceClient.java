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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP implementation of {@link AgentServiceClient}.
 *
 * <p>Accepts both the unified {@code ApiResponse{code,message,data}} envelope and a
 * bare payload, so it works regardless of whether agent-service wraps skill results.
 */
@Component
public class HttpAgentServiceClient implements AgentServiceClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAgentServiceClient.class);

    private final RestClient restClient;
    private final ApprovalProperties properties;
    private final ObjectMapper objectMapper;

    public HttpAgentServiceClient(@Qualifier("agentRestClient") RestClient restClient,
                                  ApprovalProperties properties,
                                  ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public JsonNode runSkill(String skillId, Map<String, Object> input, String tenantId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input == null ? Map.of() : input);
        if (tenantId != null && !tenantId.isBlank()) {
            body.put("tenantId", tenantId);
        }
        String raw;
        try {
            raw = restClient.post()
                    .uri("/api/skills/{skillId}/run", skillId)
                    .header("X-API-Key", properties.getDownstreamApiKey())
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new DownstreamException("agent-service call failed for skill " + skillId + ": " + ex.getMessage(), ex);
        }
        if (raw == null || raw.isBlank()) {
            throw new DownstreamException("agent-service returned an empty body for skill " + skillId);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.hasNonNull("code") && root.get("code").asInt() != 0) {
                throw new DownstreamException("agent-service returned code " + root.get("code").asInt()
                        + " for skill " + skillId + ": " + root.path("message").asText(""));
            }
            JsonNode data = root.has("data") ? root.get("data") : root;
            if (data == null || data.isNull() || data.isMissingNode()) {
                throw new DownstreamException("agent-service returned no data for skill " + skillId);
            }
            return data;
        } catch (DownstreamException ex) {
            throw ex;
        } catch (Exception ex) {
            log.debug("agent-service returned unparseable body for skill {}: {}", skillId, raw);
            throw new DownstreamException("agent-service returned unparseable body for skill " + skillId, ex);
        }
    }
}
