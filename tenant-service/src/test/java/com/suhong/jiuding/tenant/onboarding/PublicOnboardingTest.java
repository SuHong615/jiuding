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
package com.suhong.jiuding.tenant.onboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.RiskReviewClient;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public self-service onboarding (docs/15 section 6.2): compliant applications are auto
 * activated, everything else waits for manual review, responses never leak internal ids and
 * the submission endpoint is rate limited per client IP.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PublicOnboardingTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private OrgUnitRepository orgUnits;
    @Autowired
    private OnboardingApplicationRepository applications;
    @MockBean
    private RiskReviewClient riskReviewClient;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM onboarding_applications");
        TenantFixtures.clean(jdbc);
        reset(riskReviewClient);
        when(riskReviewClient.review(any())).thenReturn(
                new RiskReviewClient.RiskAssessment("LOW", 12, "AUTO_APPROVE", List.of("profile complete")));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void compliantApplicationIsAutoActivated() throws Exception {
        JsonNode data = submit("Acme Cloud", "PRO", "10.10.0.1");

        assertThat(data.path("status").asText()).isEqualTo("ACTIVATED");
        assertThat(data.path("applicationNo").asText()).startsWith("ONB");
        assertThat(data.path("tenantCode").asText()).isNotBlank();
        assertThat(data.path("message").asText()).isNotBlank();
        // Public response must not expose internal identifiers.
        assertThat(data.path("id").isMissingNode()).isTrue();
        assertThat(data.path("tenantId").isMissingNode()).isTrue();

        OnboardingApplicationRow application = applications
                .findByApplicationNo(data.path("applicationNo").asText()).orElseThrow();
        assertThat(application.status()).isEqualTo("ACTIVATED");
        assertThat(application.agentDecision()).isEqualTo("AUTO_APPROVE");
        assertThat(application.agentRiskLevel()).isEqualTo("LOW");

        TenantRow tenant = tenants.findByCode(application.tenantCode()).orElseThrow();
        assertThat(tenant.status()).isEqualTo("ACTIVE");
        assertThat(tenant.source()).isEqualTo("SELF_SERVICE");
        assertThat(tenant.riskLevel()).isEqualTo("LOW");
        assertThat(tenant.activatedAt()).isNotNull();

        List<OrgUnitRow> roots = orgUnits.list(tenant.id(), null, null);
        assertThat(roots).hasSize(1);
        assertThat(roots.get(0).code()).isEqualTo("ROOT");
        assertThat(roots.get(0).depth()).isZero();
        assertThat(roots.get(0).path()).isEqualTo("/" + roots.get(0).id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'ONBOARDING_ACTIVATED'", Long.class, tenant.id())).isEqualTo(1);
    }

    @Test
    void nonCompliantApplicationIsQueuedForManualReview() throws Exception {
        when(riskReviewClient.review(any())).thenReturn(
                new RiskReviewClient.RiskAssessment("MEDIUM", 55, "NEEDS_REVIEW", List.of("medium risk")));

        JsonNode data = submit("Manual Review Ltd", "PRO", "10.10.0.2");
        assertThat(data.path("status").asText()).isEqualTo("PENDING_MANUAL");

        OnboardingApplicationRow application = applications
                .findByApplicationNo(data.path("applicationNo").asText()).orElseThrow();
        assertThat(application.status()).isEqualTo("PENDING_MANUAL");
        assertThat(application.agentRiskLevel()).isEqualTo("MEDIUM");
        TenantRow tenant = tenants.findByCode(application.tenantCode()).orElseThrow();
        // approval-service is unreachable in the test profile: the tenant waits in SUBMITTED.
        assertThat(tenant.status()).isIn("SUBMITTED", "RISK_REVIEW");
    }

    @Test
    void enterprisePlanNeverAutoActivates() throws Exception {
        JsonNode data = submit("Enterprise Corp", "ENTERPRISE", "10.10.0.3");
        assertThat(data.path("status").asText()).isEqualTo("PENDING_MANUAL");
        TenantRow tenant = tenants.findByCode(data.path("tenantCode").asText()).orElseThrow();
        assertThat(tenant.status()).isNotEqualTo("ACTIVE");
    }

    @Test
    void degradedAgentReviewFallsBackToManualReview() throws Exception {
        when(riskReviewClient.review(any())).thenReturn(null);
        JsonNode data = submit("Agent Down Ltd", "PRO", "10.10.0.4");
        assertThat(data.path("status").asText()).isEqualTo("PENDING_MANUAL");
        OnboardingApplicationRow application = applications
                .findByApplicationNo(data.path("applicationNo").asText()).orElseThrow();
        assertThat(application.agentRiskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void statusLookupMergesLiveTenantState() throws Exception {
        JsonNode applied = submit("Status Check Inc", "PRO", "10.10.0.5");
        String applicationNo = applied.path("applicationNo").asText();

        mockMvc.perform(get("/api/public/onboarding/" + applicationNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVATED"))
                .andExpect(jsonPath("$.data.applicationNo").value(applicationNo));

        TenantRow tenant = tenants.findByCode(applied.path("tenantCode").asText()).orElseThrow();
        jdbc.update("UPDATE tenants SET status = 'REJECTED' WHERE id = ?", tenant.id());
        mockMvc.perform(get("/api/public/onboarding/" + applicationNo))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));

        mockMvc.perform(get("/api/public/onboarding/ONBUNKNOWN00"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void sixthSubmissionFromSameIpIsRateLimited() throws Exception {
        String ip = "10.10.9.99";
        for (int attempt = 1; attempt <= 5; attempt++) {
            submit("Rate Limit " + attempt, "PRO", ip);
        }
        mockMvc.perform(post("/api/public/onboarding")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Rate Limit 6", "PRO")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value(42900));
    }

    @Test
    void validationAndDuplicateTenantCodeAreRejected() throws Exception {
        mockMvc.perform(post("/api/public/onboarding")
                        .header("X-Forwarded-For", "10.10.0.6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactEmail\":\"someone@example.com\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        submitWithCode("t-taken-code", "First", "10.10.0.7");
        mockMvc.perform(post("/api/public/onboarding")
                        .header("X-Forwarded-For", "10.10.0.8")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCode("t-taken-code", "Second")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode submit(String companyName, String plan, String ip) throws Exception {
        return read(mockMvc.perform(post("/api/public/onboarding")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(companyName, plan)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");
    }

    private void submitWithCode(String tenantCode, String companyName, String ip) throws Exception {
        mockMvc.perform(post("/api/public/onboarding")
                        .header("X-Forwarded-For", ip)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyWithCode(tenantCode, companyName)))
                .andExpect(status().isOk());
    }

    private String body(String companyName, String plan) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", companyName);
        body.put("contactName", "Apply Contact");
        body.put("contactEmail", "apply@acme.io");
        body.put("contactPhone", "13800000000");
        body.put("plan", plan);
        body.put("region", "cn-shanghai");
        body.put("industry", "software");
        body.put("notes", "self-service onboarding test");
        return objectMapper.writeValueAsString(body);
    }

    private String bodyWithCode(String tenantCode, String companyName) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("companyName", companyName);
        body.put("tenantCode", tenantCode);
        body.put("contactEmail", "apply@acme.io");
        body.put("plan", "PRO");
        return objectMapper.writeValueAsString(body);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
