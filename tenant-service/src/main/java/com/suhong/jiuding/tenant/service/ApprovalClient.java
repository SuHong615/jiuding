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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.security.Headers;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.domain.RiskLevel;
import com.suhong.jiuding.tenant.repository.TenantRow;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Synchronous client for approval-service {@code POST /api/approvals}.
 *
 * <p>Failure is never propagated: the caller degrades the tenant to SUBMITTED and
 * records an audit entry (the contract forbids a 5xx here).
 */
@Component
public class ApprovalClient {

    private static final Logger log = LoggerFactory.getLogger(ApprovalClient.class);

    private final RestClient restClient;
    private final TenantProperties properties;
    private final SecurityProperties securityProperties;

    public ApprovalClient(TenantProperties properties, SecurityProperties securityProperties) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        TenantProperties.ApprovalService approval = properties.getApprovalService();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(approval.getConnectTimeoutMs());
        requestFactory.setReadTimeout(approval.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(approval.getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** @return true when approval-service accepted the approval request. */
    public boolean createApproval(TenantRow tenant, String requestedBy) {
        return createApproval(tenant, requestedBy, List.of());
    }

    /** @return true when approval-service accepted the approval request. */
    public boolean createApproval(TenantRow tenant, String requestedBy, List<String> reasons) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("tenantId", tenant.id());
        payload.put("tenantCode", tenant.tenantCode());
        payload.put("plan", tenant.plan());
        payload.put("riskLevel", tenant.riskLevel() == null ? RiskLevel.LOW.name() : tenant.riskLevel());
        payload.put("riskScore", tenant.riskScore());
        payload.put("reasons", reasons == null ? List.of() : reasons);
        payload.put("requestedBy", requestedBy);
        try {
            restClient.post()
                    .uri("/api/approvals")
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(this::applyHeaders)
                    .body(payload)
                    .retrieve()
                    .toBodilessEntity();
            return true;
        } catch (Exception ex) {
            log.warn("approval-service unavailable at {} for tenant {}; degrading to SUBMITTED: {}",
                    properties.getApprovalService().getUrl(), tenant.tenantCode(), ex.toString());
            return false;
        }
    }

    private void applyHeaders(HttpHeaders headers) {
        headers.set(Headers.INTERNAL_TOKEN, securityProperties.getInternalToken());
        String requestId = MDC.get("requestId");
        headers.set(Headers.REQUEST_ID, requestId == null ? UUID.randomUUID().toString() : requestId);

        boolean forwarded = false;
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (attributes instanceof ServletRequestAttributes servletAttributes) {
            HttpServletRequest request = servletAttributes.getRequest();
            String authorization = request.getHeader(Headers.AUTHORIZATION);
            if (authorization != null && !authorization.isBlank()) {
                headers.set(Headers.AUTHORIZATION, authorization);
                forwarded = true;
            } else {
                String apiKey = request.getHeader(Headers.API_KEY);
                if (apiKey != null && !apiKey.isBlank()) {
                    headers.set(Headers.API_KEY, apiKey);
                    forwarded = true;
                }
            }
        }
        if (!forwarded) {
            headers.set(Headers.API_KEY, properties.getApprovalService().getApiKey());
        }
    }
}
