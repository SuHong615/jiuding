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
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.repository.TenantRow;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Optional bridge to the agent-service {@code tenant-rating} skill
 * (docs/11 section 4: "调 agent-service tenant-rating skill 取调整项，不可用则仅基础分").
 *
 * <p>The base five-dimension score is always computed locally by
 * {@link com.suhong.jiuding.tenant.domain.RatingCalculator}; the skill may only return a bounded
 * adjustment. Any failure (skill not deployed, timeout after 2s, malformed payload) is
 * swallowed so rating assessment never blocks on the agent tier.
 */
@Component
public class RatingSkillClient {

    private static final Logger log = LoggerFactory.getLogger(RatingSkillClient.class);
    private static final int MAX_ADJUSTMENT = 15;

    private final RestClient restClient;
    private final TenantProperties properties;
    private final ObjectMapper objectMapper;

    public RatingSkillClient(TenantProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
        int timeout = Math.max(200, properties.getRating().getTimeoutMs());
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Math.min(500, timeout));
        requestFactory.setReadTimeout(timeout);
        this.restClient = RestClient.builder()
                .baseUrl(properties.getAgentService().getUrl())
                .requestFactory(requestFactory)
                .build();
    }

    /** @return bounded adjustment (-15..15), or 0 when the skill tier is unavailable. */
    public int adjustment(TenantRow tenant, com.suhong.jiuding.tenant.domain.RatingCalculator.Dims dims) {
        if (!properties.getRating().isEnabled() || tenant == null || dims == null) {
            return 0;
        }
        try {
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("tenantId", tenant.id());
            input.put("tenantCode", tenant.tenantCode());
            input.put("plan", tenant.plan());
            input.put("region", tenant.region());
            input.put("riskLevel", tenant.riskLevel());
            input.put("riskScore", tenant.riskScore());
            input.put("quotaSeats", tenant.quotaSeats());
            input.put("quotaStorageGb", tenant.quotaStorageGb());
            input.put("contactEmail", tenant.contactEmail());
            input.put("dims", Map.of(
                    "business", dims.business(),
                    "compliance", dims.compliance(),
                    "financial", dims.financial(),
                    "security", dims.security(),
                    "service", dims.service()));

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", input);
            body.put("tenantId", tenant.id());

            byte[] payload = restClient.post()
                    .uri("/api/skills/tenant-rating/run")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .header(Headers.API_KEY, properties.getAgentService().getApiKey())
                    .body(body)
                    .retrieve()
                    .body(byte[].class);
            if (payload == null || payload.length == 0) {
                return 0;
            }
            JsonNode root = objectMapper.readTree(new String(payload, StandardCharsets.UTF_8));
            JsonNode data = root.has("data") ? root.path("data") : root;
            if (data == null || data.isMissingNode() || data.isNull()) {
                return 0;
            }
            int adjustment = 0;
            if (data.path("adjustment").isNumber()) {
                adjustment = data.path("adjustment").asInt();
            } else {
                for (JsonNode action : data.path("actions")) {
                    String name = action.path("action").asText("");
                    if ("ratingAdjust".equals(name) || "adjustScore".equals(name)
                            || "adjustRating".equals(name)) {
                        adjustment += action.path("value").asInt(0);
                    }
                }
            }
            return Math.max(-MAX_ADJUSTMENT, Math.min(MAX_ADJUSTMENT, adjustment));
        } catch (Exception ex) {
            log.warn("tenant-rating skill unavailable for tenant {}: {}",
                    tenant.tenantCode(), ex.toString());
            return 0;
        }
    }
}
