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
package com.suhong.jiuding.gateway.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /api/platform/**} aggregation.
 *
 * <p>Every downstream call is best-effort: a dead service only degrades its own
 * section (zeros / {@code DOWN}), the aggregate endpoint still returns 200.
 */
@Service
public class PlatformService {

    private static final Logger log = LoggerFactory.getLogger(PlatformService.class);

    private final HttpClient httpClient;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public PlatformService(HttpClient httpClient, GatewayProperties properties, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** docs/02 §1 overview shape. */
    public Map<String, Object> overview(String authorization) {
        JsonNode tenant = fetchData(properties.getTenantServiceUrl() + "/api/tenants/overview", authorization);
        JsonNode approval = fetchData(properties.getApprovalServiceUrl() + "/api/approvals/overview", authorization);
        JsonNode agent = fetchData(properties.getAgentServiceUrl() + "/api/agents/overview", authorization);

        long skillTotal = longOr(agent, "skills", -1);
        if (skillTotal < 0) {
            skillTotal = countArray(properties.getAgentServiceUrl() + "/api/skills", authorization);
        }
        if (skillTotal < 0) {
            skillTotal = 0;
        }

        Map<String, Object> tenants = new LinkedHashMap<>();
        tenants.put("total", longOr(tenant, "total", 0));
        tenants.put("active", longOr(tenant, "active", 0));
        tenants.put("pending", longOr(tenant, "pending", 0));

        Map<String, Object> approvals = new LinkedHashMap<>();
        approvals.put("pending", longOr(approval, "pending", 0));
        approvals.put("autoApproved", longOr(approval, "autoApproved", 0));
        approvals.put("rejected", longOr(approval, "rejected", 0));

        Map<String, Object> skills = new LinkedHashMap<>();
        skills.put("total", skillTotal);

        Map<String, Object> agents = new LinkedHashMap<>();
        agents.put("runs", longOr(agent, "runs", 0));
        agents.put("todayRuns", longOr(agent, "todayRuns", 0));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tenants", tenants);
        result.put("approvals", approvals);
        result.put("skills", skills);
        result.put("agents", agents);
        result.put("services", health());
        return result;
    }

    /** docs/02 §1 health shape; every probe is isolated and time boxed. */
    public List<ServiceHealth> health() {
        List<ServiceHealth> result = new ArrayList<>();
        result.add(probe("tenant-service", properties.getTenantServiceUrl()));
        result.add(probe("approval-service", properties.getApprovalServiceUrl()));
        result.add(probe("agent-service", properties.getAgentServiceUrl()));
        result.add(probe("mcp-service", properties.getMcpServiceUrl()));
        return result;
    }

    private ServiceHealth probe(String name, String baseUrl) {
        long start = System.nanoTime();
        String status = "DOWN";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(baseUrl) + "/actuator/health"))
                    .timeout(Duration.ofMillis(properties.getHealthTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() == 200) {
                JsonNode body = safeRead(response.body());
                if (body != null && "UP".equalsIgnoreCase(body.path("status").asText())) {
                    status = "UP";
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.debug("health probe for {} failed: {}", name, ex.toString());
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        return new ServiceHealth(name, trimSlash(baseUrl), status, latencyMs);
    }

    /** Returns the unwrapped {@code data} node of an {@code ApiResponse}, or a bare JSON node. */
    private JsonNode fetchData(String url, String authorization) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(properties.getHealthTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET();
            if (authorization != null && !authorization.isBlank()) {
                builder.header("Authorization", authorization);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.debug("aggregate call {} returned HTTP {}", url, response.statusCode());
                return null;
            }
            JsonNode root = safeRead(response.body());
            if (root == null || !root.isObject()) {
                return root;
            }
            if (root.has("code") && root.has("data")) {
                JsonNode data = root.get("data");
                return data == null || data.isNull() ? null : data;
            }
            return root;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception ex) {
            log.debug("aggregate call {} failed: {}", url, ex.toString());
            return null;
        }
    }

    private long countArray(String url, String authorization) {
        JsonNode node = fetchData(url, authorization);
        if (node != null && node.isArray()) {
            return node.size();
        }
        return -1;
    }

    private JsonNode safeRead(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            return null;
        }
    }

    private static long longOr(JsonNode node, String field, long fallback) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return fallback;
        }
        JsonNode value = node.get(field);
        return value.isNumber() ? value.asLong() : fallback;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
