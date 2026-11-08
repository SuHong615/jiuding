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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.gateway.config.GatewayProperties;
import com.suhong.jiuding.gateway.config.OidcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Loads the enabled dynamic SSO providers from tenant-service
 * {@code GET /internal/sso-providers/enabled} (docs/20 §2/§3) with a
 * {@code tenancy.auth.oidc.providers-cache-seconds} (60s by default) cache.
 *
 * <p>The list is the authority for {@code /api/auth/oidc/providers} and for the
 * per-provider authorize/callback flow. Any failure degrades to "no dynamic providers"
 * (logged as WARN) so the legacy env-configured OIDC provider keeps working.
 */
@Component
public class SsoProviderClient {

    private static final Logger log = LoggerFactory.getLogger(SsoProviderClient.class);
    private static final String ENABLED_PATH = "/internal/sso-providers/enabled";

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties gatewayProperties;
    private final OidcProperties oidcProperties;
    private final SecurityProperties securityProperties;
    private final ConcurrentHashMap<String, CachedProviders> cache = new ConcurrentHashMap<>();

    public SsoProviderClient(HttpClient httpClient, ObjectMapper objectMapper,
                             GatewayProperties gatewayProperties, OidcProperties oidcProperties,
                             SecurityProperties securityProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.gatewayProperties = gatewayProperties;
        this.oidcProperties = oidcProperties;
        this.securityProperties = securityProperties;
    }

    /** Enabled providers (cached); empty when the lookup fails or nothing is configured. */
    public List<SsoProviderConfig> enabled() {
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1L, oidcProperties.getProvidersCacheSeconds()) * 1000L;
        CachedProviders cached = cache.get(ENABLED_PATH);
        if (cached != null && cached.expiresAt() > now) {
            return cached.providers();
        }
        List<SsoProviderConfig> providers = fetch();
        cache.put(ENABLED_PATH, new CachedProviders(List.copyOf(providers), now + ttlMs));
        return providers;
    }

    public Optional<SsoProviderConfig> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        for (SsoProviderConfig provider : enabled()) {
            if (code.equalsIgnoreCase(provider.code())) {
                return Optional.of(provider);
            }
        }
        return Optional.empty();
    }

    /** Visible for tests. */
    public void clearCache() {
        cache.clear();
    }

    private List<SsoProviderConfig> fetch() {
        String url = trimSlash(gatewayProperties.getTenantServiceUrl()) + ENABLED_PATH;
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(oidcProperties.getRequestTimeoutMs()))
                    .header("Accept", "application/json")
                    .header("X-Internal-Token", securityProperties.getInternalToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("enabled SSO provider lookup returned HTTP {}; using no dynamic providers",
                        response.statusCode());
                return List.of();
            }
            return parse(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("enabled SSO provider lookup was interrupted; using no dynamic providers");
            return List.of();
        } catch (Exception ex) {
            log.warn("enabled SSO provider lookup failed ({}); using no dynamic providers", ex.toString());
            return List.of();
        }
    }

    private List<SsoProviderConfig> parse(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
            JsonNode array = data;
            if (data != null && data.isObject()) {
                array = data.has("providers") ? data.get("providers") : data.get("items");
            }
            if (array == null || !array.isArray()) {
                return List.of();
            }
            List<SsoProviderConfig> providers = new ArrayList<>();
            for (JsonNode node : array) {
                SsoProviderConfig config = toProvider(node);
                if (config != null && config.isUsable()) {
                    providers.add(config);
                }
            }
            return providers;
        } catch (Exception ex) {
            log.warn("enabled SSO provider payload could not be parsed: {}", ex.getMessage());
            return List.of();
        }
    }

    private SsoProviderConfig toProvider(JsonNode node) {
        if (node == null || !node.isObject()) {
            return null;
        }
        return new SsoProviderConfig(
                text(node, "code", "providerCode", "provider_code"),
                text(node, "name", "providerName", "provider_name"),
                text(node, "issuer"),
                text(node, "clientId", "client_id"),
                text(node, "clientSecret", "client_secret"),
                text(node, "scopes", "scope"),
                text(node, "redirectUri", "redirect_uri"),
                text(node, "frontendRedirect", "frontend_redirect"),
                text(node, "roleClaim", "role_claim"),
                text(node, "defaultRole", "default_role"));
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

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record CachedProviders(List<SsoProviderConfig> providers, long expiresAt) {
    }
}
