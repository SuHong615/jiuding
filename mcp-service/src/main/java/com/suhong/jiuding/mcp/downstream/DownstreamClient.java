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
package com.suhong.jiuding.mcp.downstream;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.protocol.McpCallContext;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Thin JSON client for the downstream REST services.
 *
 * <p>Identity is forwarded from {@link McpCallContext}: the caller's
 * {@code Authorization} when present, otherwise {@code X-API-Key} (caller's key
 * or the configured service key). Responses are unwrapped from the shared
 * {@code ApiResponse} envelope; any non-2xx / {@code code != 0} becomes a
 * {@link DownstreamException} that tools convert to {@code isError:true}.
 */
@Component
public class DownstreamClient {

    private static final Logger log = LoggerFactory.getLogger(DownstreamClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;

    public DownstreamClient(HttpClient httpClient, ObjectMapper objectMapper, McpProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public JsonNode get(String service, String url) {
        return get(service, url, properties.getToolTimeoutMs());
    }

    public JsonNode get(String service, String url, int timeoutMs) {
        return execute(service, requestBuilder(url, timeoutMs, null).GET().build());
    }

    /** GET scoped to a tenant: forwards {@code X-Tenant-Id} (org/account/rating tools). */
    public JsonNode getForTenant(String service, String url, String tenantId) {
        return execute(service, requestBuilder(url, properties.getToolTimeoutMs(), tenantId).GET().build());
    }

    public JsonNode post(String service, String url, JsonNode body) {
        return post(service, url, body, properties.getToolTimeoutMs());
    }

    public JsonNode post(String service, String url, JsonNode body, int timeoutMs) {
        return execute(service, postRequest(requestBuilder(url, timeoutMs, null), body));
    }

    /** POST scoped to a tenant: forwards {@code X-Tenant-Id}. */
    public JsonNode postForTenant(String service, String url, JsonNode body, String tenantId) {
        return execute(service,
                postRequest(requestBuilder(url, properties.getToolTimeoutMs(), tenantId), body));
    }

    /** DELETE scoped to a tenant: forwards {@code X-Tenant-Id}. */
    public JsonNode deleteForTenant(String service, String url, String tenantId) {
        HttpRequest request = requestBuilder(url, properties.getToolTimeoutMs(), tenantId)
                .DELETE()
                .build();
        return execute(service, request);
    }

    private HttpRequest postRequest(HttpRequest.Builder builder, JsonNode body) {
        String payload = body == null ? "{}" : body.toString();
        return builder
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();
    }

    private HttpRequest.Builder requestBuilder(String url, int timeoutMs, String tenantId) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");

        McpCallContext.Call call = McpCallContext.get();
        String authorization = call == null ? null : call.authorization();
        if (authorization != null && !authorization.isBlank()) {
            builder.header(McpProtocol.HEADER_AUTHORIZATION, authorization);
        } else {
            String apiKey = call != null && call.apiKey() != null && !call.apiKey().isBlank()
                    ? call.apiKey()
                    : properties.getDownstreamApiKey();
            builder.header(McpProtocol.HEADER_API_KEY, apiKey);
        }
        if (call != null && call.requestId() != null && !call.requestId().isBlank()) {
            builder.header(McpProtocol.HEADER_REQUEST_ID, call.requestId());
        }
        if (tenantId != null && !tenantId.isBlank()) {
            builder.header(McpProtocol.HEADER_TENANT_ID, tenantId);
        }
        return builder;
    }

    private JsonNode execute(String service, HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new DownstreamException(ErrorCode.UPSTREAM_ERROR.code(), service + " call interrupted");
        } catch (Exception ex) {
            log.warn("downstream {} call failed: {} {}", service, request.uri(), ex.toString());
            throw new DownstreamException(ErrorCode.UPSTREAM_ERROR.code(),
                    service + " unavailable: " + ex.getClass().getSimpleName());
        }

        JsonNode root = parse(response.body());
        int status = response.statusCode();
        if (status / 100 != 2) {
            int code = root != null && root.has("code")
                    ? root.path("code").asInt(ErrorCode.UPSTREAM_ERROR.code())
                    : ErrorCode.UPSTREAM_ERROR.code();
            String message = root != null && root.hasNonNull("message")
                    ? root.path("message").asText()
                    : "downstream HTTP " + status;
            throw new DownstreamException(code, status, message);
        }
        if (root != null && root.isObject() && root.has("code")) {
            int code = root.path("code").asInt(0);
            if (code != 0) {
                throw new DownstreamException(code, status,
                        root.path("message").asText("downstream business error"));
            }
            JsonNode data = root.get("data");
            return data == null ? objectMapper.getNodeFactory().nullNode() : data;
        }
        return root == null ? objectMapper.getNodeFactory().nullNode() : root;
    }

    private JsonNode parse(String payload) {
        if (payload == null || payload.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.debug("downstream returned non-JSON payload: {}", ex.getMessage());
            return null;
        }
    }
}
