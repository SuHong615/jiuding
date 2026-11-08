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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** HTTP implementation of {@link TenantServiceClient} (internal token protected). */
@Component
public class HttpTenantServiceClient implements TenantServiceClient {

    private final RestClient restClient;
    private final ApprovalProperties properties;
    private final ObjectMapper objectMapper;

    public HttpTenantServiceClient(@Qualifier("tenantRestClient") RestClient restClient,
                                   ApprovalProperties properties,
                                   ObjectMapper objectMapper) {
        this.restClient = restClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @Override
    public void updateStatus(String tenantId, String status, String reason) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason == null ? "" : reason);
        String raw;
        try {
            raw = restClient.post()
                    .uri("/internal/tenants/{id}/status", tenantId)
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-API-Key", properties.getDownstreamApiKey())
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new DownstreamException("tenant-service status update failed for tenant " + tenantId
                    + " -> " + status + ": " + ex.getMessage(), ex);
        }
        checkEnvelope(raw, "tenant-service status update for tenant " + tenantId);
    }

    @Override
    public JsonNode getTenant(String tenantId) {
        String raw;
        try {
            raw = restClient.get()
                    .uri("/internal/tenants/{id}", tenantId)
                    .header("X-Internal-Token", properties.getInternalToken())
                    .header("X-API-Key", properties.getDownstreamApiKey())
                    .header("X-Request-Id", UUID.randomUUID().toString())
                    .accept(MediaType.APPLICATION_JSON)
                    .retrieve()
                    .body(String.class);
        } catch (Exception ex) {
            throw new DownstreamException("tenant-service get failed for tenant " + tenantId + ": " + ex.getMessage(), ex);
        }
        if (raw == null || raw.isBlank()) {
            throw new DownstreamException("tenant-service returned an empty body for tenant " + tenantId);
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.hasNonNull("code") && root.get("code").asInt() != 0) {
                throw new DownstreamException("tenant-service returned code " + root.get("code").asInt()
                        + " for tenant " + tenantId + ": " + root.path("message").asText(""));
            }
            return root.has("data") ? root.get("data") : root;
        } catch (DownstreamException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new DownstreamException("tenant-service returned unparseable body for tenant " + tenantId, ex);
        }
    }

    private void checkEnvelope(String raw, String action) {
        if (raw == null || raw.isBlank()) {
            return;
        }
        try {
            JsonNode root = objectMapper.readTree(raw);
            if (root.hasNonNull("code") && root.get("code").asInt() != 0) {
                throw new DownstreamException(action + " returned code " + root.get("code").asInt()
                        + ": " + root.path("message").asText(""));
            }
        } catch (DownstreamException ex) {
            throw ex;
        } catch (Exception ignored) {
            // status update succeeded with a non-JSON body; nothing to validate
        }
    }
}
