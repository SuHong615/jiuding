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
import org.mockito.InOrder;
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
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end workflow test through the real REST layer: create -&gt; auto decision -
 * &gt; human review -&gt; provisioning -&gt; ACTIVE, plus invalid transitions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_workflow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class WorkflowEngineTest {

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
    void createsApprovalAndRoutesMediumRiskToL1() throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));

        MvcResult created = mockMvc.perform(post("/api/approvals")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, "t-wf-1", "ACME-WF-1", "PRO", "MEDIUM", 55)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.state").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.currentNode").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.autoDecided").value(false))
                .andExpect(jsonPath("$.data.riskLevel").value("MEDIUM"))
                .andExpect(jsonPath("$.data.slaDueAt").isNotEmpty())
                .andExpect(jsonPath("$.data.nodes[2].node").value("PENDING_APPROVAL"))
                .andExpect(jsonPath("$.data.nodes[2].type").value("HUMAN"))
                .andExpect(jsonPath("$.data.nodes[2].state").value("PENDING"))
                .andExpect(jsonPath("$.data.nodes[2].assigneeRole").value("APPROVER"))
                .andExpect(jsonPath("$.data.nodes[2].slaHours").value(24))
                .andReturn();

        String id = TestSupport.dataOf(created, objectMapper).path("id").asText();

        mockMvc.perform(get("/api/approvals/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.timeline").isArray());

        mockMvc.perform(get("/api/approvals")
                        .param("state", "PENDING_APPROVAL")
                        .param("page", "0")
                        .param("size", "10")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").isNumber())
                .andExpect(jsonPath("$.data.items").isArray());
    }

    @Test
    void approvesAndProvisionsTenantToActive() throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
        String id = createPending("t-wf-2", "ACME-WF-2");

        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "looks good"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.decidedBy").value("approver"));

        InOrder inOrder = inOrder(tenantServiceClient);
        inOrder.verify(tenantServiceClient).updateStatus(eq("t-wf-2"), eq("APPROVED"), anyString());
        inOrder.verify(tenantServiceClient).updateStatus(eq("t-wf-2"), eq("PROVISIONING"), anyString());
        inOrder.verify(tenantServiceClient).updateStatus(eq("t-wf-2"), eq("ACTIVE"), anyString());
        verify(agentServiceClient).runSkill(eq("provisioning"), anyMap(), eq("t-wf-2"));

        MvcResult detail = mockMvc.perform(get("/api/approvals/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = TestSupport.dataOf(detail, objectMapper);
        assertThat(TestSupport.nodeNames(data)).contains("RISK_CHECK", "PROVISIONING", "ACTIVE");
        assertThat(data.path("nodes").findValues("state").stream().map(JsonNode::asText))
                .contains("APPROVED");
        assertThat(TestSupport.timelineActions(data))
                .contains("CREATED", "RISK_CHECK", "AUTO_DECISION", "ROUTED_TO_APPROVAL",
                        "DECISION_APPROVED", "PROVISIONED");
    }

    @Test
    void rejectsDecisionWhenApprovalIsAlreadyTerminal() throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
        String id = createPending("t-wf-3", "ACME-WF-3");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "approve"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "again"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));
    }

    @Test
    void autoApprovesLowRiskNonEnterpriseTenant() throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));

        MvcResult created = mockMvc.perform(post("/api/approvals")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, "t-wf-4", "ACME-WF-4", "PRO", "LOW", 8)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"))
                .andExpect(jsonPath("$.data.autoDecided").value(true))
                .andExpect(jsonPath("$.data.decidedBy").value("agent"))
                .andReturn();

        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_DECISION", "AUTO_APPROVED", "PROVISIONED");
        assertThat(TestSupport.nodeNames(data)).contains("RISK_CHECK", "AUTO_DECISION", "APPROVED", "ACTIVE");
        verify(tenantServiceClient).updateStatus(eq("t-wf-4"), eq("ACTIVE"), anyString());
    }

    @Test
    void overviewExposesWorkflowCounters() throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
        createPending("t-wf-5", "ACME-WF-5");

        MvcResult result = mockMvc.perform(get("/api/approvals/overview")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("pending").asLong()).isGreaterThanOrEqualTo(1);
        assertThat(data.has("autoApproved")).isTrue();
        assertThat(data.has("approved")).isTrue();
        assertThat(data.has("rejected")).isTrue();
        assertThat(data.has("slaBreached")).isTrue();
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
}
