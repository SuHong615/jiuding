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
import com.suhong.jiuding.gateway.config.OidcProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolves OIDC provider endpoints from
 * {@code ${issuer}/.well-known/openid-configuration} with a 5-minute in-memory cache keyed
 * by issuer (docs/16 §2, docs/20 §3 — one cache entry per dynamic SSO provider).
 * Explicitly configured endpoints take precedence over discovery.
 */
@Service
public class OidcDiscoveryClient {

    private static final Logger log = LoggerFactory.getLogger(OidcDiscoveryClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final OidcProperties properties;
    private final Map<String, CachedEndpoints> cache = new ConcurrentHashMap<>();

    public OidcDiscoveryClient(HttpClient httpClient, ObjectMapper objectMapper, OidcProperties properties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /** Endpoints of the legacy env-configured provider ({@code tenancy.auth.oidc.*}). */
    public Endpoints endpoints() {
        return endpoints(properties.getIssuer(), properties.getAuthorizationEndpoint(),
                properties.getTokenEndpoint(), properties.getUserinfoEndpoint());
    }

    /**
     * @param issuer      provider issuer base URL
     * @param auth        explicit authorization endpoint (optional)
     * @param token       explicit token endpoint (optional)
     * @param userinfo    explicit userinfo endpoint (optional)
     */
    public Endpoints endpoints(String issuer, String auth, String token, String userinfo) {
        if (hasAllExplicitEndpoints(auth, token, userinfo)) {
            return new Endpoints(auth, token, userinfo);
        }
        if (issuer == null || issuer.isBlank()) {
            throw new OidcException("oidc_discovery_failed", "OIDC issuer is not configured");
        }
        String key = trimSlash(issuer);
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1L, properties.getDiscoveryCacheSeconds()) * 1000L;
        CachedEndpoints current = cache.get(key);
        if (current != null && now - current.fetchedAt() < ttlMs) {
            return current.endpoints();
        }
        synchronized (this) {
            current = cache.get(key);
            now = System.currentTimeMillis();
            if (current != null && now - current.fetchedAt() < ttlMs) {
                return current.endpoints();
            }
            Endpoints fetched = fetch(issuer);
            cache.put(key, new CachedEndpoints(fetched, now));
            return fetched;
        }
    }

    /** Visible for tests: clears the discovery cache. */
    public void invalidateCache() {
        cache.clear();
    }

    private Endpoints fetch(String issuer) {
        String discoveryUrl = trimSlash(issuer) + "/.well-known/openid-configuration";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(discoveryUrl))
                    .timeout(Duration.ofMillis(properties.getRequestTimeoutMs()))
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                throw new OidcException("oidc_discovery_failed",
                        "discovery document returned HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String authorizationEndpoint = text(root, "authorization_endpoint");
            String tokenEndpoint = text(root, "token_endpoint");
            String userinfoEndpoint = text(root, "userinfo_endpoint");
            if (authorizationEndpoint == null || tokenEndpoint == null || userinfoEndpoint == null) {
                throw new OidcException("oidc_discovery_failed",
                        "discovery document is missing required endpoints");
            }
            return new Endpoints(authorizationEndpoint, tokenEndpoint, userinfoEndpoint);
        } catch (OidcException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new OidcException("oidc_discovery_failed", "OIDC discovery was interrupted", ex);
        } catch (Exception ex) {
            log.warn("OIDC discovery failed for {}: {}", discoveryUrl, ex.toString());
            throw new OidcException("oidc_discovery_failed", "OIDC discovery failed", ex);
        }
    }

    private static boolean hasAllExplicitEndpoints(String auth, String token, String userinfo) {
        return hasText(auth) && hasText(token) && hasText(userinfo);
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value == null || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** Endpoints needed for the authorization-code flow. */
    public record Endpoints(String authorizationEndpoint, String tokenEndpoint, String userinfoEndpoint) {
    }

    private record CachedEndpoints(Endpoints endpoints, long fetchedAt) {
    }
}
