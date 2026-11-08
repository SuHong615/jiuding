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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.Headers;
import com.suhong.jiuding.common.security.SecurityProperties;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.repository.TenantRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Runs the {@code risk-review} skill through agent-service before a tenant is
 * submitted for approval.
 *
 * <p>This is the "skill-based tenant rule" entry point of the onboarding flow:
 * the deterministic rule engine (not an LLM) produces {@code riskLevel},
 * {@code riskScore}, a decision and human-readable findings. Any failure is
 * swallowed: onboarding degrades to the local LOW default instead of blocking.
 */
@Component
public class RiskReviewClient {

    private static final Logger log = LoggerFactory.getLogger(RiskReviewClient.class);

    private final RestClient restClient;
    private final TenantProperties properties;
    private final SecurityProperties securityProperties;
    private final ObjectMapper objectMapper;

    public RiskReviewClient(TenantProperties properties, SecurityProperties securityProperties,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.securityProperties = securityProperties;
        this.objectMapper = objectMapper;
        TenantProperties.AgentService agent = properties.getAgentService();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(agent.getConnectTimeoutMs());
        requestFactory.setReadTimeout(agent.getReadTimeoutMs());
        this.restClient = RestClient.builder()
                .baseUrl(agent.getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** @return assessment, or {@code null} when the skill engine is unavailable/disabled. */
    public RiskAssessment review(TenantRow tenant) {
        if (!properties.getRiskReview().isEnabled() || tenant == null) {
            return null;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tenantId", tenant.id());
            input.put("tenantCode", tenant.tenantCode());
            input.put("name", tenant.name());
            input.put("plan", tenant.plan());
            input.put("region", tenant.region());
            input.put("isolationMode", tenant.isolationMode());
            input.put("quotaSeats", tenant.quotaSeats());
            input.put("quotaStorageGb", tenant.quotaStorageGb());
            input.put("quotaApiRpm", tenant.quotaApiRpm());
            input.put("contactName", tenant.contactName());
            input.put("contactEmail", tenant.contactEmail());
            input.put("attributes", readAttributes(tenant.attributesJson()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", input);
            body.put("tenantId", tenant.id());

            byte[] payload = restClient.post()
                    .uri("/api/skills/risk-review/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(Headers.API_KEY, properties.getAgentService().getApiKey())
                    .header(Headers.INTERNAL_TOKEN, securityProperties.getInternalToken())
                    .body(body)
                    .retrieve()
                    .body(byte[].class);

            JsonNode root = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode data = root.has("data") ? root.path("data") : root;
            if (data == null || data.isMissingNode() || data.isNull()) {
                return null;
            }
            int score = data.path("score").asInt(0);
            String decision = data.path("decision").asText("NONE");
            String level = null;
            for (JsonNode action : data.path("actions")) {
                if ("setRiskLevel".equals(action.path("action").asText())) {
                    level = action.path("value").asText(null);
                }
            }
            if (level == null) {
                level = levelForScore(score);
            }
            List<String> reasons = new ArrayList<>();
            for (JsonNode finding : data.path("findings")) {
                String message = finding.path("message").asText("");
                if (!message.isBlank()) {
                    reasons.add(message);
                }
            }
            return new RiskAssessment(level, score, decision, reasons);
        } catch (Exception ex) {
            log.warn("risk-review skill unavailable for tenant {}: {}", tenant.tenantCode(), ex.toString());
            return null;
        }
    }

    /** Mirrors the thresholds documented in skills/risk-review/rules.yaml. */
    static String levelForScore(int score) {
        if (score <= 20) {
            return "LOW";
        }
        if (score <= 60) {
            return "MEDIUM";
        }
        return "HIGH";
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readAttributes(String attributesJson) {
        if (attributesJson == null || attributesJson.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(attributesJson, Map.class);
        } catch (Exception ex) {
            return Map.of();
        }
    }

    /** Deterministic risk assessment produced by the skill rule engine. */
    public record RiskAssessment(String riskLevel, int riskScore, String decision, List<String> reasons) {
        public RiskAssessment {
            reasons = reasons == null ? List.of() : List.copyOf(reasons);
        }
    }
}
