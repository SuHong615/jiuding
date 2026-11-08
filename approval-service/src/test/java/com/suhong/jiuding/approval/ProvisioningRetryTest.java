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
import com.suhong.jiuding.approval.service.ApprovalScheduler;
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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Downstream failure handling: a failing tenant-service / provisioning skill must
 * leave the approval in PROVISIONING (HTTP 200, never 5xx) and the scheduler retry
 * must complete the chain once the dependency recovers.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_provision;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class ProvisioningRetryTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private ApprovalScheduler approvalScheduler;

    @MockBean
    private AgentServiceClient agentServiceClient;
    @MockBean
    private TenantServiceClient tenantServiceClient;

    @Test
    void tenantCallbackFailureKeepsProvisioningAndSchedulerRecovers() throws Exception {
        stubAgentPolicyUnavailable();
        String id = createPending("t-retry-1", "ACME-RETRY-1");
        doThrow(new DownstreamException("tenant-service down"))
                .when(tenantServiceClient).updateStatus(eq("t-retry-1"), eq("APPROVED"), anyString());

        MvcResult decided = mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROVISIONING"))
                .andReturn();
        JsonNode data = TestSupport.dataOf(decided, objectMapper);
        assertThat(TestSupport.timelineActions(data)).contains("PROVISION_FAILED");

        reset(tenantServiceClient);
        approvalScheduler.scan();

        JsonNode recovered = fetch(id);
        assertThat(recovered.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(TestSupport.timelineActions(recovered)).contains("PROVISIONED");
        verify(agentServiceClient, atLeastOnce()).runSkill(eq("provisioning"), anyMap(), anyString());

        approvalScheduler.scan();
        assertThat(fetch(id).path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void provisioningSkillFailureIsRetriedByScheduler() throws Exception {
        stubAgentPolicyUnavailable();
        when(agentServiceClient.runSkill(eq("provisioning"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
        String id = createPending("t-retry-2", "ACME-RETRY-2");

        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROVISIONING"));

        reset(agentServiceClient);
        approvalScheduler.scan();

        assertThat(fetch(id).path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void provisioningVerdictFailKeepsProvisioningAndRetries() throws Exception {
        stubAgentPolicyUnavailable();
        when(agentServiceClient.runSkill(eq("provisioning"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree(
                        "{\"skillId\":\"provisioning\",\"verdict\":\"FAIL\",\"actions\":[]}"));
        String id = createPending("t-retry-3", "ACME-RETRY-3");

        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "ok"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PROVISIONING"));
        assertThat(TestSupport.timelineActions(fetch(id))).contains("PROVISION_FAILED");

        when(agentServiceClient.runSkill(eq("provisioning"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree(
                        "{\"skillId\":\"provisioning\",\"verdict\":\"PASS\",\"actions\":[]}"));
        approvalScheduler.scan();
        assertThat(fetch(id).path("state").asText()).isEqualTo("ACTIVE");
    }

    private void stubAgentPolicyUnavailable() {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
    }

    private String createPending(String tenantId, String tenantCode) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/approvals")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, tenantId, tenantCode, "PRO", "MEDIUM", 55)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("PENDING_APPROVAL"))
                .andReturn();
        return TestSupport.dataOf(result, objectMapper).path("id").asText();
    }

    private JsonNode fetch(String id) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/approvals/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
        return TestSupport.dataOf(result, objectMapper);
    }
}
