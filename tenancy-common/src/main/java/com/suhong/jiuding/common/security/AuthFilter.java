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
package com.suhong.jiuding.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.tenant.TenantContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Single authentication entry point for every service.
 *
 * <p>Two credential types are supported:
 * <ul>
 *   <li>{@code Authorization: Bearer <jwt>} for humans / CLI</li>
 *   <li>{@code X-API-Key: <key>} for MCP clients and machine-to-machine calls</li>
 * </ul>
 * On success the principal and — when resolvable — the tenant scope are bound to
 * the request thread. Downstream code fails closed via {@code TenantContext.require()}.
 */
public class AuthFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();

    private final SecurityProperties properties;
    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public AuthFilter(SecurityProperties properties, JwtService jwtService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        List<String> whitelist = properties.getWhitelist();
        return whitelist != null && whitelist.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String requestId = request.getHeader(Headers.REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        MDC.put("requestId", requestId);
        response.setHeader(Headers.REQUEST_ID, requestId);

        try {
            Principal principal = authenticate(request);
            if (principal == null) {
                writeError(response, ErrorCode401.UNAUTHORIZED, "authentication required", requestId);
                return;
            }
            String tenantId = resolveTenant(request, principal);
            AuthContext.set(principal);
            TenantContext.set(new com.suhong.jiuding.common.tenant.TenantScope(
                    tenantId, principal.userId(), principal.roles() == null ? Set.of() : principal.roles(),
                    principal.orgUnitId()));
            response.setHeader(Headers.USER_ID, principal.userId());
            chain.doFilter(request, response);
        } catch (com.suhong.jiuding.common.error.BizException ex) {
            writeError(response, ex.errorCode().code(), ex.getMessage(), requestId);
        } finally {
            AuthContext.clear();
            TenantContext.clear();
            MDC.remove("requestId");
        }
    }

    private Principal authenticate(HttpServletRequest request) {
        // 1) Internal service-to-service calls: /internal/** + shared internal token.
        //    Kept inside this filter (instead of a whitelist) so internal endpoints are
        //    never accidentally left unauthenticated.
        if (request.getRequestURI().startsWith("/internal/")) {
            String internalToken = request.getHeader(Headers.INTERNAL_TOKEN);
            if (internalToken != null && !internalToken.isBlank()) {
                if (!constantTimeEquals(internalToken, properties.getInternalToken())) {
                    throw new com.suhong.jiuding.common.error.BizException(
                            com.suhong.jiuding.common.error.ErrorCode.UNAUTHORIZED, "invalid internal token");
                }
                return Principal.internalService();
            }
        }
        String apiKey = request.getHeader(Headers.API_KEY);
        if (apiKey != null && !apiKey.isBlank()) {
            List<String> keys = properties.getApiKeys();
            if (keys != null && keys.contains(apiKey)) {
                return Principal.apiKey("mcp:" + Integer.toHexString(apiKey.hashCode()));
            }
            throw new com.suhong.jiuding.common.error.BizException(
                    com.suhong.jiuding.common.error.ErrorCode.UNAUTHORIZED, "invalid api key");
        }
        String authorization = request.getHeader(Headers.AUTHORIZATION);
        if (authorization != null && authorization.startsWith(Headers.BEARER)) {
            return jwtService.parse(authorization.substring(Headers.BEARER.length()).trim());
        }
        return null;
    }

    private static boolean constantTimeEquals(String actual, String expected) {
        if (expected == null || actual == null) {
            return false;
        }
        return java.security.MessageDigest.isEqual(
                actual.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                expected.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String resolveTenant(HttpServletRequest request, Principal principal) {
        String headerTenant = request.getHeader(Headers.TENANT_ID);
        String tokenTenant = principal.tenantId();
        if (headerTenant != null && !headerTenant.isBlank()) {
            if (tokenTenant != null && !tokenTenant.isBlank()
                    && !tokenTenant.equals(headerTenant) && !principal.isPlatformAdmin()) {
                throw new com.suhong.jiuding.common.error.BizException(
                        com.suhong.jiuding.common.error.ErrorCode.FORBIDDEN, "tenant header does not match token");
            }
            return headerTenant;
        }
        return tokenTenant;
    }

    private void writeError(HttpServletResponse response, int code, String message, String requestId)
            throws IOException {
        response.setStatus(httpStatusFor(code));
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
                ApiResponse.error(code, message, requestId)));
    }

    private static int httpStatusFor(int code) {
        if (code >= 50000) {
            return 500;
        }
        return switch (code) {
            case 40100 -> 401;
            case 40300, 40301 -> 403;
            case 40400 -> 404;
            case 40900 -> 409;
            case 42900 -> 429;
            case 50200 -> 502;
            default -> 400;
        };
    }

    /** Local constant to avoid an extra import cycle in the filter. */
    private static final class ErrorCode401 {
        static final int UNAUTHORIZED = 40100;
    }
}
