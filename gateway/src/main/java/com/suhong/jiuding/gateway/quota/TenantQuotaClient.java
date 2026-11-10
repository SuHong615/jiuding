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
package com.suhong.jiuding.gateway.quota;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.gateway.config.GatewayProperties;
import com.suhong.jiuding.gateway.config.TenantQuotaProperties;
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
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reads the tenant API-RPM limit from tenant-service
 * {@code GET /internal/tenants/{id}/quota} (X-Internal-Token, docs/17 §3/§5).
 *
 * <p>Values are cached for {@code tenancy.gateway.tenant-quota.cache-seconds} (60s). Any
 * failure (network, non-2xx including 401, malformed body) degrades to "unlimited" and logs
 * a WARN — quota enforcement must never break tenant traffic because the quota endpoint is
 * down.
 */
@Component
public class TenantQuotaClient {

    private static final Logger log = LoggerFactory.getLogger(TenantQuotaClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final GatewayProperties gatewayProperties;
    private final TenantQuotaProperties quotaProperties;
    private final SecurityProperties securityProperties;
    private final ConcurrentHashMap<String, CachedLimit> cache = new ConcurrentHashMap<>();

    public TenantQuotaClient(HttpClient httpClient, ObjectMapper objectMapper,
                             GatewayProperties gatewayProperties, TenantQuotaProperties quotaProperties,
                             SecurityProperties securityProperties) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.gatewayProperties = gatewayProperties;
        this.quotaProperties = quotaProperties;
        this.securityProperties = securityProperties;
    }

    /**
     * @return the tenant's API RPM limit, or {@code null} when unlimited / unavailable
     */
    public Long limitFor(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return null;
        }
        long now = System.currentTimeMillis();
        long ttlMs = Math.max(1L, quotaProperties.getCacheSeconds()) * 1000L;
        CachedLimit cached = cache.get(tenantId);
        if (cached != null && cached.expiresAt() > now) {
            return cached.limit();
        }
        Long limit = fetchLimit(tenantId);
        cache.put(tenantId, new CachedLimit(limit, now + ttlMs));
        return limit;
    }

    /** Visible for tests. */
    public void clearCache() {
        cache.clear();
    }

    private Long fetchLimit(String tenantId) {
        String url = trimSlash(gatewayProperties.getTenantServiceUrl())
                + "/internal/tenants/" + encodePath(tenantId) + "/quota";
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofMillis(quotaProperties.getRequestTimeoutMs()))
                    .header("Accept", "application/json")
                    .header("X-Internal-Token", securityProperties.getInternalToken())
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() / 100 != 2) {
                log.warn("tenant quota lookup for {} returned HTTP {}; degrading to unlimited",
                        tenantId, response.statusCode());
                return null;
            }
            return parseLimit(response.body());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("tenant quota lookup for {} was interrupted; degrading to unlimited", tenantId);
            return null;
        } catch (Exception ex) {
            log.warn("tenant quota lookup for {} failed ({}); degrading to unlimited",
                    tenantId, ex.toString());
            return null;
        }
    }

    private Long parseLimit(String payload) {
        try {
            JsonNode root = objectMapper.readTree(payload);
            JsonNode data = root != null && root.isObject() && root.has("data") ? root.get("data") : root;
            if (data == null || !data.isObject()) {
                return null;
            }
            JsonNode apiRpm = data.get("apiRpm");
            if (apiRpm == null || apiRpm.isNull()) {
                return null;
            }
            JsonNode limit = apiRpm.isObject() ? apiRpm.get("limit") : apiRpm;
            if (limit == null || limit.isNull() || !limit.isNumber()) {
                return null;
            }
            return limit.asLong();
        } catch (Exception ex) {
            log.warn("tenant quota payload could not be parsed; degrading to unlimited: {}", ex.getMessage());
            return null;
        }
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String encodePath(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private record CachedLimit(Long limit, long expiresAt) {
    }
}
