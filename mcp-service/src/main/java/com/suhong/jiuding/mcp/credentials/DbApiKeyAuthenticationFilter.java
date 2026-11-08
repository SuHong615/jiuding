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
package com.suhong.jiuding.mcp.credentials;

import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.security.SecurityProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.Optional;
import java.util.Set;

/**
 * Bridges personal {@code mcp_api_keys} (docs/15 §4.2) into the shared JWT-based
 * {@code AuthFilter} of tenancy-common (which only knows the static env API keys).
 *
 * <p>For {@code /mcp} requests carrying an {@code X-API-Key}:
 * <ul>
 *   <li>static env keys are left untouched (backwards compatibility, audit prefix
 *       {@code static});</li>
 *   <li>DB keys are looked up by SHA-256 hash, validated (ACTIVE, not expired), marked as
 *       used, and replaced by a freshly minted service JWT carrying the key owner's
 *       user/tenant/roles — the request is wrapped so the downstream AuthFilter sees the
 *       bearer token instead of the raw key;</li>
 *   <li>unknown/revoked/expired keys fall through so the shared filter answers 401.</li>
 * </ul>
 */
public class DbApiKeyAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DbApiKeyAuthenticationFilter.class);
    public static final int FILTER_ORDER = -150;

    private final ApiKeyService apiKeyService;
    private final JwtService jwtService;
    private final SecurityProperties securityProperties;

    public DbApiKeyAuthenticationFilter(ApiKeyService apiKeyService, JwtService jwtService,
                                        SecurityProperties securityProperties) {
        this.apiKeyService = apiKeyService;
        this.jwtService = jwtService;
        this.securityProperties = securityProperties;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return !("/mcp".equals(path) || path.startsWith("/mcp/"));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String rawKey = request.getHeader("X-API-Key");
        if (rawKey == null || rawKey.isBlank()) {
            chain.doFilter(request, response);
            return;
        }
        if (isStaticKey(rawKey)) {
            request.setAttribute(McpAuthAttributes.STATIC_KEY, Boolean.TRUE);
            request.setAttribute(McpAuthAttributes.KEY_PREFIX, McpAuthAttributes.STATIC_PREFIX);
            request.setAttribute(McpAuthAttributes.OWNER_USERNAME, "static");
            chain.doFilter(request, response);
            return;
        }

        Optional<ApiKeyRow> key = apiKeyService.authenticate(rawKey);
        if (key.isEmpty()) {
            // Invalid/revoked/expired: let the shared AuthFilter produce the uniform 401.
            chain.doFilter(request, response);
            return;
        }

        ApiKeyRow row = key.get();
        try {
            apiKeyService.markUsed(row.id());
        } catch (RuntimeException ex) {
            log.warn("failed to update last_used_at for mcp key {}: {}", row.keyPrefix(), ex.getMessage());
        }
        Set<String> roles = ApiKeyService.parseRoles(row.roles());
        Principal principal = new Principal(row.ownerUserId(), row.ownerUsername(), row.tenantId(),
                roles, "JWT", null);
        String jwt = jwtService.issue(principal);
        request.setAttribute(McpAuthAttributes.KEY_ID, row.id());
        request.setAttribute(McpAuthAttributes.KEY_PREFIX, row.keyPrefix());
        request.setAttribute(McpAuthAttributes.OWNER_USERNAME, row.ownerUsername());
        request.setAttribute(McpAuthAttributes.TENANT_ID, row.tenantId());
        request.setAttribute(McpAuthAttributes.STATIC_KEY, Boolean.FALSE);
        chain.doFilter(new BearerTokenRequestWrapper(request, jwt), response);
    }

    private boolean isStaticKey(String rawKey) {
        return securityProperties.getApiKeys() != null && securityProperties.getApiKeys().contains(rawKey);
    }

    /** Wraps the request so the shared AuthFilter sees only the minted bearer token. */
    static final class BearerTokenRequestWrapper extends HttpServletRequestWrapper {

        private final String authorization;

        BearerTokenRequestWrapper(HttpServletRequest request, String jwt) {
            super(request);
            this.authorization = "Bearer " + jwt;
        }

        @Override
        public String getHeader(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return authorization;
            }
            if ("X-API-Key".equalsIgnoreCase(name)) {
                return null;
            }
            return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
            if ("Authorization".equalsIgnoreCase(name)) {
                return Collections.enumeration(java.util.List.of(authorization));
            }
            if ("X-API-Key".equalsIgnoreCase(name)) {
                return Collections.emptyEnumeration();
            }
            return super.getHeaders(name);
        }
    }
}
