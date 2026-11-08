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
package com.suhong.jiuding.agent.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import com.suhong.jiuding.common.security.SecurityProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Minimal HTTP client for the two downstream services the agent tools read from.
 *
 * <p>Every failure is converted into a structured tool observation
 * ({@code {"ok":false,"error":"..."}}) so a dead downstream degrades the agent
 * answer instead of breaking the run (engineering guide rule 9).
 */
@Component
public class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);
    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final TenancyAgentProperties properties;
    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public DownstreamClient(TenancyAgentProperties properties, SecurityProperties securityProperties,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    /** Documented downstream contract: GET /internal/tenants/{id} with X-Internal-Token. */
    public Result getTenant(String tenantId) {
        String path = "/internal/tenants/" + URLEncoder.encode(tenantId, StandardCharsets.UTF_8);
        return get(properties.getServices().getTenantUrl() + path,
                "X-Internal-Token: " + properties.getInternalToken());
    }

    /** Approval pending list, used by the intake/approval agents for context. */
    public Result listPendingApprovals() {
        return get(properties.getServices().getApprovalUrl() + "/api/approvals/pending",
                "X-API-Key: " + firstApiKey());
    }

    private Result get(String url, String credentialHeader) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(TIMEOUT)
                    .header("Accept", "application/json")
                    .GET();
            int separator = credentialHeader.indexOf(": ");
            builder.header(credentialHeader.substring(0, separator),
                    credentialHeader.substring(separator + 2));
            HttpResponse<String> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonNode root = parse(response.body());
            if (response.statusCode() / 100 != 2) {
                String message = root != null && root.path("message").isTextual()
                        ? root.path("message").asText() : "downstream HTTP " + response.statusCode();
                return new Result(false, response.statusCode(), null, message);
            }
            JsonNode data = root != null && root.has("data") ? root.get("data") : root;
            return new Result(true, response.statusCode(), data, null);
        } catch (Exception e) {
            log.debug("downstream call failed ({}): {}", url, e.toString());
            return new Result(false, 0, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private JsonNode parse(String body) {
        if (body == null || body.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(body);
        } catch (Exception e) {
            return null;
        }
    }

    private String firstApiKey() {
        List<String> keys = securityProperties.getApiKeys();
        return keys == null || keys.isEmpty() ? "dev-mcp-key" : keys.get(0);
    }

    /** Downstream call outcome. */
    public record Result(boolean ok, int status, JsonNode data, String error) {
    }
}
