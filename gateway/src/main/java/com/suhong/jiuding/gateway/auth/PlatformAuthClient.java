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
package com.suhong.jiuding.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.gateway.config.GatewayProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;

/**
 * Client for the tenant-service platform-account authentication endpoint
 * ({@code POST /internal/platform-users/authenticate}, docs/20 §3).
 *
 * <p>A 2xx response with an ACTIVE account returns the platform user; 401/403/other 4xx are
 * reported as "no such platform user" (so the gateway can fall back to the legacy preset
 * directory during migration), while transport failures / 5xx raise
 * {@link TenantAuthUnavailableException} and are handled by the caller's fallback.
 */
@Component
public class PlatformAuthClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformAuthClient.class);
    private static final String AUTHENTICATE_PATH = "/internal/platform-users/authenticate";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final SecurityProperties securityProperties;

    public PlatformAuthClient(HttpClient httpClient, ObjectMapper objectMapper,
                              GatewayProperties properties, SecurityProperties securityProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.securityProperties = securityProperties;
    }

    /** @return the ACTIVE platform user, or empty for invalid credentials */
    public Optional<PlatformUserResult> authenticate(String username, String password,
                                                     String clientIp, String userAgent) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("password", password == null ? "" : password);

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimSlash(properties.getTenantServiceUrl())
                        + AUTHENTICATE_PATH))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("X-Internal-Token", securityProperties.getInternalToken());
        if (clientIp != null && !clientIp.isBlank()) {
            builder.header("X-Forwarded-For", clientIp);
        }
        if (userAgent != null && !userAgent.isBlank()) {
            builder.header("User-Agent", userAgent);
        }
        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TenantAuthUnavailableException("platform authentication was interrupted", ex);
        } catch (Exception ex) {
            log.warn("tenant-service platform authentication unreachable: {}", ex.toString());
            throw new TenantAuthUnavailableException("platform authentication service unavailable", ex);
        }

        int status = response.statusCode();
        if (status / 100 == 2) {
            PlatformUserResult result = parseResult(response.body());
            return result != null && result.isActive() ? Optional.of(result) : Optional.empty();
        }
        if (status / 100 == 4) {
            return Optional.empty();
        }
        log.warn("tenant-service platform authentication failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("platform authentication service unavailable");
    }

    private PlatformUserResult parseResult(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
            if (data == null || !data.isObject()) {
                return null;
            }
            String userId = firstText(data, "userId", "id", "accountId");
            String username = firstText(data, "username", "userName", "name");
            String displayName = firstText(data, "displayName", "display_name", "name");
            String status = firstText(data, "status", "state");
            return new PlatformUserResult(userId, username, displayName, parseRoles(data), status);
        } catch (Exception ex) {
            log.warn("platform authentication payload could not be parsed: {}", ex.getMessage());
            return null;
        }
    }

    private static Set<String> parseRoles(JsonNode data) {
        Set<String> roles = new LinkedHashSet<>();
        JsonNode node = data.get("roles");
        if (node == null) {
            node = data.get("role");
        }
        if (node == null || node.isNull()) {
            return roles;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (!item.isNull() && !item.asText().isBlank()) {
                    roles.add(item.asText().trim());
                }
            }
            return roles;
        }
        for (String part : node.asText().split("[,\\s]+")) {
            if (!part.isBlank()) {
                roles.add(part.trim());
            }
        }
        return roles;
    }

    private static String firstText(JsonNode data, String... fields) {
        for (String field : fields) {
            JsonNode value = data.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
