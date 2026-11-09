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
import com.suhong.jiuding.approval.client.TenantServiceClient;
import com.suhong.jiuding.approval.support.TestSupport;
import com.suhong.jiuding.approval.support.WorkflowTestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Engine behaviour for published definitions: AUTO_CHECK reuses the existing
 * decision pipeline, HUMAN stops at a pending node with definition role/SLA,
 * PARALLEL executes sequentially and is annotated, and auto decisions skip humans.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_wf_engine;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class WorkflowEngineExecutionTest {

    private static final String HUMAN_DEF = """
            {"nodes":[
              {"id":"risk","type":"AUTO_CHECK","skill":"approval-policy","next":"approval"},
              {"id":"approval","type":"HUMAN","role":"SENIOR_APPROVER","slaHours":12,"next":null}]}
            """;
    private static final String PARALLEL_DEF = """
            {"nodes":[
              {"id":"stage","type":"PARALLEL","next":"approval","nodes":[
                {"id":"p1","type":"AUTO_CHECK","skill":"risk-review"},
                {"id":"p2","type":"AUTO_CHECK","skill":"tenant-intake"}]},
              {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24,"next":null}]}
            """;
    private static final String SLA_DEF = """
            {"nodes":[
              {"id":"timer","type":"SLA","slaHours":6,"next":"approval"},
              {"id":"approval","type":"HUMAN","role":"APPROVER","next":null}]}
            """;

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
    void humanNodeFromPublishedDefinitionWaitsWithDefinitionRoleAndSla() throws Exception {
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "engine-human", HUMAN_DEF);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree("""
                        {"decision":"NEEDS_REVIEW",
                         "actions":[{"action":"requireRole","value":"L1"},
                                    {"action":"setSlaHours","value":24}]}
                        """));

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-eng-1", "ACME-ENG-1",
                "PRO", "MEDIUM", 55, "engine-human");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(data.path("currentNode").asText()).isEqualTo("approval");
        assertThat(data.path("slaDueAt").isMissingNode()).isFalse();

        JsonNode risk = node(data, "risk");
        assertThat(risk.path("type").asText()).isEqualTo("AUTO");
        assertThat(risk.path("state").asText()).isEqualTo("APPROVED");
        assertThat(risk.path("comment").asText()).contains("decision=NEEDS_REVIEW");

        JsonNode human = node(data, "approval");
        assertThat(human.path("type").asText()).isEqualTo("HUMAN");
        assertThat(human.path("state").asText()).isEqualTo("PENDING");
        assertThat(human.path("assigneeRole").asText()).isEqualTo("SENIOR_APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(12);

        assertThat(TestSupport.timelineActions(data))
                .contains("WORKFLOW_SELECTED", "WORKFLOW_PLAN", "AUTO_CHECK", "ROUTED_TO_APPROVAL");

        String id = data.path("id").asText();
        MvcResult decided = mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "definition flow approved"))))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(TestSupport.dataOf(decided, objectMapper).path("state").asText()).isEqualTo("ACTIVE");
    }

    @Test
    void autoApproveDecisionSkipsHumanNode() throws Exception {
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "engine-auto", HUMAN_DEF);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree("{\"decision\":\"AUTO_APPROVE\"}"));

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-eng-2", "ACME-ENG-2",
                "PRO", "LOW", 5, "engine-auto");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("ACTIVE");
        assertThat(data.path("autoDecided").asBoolean()).isTrue();
        assertThat(TestSupport.nodeNames(data)).contains("risk").doesNotContain("approval");
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_APPROVED");
        verify(tenantServiceClient).updateStatus(eq("t-wf-eng-2"), eq("ACTIVE"), anyString());
    }

    @Test
    void parallelNodeIsExecutedSequentiallyAndAnnotated() throws Exception {
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "engine-parallel", PARALLEL_DEF);
        when(agentServiceClient.runSkill(eq("risk-review"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree("{\"verdict\":\"PASS\"}"));
        when(agentServiceClient.runSkill(eq("tenant-intake"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree("{\"verdict\":\"PASS\"}"));

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-eng-3", "ACME-ENG-3",
                "PRO", "MEDIUM", 40, "engine-parallel");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(TestSupport.nodeNames(data)).contains("stage", "p1", "p2", "approval");

        JsonNode stage = node(data, "stage");
        assertThat(stage.path("comment").asText()).contains("[PARALLEL]").contains("sequentially");
        verify(agentServiceClient).runSkill(eq("risk-review"), anyMap(), anyString());
        verify(agentServiceClient).runSkill(eq("tenant-intake"), anyMap(), anyString());
    }

    @Test
    void rejectDecisionFromDefinitionFlowRejectsApproval() throws Exception {
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "engine-reject", HUMAN_DEF);
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenReturn(objectMapper.readTree("{\"decision\":\"REJECT\"}"));

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-eng-4", "ACME-ENG-4",
                "PRO", "HIGH", 90, "engine-reject");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("REJECTED");
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_REJECTED");
        verify(tenantServiceClient).updateStatus(eq("t-wf-eng-4"), eq("REJECTED"), anyString());
    }

    @Test
    void slaNodeSetsHumanNodeSla() throws Exception {
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "engine-sla", SLA_DEF);

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-eng-5", "ACME-ENG-5",
                "PRO", "MEDIUM", 40, "engine-sla");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        JsonNode human = node(data, "approval");
        assertThat(human.path("slaHours").asInt()).isEqualTo(6);
        assertThat(node(data, "timer").path("state").asText()).isEqualTo("APPROVED");
    }

    private JsonNode node(JsonNode approval, String nodeName) {
        for (JsonNode node : approval.path("nodes")) {
            if (nodeName.equals(node.path("node").asText())) {
                return node;
            }
        }
        throw new AssertionError("node not found: " + nodeName);
    }
}
