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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** SLA escalation: the 30s scheduler escalates an overdue approval exactly once. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_sla;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class SlaEscalationTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ApprovalScheduler approvalScheduler;

    @MockBean
    private AgentServiceClient agentServiceClient;
    @MockBean
    private TenantServiceClient tenantServiceClient;

    @Test
    void schedulerEscalatesOverdueApprovalExactlyOnce() throws Exception {
        stubAgentUnavailable();
        String id = createPending("t-sla-1", "ACME-SLA-1");
        makeOverdue(id);

        JsonNode before = fetch(id);
        assertThat(before.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(before.path("escalated").asBoolean()).isFalse();

        mockMvc.perform(get("/api/approvals/overview")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.slaBreached").isNumber());

        approvalScheduler.scan();

        JsonNode after = fetch(id);
        assertThat(after.path("escalated").asBoolean()).isTrue();
        JsonNode human = findNode(after, "PENDING_APPROVAL");
        assertThat(human.path("assigneeRole").asText()).isEqualTo("SENIOR_APPROVER");
        assertThat(human.path("slaHours").asInt()).isEqualTo(8);
        assertThat(countAction(after, "ESCALATED")).isEqualTo(1);

        approvalScheduler.scan();
        assertThat(countAction(fetch(id), "ESCALATED")).isEqualTo(1);

        mockMvc.perform(post("/api/approvals/{id}/escalate", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"manual\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalated").value(true));
        assertThat(countAction(fetch(id), "ESCALATED")).isEqualTo(1);
    }

    @Test
    void manualEscalationIsIdempotentAndApprovalStaysDecidable() throws Exception {
        stubAgentUnavailable();
        String id = createPending("t-sla-2", "ACME-SLA-2");

        mockMvc.perform(post("/api/approvals/{id}/escalate", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"urgent customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.escalated").value(true))
                .andExpect(jsonPath("$.data.nodes[2].assigneeRole").value("SENIOR_APPROVER"));

        mockMvc.perform(post("/api/approvals/{id}/escalate", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isOk());
        assertThat(countAction(fetch(id), "ESCALATED")).isEqualTo(1);

        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "escalated but approved"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("ACTIVE"));
    }

    @Test
    void escalationRejectedWhenApprovalIsNotPending() throws Exception {
        stubAgentUnavailable();
        String id = createPending("t-sla-3", "ACME-SLA-3");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("decision", "APPROVE", "comment", "approve"))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approvals/{id}/escalate", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too late\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));
    }

    private void makeOverdue(String id) {
        jdbcTemplate.update("UPDATE approvals SET sla_due_at = ? WHERE id = ?",
                Timestamp.from(Instant.now().minusSeconds(7200)), id);
    }

    private void stubAgentUnavailable() {
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

    private JsonNode findNode(JsonNode approval, String nodeName) {
        for (JsonNode node : approval.path("nodes")) {
            if (nodeName.equals(node.path("node").asText())) {
                return node;
            }
        }
        throw new AssertionError("node not found: " + nodeName);
    }

    private long countAction(JsonNode approval, String action) {
        return approval.path("timeline").findValues("action").stream()
                .filter(node -> action.equals(node.asText()))
                .count();
    }
}
