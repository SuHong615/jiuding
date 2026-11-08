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
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.gateway.config.GatewayProperties;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Client for the tenant-service internal identity endpoints (docs/13 §2.2, docs/20 §3):
 * <ul>
 *   <li>{@code POST /internal/auth/authenticate} – password login / tenant selection</li>
 *   <li>{@code POST /internal/auth/authenticate-mfa} – MFA second step</li>
 *   <li>{@code GET /internal/auth/tenants} – tenants switchable by the current identity</li>
 *   <li>{@code POST /internal/auth/switch-tenant} – re-issue a target-tenant principal</li>
 * </ul>
 *
 * <p>Credential failures are normalised so the gateway always answers a unified
 * {@code 401 invalid credentials}; a transport failure or a 5xx from tenant-service is
 * surfaced as {@link TenantAuthUnavailableException} so the gateway can answer 503
 * without pretending the password was wrong.
 */
@Component
public class TenantAuthClient {

    private static final Logger log = LoggerFactory.getLogger(TenantAuthClient.class);
    private static final String AUTHENTICATE_PATH = "/internal/auth/authenticate";
    private static final String AUTHENTICATE_MFA_PATH = "/internal/auth/authenticate-mfa";
    private static final String TENANTS_PATH = "/internal/auth/tenants";
    private static final String SWITCH_TENANT_PATH = "/internal/auth/switch-tenant";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties properties;
    private final SecurityProperties securityProperties;

    public TenantAuthClient(HttpClient httpClient, ObjectMapper objectMapper,
                            GatewayProperties properties, SecurityProperties securityProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.securityProperties = securityProperties;
    }

    /**
     * @param tenantCode tenant code supplied by the caller; may be blank for the
     *                   username-only resolution (docs/20 §3 may return a tenant selection)
     * @param clientIp   resolved client IP forwarded as {@code X-Forwarded-For} so
     *                   tenant-service can record the login event (docs/14 §4)
     * @param userAgent  truncated User-Agent forwarded for the same audit trail
     * @throws BizException                      unified 401 for invalid credentials
     * @throws TenantAuthUnavailableException    downstream unreachable / 5xx
     */
    public TenantAuthResult authenticate(String tenantCode, String username, String password,
                                         String clientIp, String userAgent) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("tenantCode", tenantCode == null ? "" : tenantCode);
        body.put("username", username);
        body.put("password", password);

        HttpResponse<String> response = sendJson(AUTHENTICATE_PATH, body, clientIp, userAgent);
        int status = response.statusCode();
        if (status / 100 == 2) {
            return parseResult(response.body());
        }
        if (status / 100 == 4) {
            // Tenant-service deliberately does not distinguish unknown user / wrong password /
            // wrong tenant; the gateway keeps that single message so callers cannot enumerate.
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
        log.warn("tenant-service internal authentication failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("tenant authentication service unavailable");
    }

    /**
     * Second MFA step (docs/16 §3): exchanges the short-lived challenge token plus a
     * TOTP/recovery code for the full principal payload.
     */
    public TenantAuthResult authenticateMfa(String challengeToken, String code) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("challengeToken", challengeToken);
        body.put("code", code);

        HttpResponse<String> response = sendJson(AUTHENTICATE_MFA_PATH, body, null, null);
        int status = response.statusCode();
        if (status / 100 == 2) {
            return parseResult(response.body());
        }
        if (status / 100 == 4) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid mfa code");
        }
        log.warn("tenant-service MFA authentication failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("tenant authentication service unavailable");
    }

