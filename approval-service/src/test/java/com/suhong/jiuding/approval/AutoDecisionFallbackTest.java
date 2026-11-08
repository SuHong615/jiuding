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
package com.suhong.jiuding.approval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.approval.client.AgentServiceClient;
import com.suhong.jiuding.approval.client.DownstreamException;
import com.suhong.jiuding.approval.client.TenantServiceClient;
import com.suhong.jiuding.approval.support.TestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Verifies the approval-policy call and the deterministic local fallback rules:
 * LOW &amp;&amp; !ENTERPRISE =&gt; auto approve; MEDIUM =&gt; L1; HIGH =&gt; L2.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_autodecision;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class AutoDecisionFallbackTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;

    @MockBean
    private AgentServiceClient agentServiceClient;
    @MockBean
    private TenantServiceClient tenantServiceClient;

    @Test
    void lowRiskNonEnterpriseFallsBackToAutoApprove() throws Exception {
        stubAgentUnavailable();

        MvcResult result = createAndReturn("t-ad-1", "ACME-AD-1", "PRO", "LOW", 7);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("autoDecided").asBoolean()).isTrue();
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_APPROVED");
        verify(tenantServiceClient).updateStatus(eq("t-ad-1"), eq("ACTIVE"), anyString());
    }

    @Test
    void lowRiskEnterpriseRequiresL1Review() throws Exception {
        stubAgentUnavailable();

        MvcResult result = createAndReturn("t-ad-2", "ACME-AD-2", "ENTERPRISE", "LOW", 7);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = data.path("nodes").get(2);
        assertThat(human.path("node").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(human.path("assigneeRole").asText()).isEqualTo("APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(24);
    }

    @Test
    void mediumRiskFallsBackToL1With24hSla() throws Exception {
        stubAgentUnavailable();

        MvcResult result = createAndReturn("t-ad-3", "ACME-AD-3", "PRO", "MEDIUM", 55);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = data.path("nodes").get(2);
        assertThat(human.path("assigneeRole").asText()).isEqualTo("APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(24);
        assertThat(data.path("slaDueAt").isNull()).isFalse();
    }

    @Test
    void highRiskFallsBackToL2With8hSla() throws Exception {
        stubAgentUnavailable();

        MvcResult result = createAndReturn("t-ad-4", "ACME-AD-4", "PRO", "HIGH", 88);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = data.path("nodes").get(2);
        assertThat(human.path("assigneeRole").asText()).isEqualTo("SENIOR_APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(8);
    }

    @Test
    void agentDecisionOverridesLocalRules() throws Exception {
        JsonNode decision = objectMapper.readTree("""
                {"decision":"NEEDS_REVIEW",
                 "actions":[{"action":"requireRole","value":"L2"},{"action":"setSlaHours","value":12}]}
                """);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString())).thenReturn(decision);

        MvcResult result = createAndReturn("t-ad-5", "ACME-AD-5", "PRO", "MEDIUM", 55);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = data.path("nodes").get(2);
        assertThat(human.path("assigneeRole").asText()).isEqualTo("SENIOR_APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(12);
        assertThat(data.path("timeline").findValues("detail").stream().map(JsonNode::asText))
                .anyMatch(detail -> detail.contains("source=agent"));
        verify(agentServiceClient).runSkill(eq("approval-policy"), anyMap(), eq("t-ad-5"));
    }

    @Test
    void agentAutoApproveIsUsedWhenAvailable() throws Exception {
        JsonNode decision = objectMapper.readTree("{\"decision\":\"AUTO_APPROVE\"}");
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString())).thenReturn(decision);

        MvcResult result = createAndReturn("t-ad-6", "ACME-AD-6", "PRO", "LOW", 5);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("autoDecided").asBoolean()).isTrue();
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_APPROVED");
        verify(tenantServiceClient).updateStatus(eq("t-ad-6"), eq("ACTIVE"), anyString());
    }

    @Test
    void unusableAgentPayloadFallsBackToLocalRules() throws Exception {
        JsonNode decision = objectMapper.readTree("{\"verdict\":\"PASS\",\"findings\":[]}");
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString())).thenReturn(decision);

        MvcResult result = createAndReturn("t-ad-7", "ACME-AD-7", "PRO", "MEDIUM", 40);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(data.path("nodes").get(2).path("assigneeRole").asText()).isEqualTo("APPROVER");
        assertThat(data.path("timeline").findValues("detail").stream().map(JsonNode::asText))
                .anyMatch(detail -> detail.contains("source=local-fallback"));
    }

    @Test
    void parsesActualAgentServiceApprovalPolicyPayload() throws Exception {
        // exact data node produced by agent-service (skill approval-policy)
        JsonNode decision = objectMapper.readTree("""
                {"skillId":"approval-policy","runId":"run-1","verdict":"WARN","score":20,
                 "decision":"NEEDS_REVIEW","findings":[],
                 "actions":[
                   {"ruleId":"R-APR-003","action":"addScore","value":20},
                   {"ruleId":"R-APR-003","action":"setDecision","value":"NEEDS_REVIEW"},
                   {"ruleId":"R-APR-003","action":"requireRole","value":"L1"},
                   {"ruleId":"R-APR-003","action":"setSlaHours","value":24}],
                 "trace":[],"durationMs":1,"model":"scripted"}
                """);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString())).thenReturn(decision);

        MvcResult result = createAndReturn("t-ad-8", "ACME-AD-8", "PRO", "MEDIUM", 30);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = data.path("nodes").get(2);
        assertThat(human.path("assigneeRole").asText()).isEqualTo("APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(24);
        assertThat(data.path("timeline").findValues("detail").stream().map(JsonNode::asText))
                .anyMatch(detail -> detail.contains("source=agent"));
    }

    @Test
    void noneDecisionFallsBackToLocalRules() throws Exception {
        JsonNode decision = objectMapper.readTree("""
                {"skillId":"approval-policy","verdict":"PASS","score":0,"decision":"NONE",
                 "findings":[],"actions":[],"trace":[],"durationMs":1,"model":"scripted"}
                """);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString())).thenReturn(decision);

        MvcResult result = createAndReturn("t-ad-9", "ACME-AD-9", "ENTERPRISE", "LOW", 10);
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(data.path("nodes").get(2).path("assigneeRole").asText()).isEqualTo("APPROVER");
        assertThat(data.path("timeline").findValues("detail").stream().map(JsonNode::asText))
                .anyMatch(detail -> detail.contains("source=local-fallback"));
    }

    private void stubAgentUnavailable() {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
    }

    private MvcResult createAndReturn(String tenantId, String tenantCode, String plan,
                                      String riskLevel, Integer riskScore) throws Exception {
        return mockMvc.perform(post("/api/approvals")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, tenantId, tenantCode, plan, riskLevel, riskScore)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
    }
}
