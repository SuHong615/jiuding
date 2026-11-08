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
package com.suhong.jiuding.gateway.oidc;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.config.OidcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * OIDC authorization-code + PKCE flow (docs/16 §2).
 *
 * <p>{@code /api/auth/oidc/authorize} builds a signed stateless state and an S256 PKCE
 * challenge; {@code /api/auth/oidc/callback} verifies the state, exchanges the code, reads
 * the userinfo claims, maps roles ({@code role-claim} → known roles, otherwise
 * {@code default-role}) and signs the same JWT the password login issues. The final step is
 * a 302 to the frontend with the token in the URL fragment (never on the server side).
 */
@Service
public class OidcService {

    private static final Logger log = LoggerFactory.getLogger(OidcService.class);
    private static final Set<String> KNOWN_ROLES = Set.of(
            "PLATFORM_ADMIN", "APPROVER", "AUDITOR", "TENANT_ADMIN", "ORG_ADMIN", "MEMBER");

    private final OidcProperties properties;
    private final OidcStateService stateService;
    private final OidcDiscoveryClient discoveryClient;
    private final JwtService jwtService;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public OidcService(OidcProperties properties, OidcStateService stateService,
                       OidcDiscoveryClient discoveryClient, JwtService jwtService,
                       HttpClient httpClient, ObjectMapper objectMapper) {
        this.properties = properties;
        this.stateService = stateService;
        this.discoveryClient = discoveryClient;
        this.jwtService = jwtService;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> status() {
        boolean enabled = properties.isUsable();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("providerName", properties.getProviderName());
        if (enabled) {
            body.put("loginUrl", "/api/auth/oidc/authorize");
            body.put("frontendRedirect", properties.getFrontendRedirect());
        }
        return body;
    }

    /** Builds the provider authorization URL (state + PKCE S256 challenge). */
    public String buildAuthorizationUrl() {
        OidcDiscoveryClient.Endpoints endpoints = discoveryClient.endpoints();
        OidcStateService.AuthorizationRequest authorization = stateService.newAuthorizationRequest();
        String separator = endpoints.authorizationEndpoint().contains("?") ? "&" : "?";
        StringBuilder url = new StringBuilder(endpoints.authorizationEndpoint()).append(separator);
        url.append("response_type=code");
        url.append("&client_id=").append(encode(properties.getClientId()));
        url.append("&redirect_uri=").append(encode(properties.getRedirectUri()));
        url.append("&scope=").append(encode(String.join(" ", scopes())));
        url.append("&state=").append(encode(authorization.state()));
        url.append("&code_challenge=").append(encode(authorization.codeChallenge()));
        url.append("&code_challenge_method=S256");
        return url.toString();
    }

    /**
     * Completes the callback and returns the frontend redirect URL (token in the fragment).
     *
     * @throws OidcException with a safe public {@code error} code on any failure
     */
    public String handleCallback(String code, String state) {
        OidcStateService.PkceSession session = stateService.consume(state)
                .orElseThrow(() -> new OidcException("invalid_state", "OIDC state is invalid or expired"));
        if (code == null || code.isBlank()) {
            throw new OidcException("invalid_request", "OIDC callback is missing the code parameter");
        }
        OidcDiscoveryClient.Endpoints endpoints = discoveryClient.endpoints();
        String accessToken = exchangeCode(endpoints, code, session.codeVerifier());
        Map<String, Object> claims = fetchUserInfo(endpoints, accessToken);
        Principal principal = toPrincipal(claims);
        String token = jwtService.issue(principal);
        StringBuilder redirect = new StringBuilder(frontendRedirect());
        redirect.append("#token=").append(encode(token));
        redirect.append("&username=").append(encode(principal.username() == null ? "" : principal.username()));
        return redirect.toString();
    }

    /** Redirect target for failures: only a stable error code is exposed (no details). */
    public String errorRedirect(String errorCode) {
        return frontendRedirect() + "#error=" + encode(errorCode);
    }

    private String exchangeCode(OidcDiscoveryClient.Endpoints endpoints, String code, String codeVerifier) {
        String form = "grant_type=authorization_code"
                + "&code=" + encode(code)
                + "&redirect_uri=" + encode(properties.getRedirectUri())
                + "&client_id=" + encode(properties.getClientId())
                + "&client_secret=" + encode(properties.getClientSecret())
                + "&code_verifier=" + encode(codeVerifier);
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.tokenEndpoint()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("OIDC token endpoint returned HTTP {}", response.statusCode());
                throw new OidcException("oidc_token_failed", "OIDC token exchange failed");
            }
            JsonNode root = objectMapper.readTree(response.body());
            String accessToken = root == null ? null : root.path("access_token").asText(null);
            if (accessToken == null || accessToken.isBlank()) {
                throw new OidcException("oidc_token_failed", "OIDC token response has no access_token");
            }
            return accessToken;
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("oidc_token_failed", "OIDC token exchange was interrupted", ex);
        } catch (Exception ex) {
            log.warn("OIDC token exchange failed: {}", ex.toString());
            throw new OidcException("oidc_token_failed", "OIDC token exchange failed", ex);
        }
    }

    private Map<String, Object> fetchUserInfo(OidcDiscoveryClient.Endpoints endpoints, String accessToken) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoints.userinfoEndpoint()))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("OIDC userinfo endpoint returned HTTP {}", response.statusCode());
                throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request failed");
            }
            return objectMapper.readValue(response.body(), new TypeReference<Map<String, Object>>() {
            });
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request was interrupted", ex);
        } catch (Exception ex) {
            log.warn("OIDC userinfo request failed: {}", ex.toString());
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo request failed", ex);
        }
    }

    private Principal toPrincipal(Map<String, Object> claims) {
        String userId = firstText(claims, "sub", "preferred_username", "email");
        if (userId == null) {
            throw new OidcException("oidc_userinfo_failed", "OIDC userinfo has no subject");
        }
        String username = firstText(claims, "name", "preferred_username", "email");
        if (username == null) {
            username = userId;
        }
        Set<String> roles = mapRoles(claims);
        return new Principal(userId, username, null, roles, "JWT", null);
    }

    private Set<String> mapRoles(Map<String, Object> claims) {
        Set<String> mapped = new LinkedHashSet<>();
        Object raw = claims.get(properties.getRoleClaim());
        for (String candidate : roleValues(raw)) {
            String normalized = candidate.trim().toUpperCase(Locale.ROOT);
            if (KNOWN_ROLES.contains(normalized)) {
                mapped.add(normalized);
            }
        }
        if (mapped.isEmpty()) {
            String fallback = properties.getDefaultRole();
            if (fallback != null && !fallback.isBlank()) {
                mapped.add(fallback.trim().toUpperCase(Locale.ROOT));
            }
        }
        return mapped;
    }

    private List<String> roleValues(Object raw) {
        List<String> values = new ArrayList<>();
        if (raw == null) {
            return values;
        }
        if (raw instanceof Iterable<?> iterable) {
            for (Object item : iterable) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        if (raw.getClass().isArray()) {
            Object[] array = (Object[]) raw;
            for (Object item : array) {
                if (item != null) {
                    values.add(String.valueOf(item));
                }
            }
            return values;
        }
        String text = String.valueOf(raw);
        if (text.contains(" ")) {
            for (String part : text.split("\\s+")) {
                values.add(part);
            }
        } else if (text.contains(",")) {
            for (String part : text.split(",")) {
                values.add(part);
            }
        } else if (!text.isBlank()) {
            values.add(text);
        }
        return values;
    }

    private List<String> scopes() {
        List<String> scopes = new ArrayList<>();
        for (String scope : properties.getScopes().split("[,\\s]+")) {
            if (!scope.isBlank()) {
                scopes.add(scope.trim());
            }
        }
        return scopes;
    }

    private String frontendRedirect() {
        return properties.getFrontendRedirect();
    }

    private static String firstText(Map<String, Object> claims, String... keys) {
        for (String key : keys) {
            Object value = claims.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