    /**
     * Tenants that the current identity can switch to (same username + identical password
     * hash across ACTIVE tenant memberships, docs/20 §3).
     */
    public List<TenantSummary> listSwitchableTenants(String accountId, String username,
                                                     String currentTenantId) {
        StringBuilder url = new StringBuilder(trimSlash(properties.getTenantServiceUrl())).append(TENANTS_PATH);
        boolean first = true;
        first = appendQuery(url, first, "accountId", accountId);
        first = appendQuery(url, first, "username", username);
        first = appendQuery(url, first, "currentTenantId", currentTenantId);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url.toString()))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Accept", "application/json")
                .header("X-Internal-Token", securityProperties.getInternalToken())
                .GET()
                .build();
        HttpResponse<String> response = send(request, "list switchable tenants");
        int status = response.statusCode();
        if (status / 100 == 2) {
            return parseTenantList(response.body());
        }
        if (status / 100 == 4) {
            if (status == 403) {
                throw BizException.forbidden("not allowed to list switchable tenants");
            }
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
        log.warn("tenant-service switchable tenant list failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("tenant authentication service unavailable");
    }

    /**
     * Re-issues the principal of the current identity inside another tenant. The target
     * membership is validated by tenant-service (same username + identical password hash).
     */
    public TenantAuthResult switchTenant(String accountId, String username, String tenantCode,
                                         String currentTenantId, String password) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("tenantCode", tenantCode);
        if (password == null || password.isEmpty()) {
            // v10.1 password-less contract: tenant-service validates the target membership
            // against the current account's username + identical password hash.
            body.put("currentAccountId", accountId);
            if (currentTenantId != null && !currentTenantId.isBlank()) {
                body.put("currentTenantId", currentTenantId);
            }
        } else {
            // Legacy v10 contract {username,tenantCode,password} (kept for compatibility).
            body.put("username", username);
            body.put("password", password);
            if (accountId != null && !accountId.isBlank()) {
                body.put("currentAccountId", accountId);
            }
        }

        HttpResponse<String> response = sendJson(SWITCH_TENANT_PATH, body, null, null);
        int status = response.statusCode();
        if (status / 100 == 2) {
            return parseResult(response.body());
        }
        if (status == 403) {
            throw BizException.forbidden("tenant switch is not allowed");
        }
        if (status / 100 == 4) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
        log.warn("tenant-service tenant switch failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("tenant authentication service unavailable");
    }

    private HttpResponse<String> sendJson(String path, ObjectNode body, String clientIp, String userAgent) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(trimSlash(properties.getTenantServiceUrl())
                        + path))
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
        return send(request, path);
    }

    private HttpResponse<String> send(HttpRequest request, String action) {
        try {
            return httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TenantAuthUnavailableException("tenant " + action + " was interrupted", ex);
        } catch (Exception ex) {
            log.warn("tenant-service {} unreachable: {}", action, ex.toString());
            throw new TenantAuthUnavailableException("tenant authentication service unavailable", ex);
        }
    }

    /**
     * Current tenant summary used as a graceful fallback for {@code /api/auth/tenants}
     * (tenant-service v10 does not expose a candidate-list endpoint yet). Returns null on
     * any failure.
     */
    public TenantSummary currentTenantSummary(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        String url = trimSlash(properties.getTenantServiceUrl()) + "/internal/tenants/"
                + URLEncoder.encode(tenantId, StandardCharsets.UTF_8);
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Accept", "application/json")
                .header("X-Internal-Token", securityProperties.getInternalToken())
                .GET()
                .build();
        try {
            HttpResponse<String> response = send(request, "current tenant summary");
            if (response.statusCode() / 100 != 2) {
                return null;
            }
            JsonNode root = objectMapper.readTree(response.body());
            JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
            if (data == null || !data.isObject()) {
                return null;
            }
            String id = text(data, "id", "tenantId", "tenant_id");
            return new TenantSummary(id == null ? tenantId : id,
                    text(data, "tenantCode", "tenant_code", "code"),
                    text(data, "name", "tenantName", "tenant_name"));
        } catch (Exception ex) {
            log.debug("current tenant summary lookup failed for {}: {}", tenantId, ex.toString());
            return null;
        }
    }

    private TenantAuthResult parseResult(String payload) {
        JsonNode root;
        try {
            root = objectMapper.readTree(payload);
        } catch (Exception ex) {
            log.warn("tenant-service internal authentication returned non-JSON payload: {}", ex.getMessage());
            throw new TenantAuthUnavailableException("tenant authentication service returned an invalid payload", ex);
        }
        JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
        if (data == null || !data.isObject()) {
            throw new TenantAuthUnavailableException("tenant authentication service returned an empty payload");
        }
        return new TenantAuthResult(
                text(data, "accountId", "userId", "id"),
                text(data, "tenantId"),
                text(data, "tenantCode"),
                text(data, "username", "userName"),
                text(data, "displayName", "display_name", "name"),
                text(data, "role"),
                text(data, "orgUnitId", "org_unit_id"),
                text(data, "orgUnitPath", "org_unit_path"),
                bool(data, "mustChangePassword", "must_change_password"),
                bool(data, "mfaRequired", "mfa_required"),
                text(data, "challengeToken", "challenge_token"),
                bool(data, "tenantSelectionRequired", "tenant_selection_required"),
                parseTenants(data));
    }

    private List<TenantSummary> parseTenants(JsonNode data) {
        JsonNode node = data.get("tenants");
        if (node == null) {
            node = data.get("items");
        }
        if (node == null || !node.isArray()) {
            return null;
        }
        List<TenantSummary> tenants = new ArrayList<>();
        for (JsonNode item : node) {
            String tenantId = text(item, "tenantId", "tenant_id", "id");
            String tenantCode = text(item, "tenantCode", "tenant_code", "code");
            String name = text(item, "name", "tenantName", "tenant_name");
            if (tenantId != null || tenantCode != null) {
                tenants.add(new TenantSummary(tenantId, tenantCode, name));
            }
        }
        return tenants;
    }

    private List<TenantSummary> parseTenantList(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
            if (data == null) {
                return List.of();
            }
            if (data.isArray()) {
                List<TenantSummary> tenants = new ArrayList<>();
                for (JsonNode item : data) {
                    tenants.add(toTenantSummary(item));
                }
                return tenants;
            }
            List<TenantSummary> nested = parseTenants(data);
            return nested == null ? List.of() : nested;
        } catch (Exception ex) {
            log.warn("switchable tenant payload could not be parsed: {}", ex.getMessage());
            throw new TenantAuthUnavailableException("tenant authentication service returned an invalid payload", ex);
        }
    }

    private TenantSummary toTenantSummary(JsonNode item) {
        return new TenantSummary(
                text(item, "tenantId", "tenant_id", "id"),
                text(item, "tenantCode", "tenant_code", "code"),
                text(item, "name", "tenantName", "tenant_name"));
    }

    private static Boolean bool(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull()) {
                return value.asBoolean();
            }
        }
        return null;
    }

    private static String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.get(field);
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                return value.asText();
            }
        }
        return null;
    }

    private static boolean appendQuery(StringBuilder url, boolean first, String name, String value) {
        if (value == null || value.isBlank()) {
            return first;
        }
        url.append(first ? '?' : '&')
                .append(name).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
        return false;
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
