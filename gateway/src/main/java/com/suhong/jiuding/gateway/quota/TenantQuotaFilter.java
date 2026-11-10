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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.config.TenantQuotaProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * Tenant API-RPM enforcement for proxied {@code /api/**} calls (docs/17 §5).
 *
 * <p>Only requests authenticated by a JWT that carries a tenant id ({@code tid}) are counted;
 * platform administrators / API keys without a tenant scope are never throttled. The limit is
 * resolved through {@link TenantQuotaClient} (60s cache, failures degrade to unlimited) and
 * enforced with a 60s sliding window keyed by tenant id. Over-limit requests get
 * {@code HTTP 429 {code:42901, message:"tenant api quota exceeded"}}.
 *
 * <p>Local gateway endpoints ({@code /api/auth/**}, {@code /api/platform/overview|health}) are
 * excluded — they are not proxied tenant traffic.
 */
public class TenantQuotaFilter extends OncePerRequestFilter {

    public static final int FILTER_ORDER = -50;
    public static final int QUOTA_EXCEEDED_CODE = 42901;
    public static final String QUOTA_EXCEEDED_MESSAGE = "tenant api quota exceeded";

    private final TenantQuotaProperties properties;
    private final TenantQuotaClient quotaClient;
    private final TenantQuotaLimiter quotaLimiter;
    private final ObjectMapper objectMapper;

    public TenantQuotaFilter(TenantQuotaProperties properties, TenantQuotaClient quotaClient,
                             TenantQuotaLimiter quotaLimiter, ObjectMapper objectMapper) {
        this.properties = properties;
        this.quotaClient = quotaClient;
        this.quotaLimiter = quotaLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        if (!properties.isEnabled()) {
            return true;
        }
        String path = request.getRequestURI();
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (path.startsWith("/api/auth/")) {
            return true;
        }
        return "/api/platform/overview".equals(path) || "/api/platform/health".equals(path);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        Principal principal = AuthContext.get();
        String tenantId = principal == null ? null : principal.tenantId();
        if (tenantId == null || tenantId.isBlank()) {
            chain.doFilter(request, response);
            return;
        }

        Long limit = quotaClient.limitFor(tenantId);
        if (limit == null) {
            chain.doFilter(request, response);
            return;
        }
        if (!quotaLimiter.tryAcquire(tenantId, limit)) {
            writeQuotaExceeded(response);
            return;
        }
        chain.doFilter(request, response);
    }

    private void writeQuotaExceeded(HttpServletResponse response) throws IOException {
        response.setStatus(429);
        response.setContentType("application/json;charset=UTF-8");
        byte[] body = objectMapper.writeValueAsString(
                        ApiResponse.error(QUOTA_EXCEEDED_CODE, QUOTA_EXCEEDED_MESSAGE, MDC.get("requestId")))
                .getBytes(StandardCharsets.UTF_8);
        response.getOutputStream().write(body);
        response.getOutputStream().flush();
    }
}
