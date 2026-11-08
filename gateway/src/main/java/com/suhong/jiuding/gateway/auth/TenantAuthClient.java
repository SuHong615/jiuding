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
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * Client for the tenant-service internal account-authentication endpoint
 * ({@code POST /internal/auth/authenticate}, docs/13 §2.2).
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
     *                   username-only fallback (tenant-service then cannot resolve a tenant)
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
            throw new TenantAuthUnavailableException("tenant authentication was interrupted", ex);
        } catch (Exception ex) {
            log.warn("tenant-service internal authentication unreachable: {}", ex.toString());
            throw new TenantAuthUnavailableException("tenant authentication service unavailable", ex);
        }

        int status = response.statusCode();
        if (status / 100 == 2) {
            return parseResult(response.body());
        }
        if (status == 401 || status / 100 == 4) {
            // Tenant-service deliberately does not distinguish unknown user / wrong password /
            // wrong tenant; the gateway keeps that single message so callers cannot enumerate.
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
        log.warn("tenant-service internal authentication failed with HTTP {}", status);
        throw new TenantAuthUnavailableException("tenant authentication service unavailable");
    }

    /**
     * Second MFA step (docs/16 搂3): exchanges the short-lived challenge token plus a
     * TOTP/recovery code for the full principal payload.
     *
     * @throws BizException                   unified 401 for a wrong/expired/replayed code
     * @throws TenantAuthUnavailableException downstream unreachable / 5xx
     */
    public TenantAuthResult authenticateMfa(String challengeToken, String code) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("challengeToken", challengeToken);
        body.put("code", code);

        HttpRequest request = HttpRequest.newBuilder(URI.create(trimSlash(properties.getTenantServiceUrl())
                        + AUTHENTICATE_MFA_PATH))
                .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                .header("Content-Type", "application/json;charset=UTF-8")
                .header("Accept", "application/json")
                .header("X-Internal-Token", securityProperties.getInternalToken())
                .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response;
        try {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new TenantAuthUnavailableException("tenant MFA authentication was interrupted", ex);
        } catch (Exception ex) {
            log.warn("tenant-service MFA authentication unreachable: {}", ex.toString());
            throw new TenantAuthUnavailableException("tenant authentication service unavailable", ex);
        }

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
                text(data, "accountId"),
                text(data, "tenantId"),
                text(data, "tenantCode"),
                text(data, "username"),
                text(data, "displayName"),
                text(data, "role"),
                text(data, "orgUnitId"),
                text(data, "orgUnitPath"),
                data.hasNonNull("mustChangePassword") ? data.get("mustChangePassword").asBoolean() : Boolean.FALSE,
                data.hasNonNull("mfaRequired") ? data.get("mfaRequired").asBoolean() : null,
                text(data, "challengeToken"));
    }

    private static String text(JsonNode node, String field) {        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
