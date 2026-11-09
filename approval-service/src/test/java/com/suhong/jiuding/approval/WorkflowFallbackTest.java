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
import com.suhong.jiuding.approval.support.WorkflowTestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Backward compatibility: without a published definition the pre-v12 default flow
 * is used; DRAFT definitions and unknown codes never change that behaviour.
 * Ordered so the "default" workflow is published last (it applies implicitly).
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_wf_fallback;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class WorkflowFallbackTest {

    private static final String HUMAN_DEF = """
            {"nodes":[
              {"id":"risk","type":"AUTO_CHECK","skill":"approval-policy","next":"approval"},
              {"id":"approval","type":"HUMAN","role":"SENIOR_APPROVER","slaHours":12,"next":null}]}
            """;
    private static final String DEFAULT_DEF = """
            {"nodes":[
              {"id":"default-approval","type":"HUMAN","role":"PLATFORM_ADMIN","slaHours":2,"next":null}]}
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
    @Order(1)
    void noDefinitionUsesDefaultFlow() throws Exception {
        stubAgentUnavailable();
        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-fb-1", "ACME-FB-1",
                "PRO", "MEDIUM", 55, null);
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(TestSupport.nodeNames(data))
                .containsExactly("RISK_CHECK", "AUTO_DECISION", "PENDING_APPROVAL");
        assertThat(TestSupport.timelineActions(data)).contains("AUTO_DECISION");
    }

    @Test
    @Order(2)
    void draftDefinitionIsIgnored() throws Exception {
        stubAgentUnavailable();
        WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "draft-only", HUMAN_DEF);
        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-fb-2", "ACME-FB-2",
                "PRO", "MEDIUM", 55, "draft-only");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(TestSupport.nodeNames(data))
                .containsExactly("RISK_CHECK", "AUTO_DECISION", "PENDING_APPROVAL");
        assertThat(TestSupport.timelineActions(data)).contains("WORKFLOW_FALLBACK");
    }

    @Test
    @Order(3)
    void unknownWorkflowCodeFallsBackToDefault() throws Exception {
        stubAgentUnavailable();
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "known-only", HUMAN_DEF);
        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-fb-3", "ACME-FB-3",
                "PRO", "MEDIUM", 55, "unknown-code");
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(TestSupport.nodeNames(data))
                .containsExactly("RISK_CHECK", "AUTO_DECISION", "PENDING_APPROVAL");
        assertThat(TestSupport.timelineActions(data)).contains("WORKFLOW_FALLBACK");
    }

    @Test
    @Order(4)
    void publishedDefaultCodeAppliesImplicitly() throws Exception {
        stubAgentUnavailable();
        WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "default", DEFAULT_DEF);

        MvcResult created = WorkflowTestSupport.createApproval(
                mockMvc, jwtService, objectMapper, "t-wf-fb-4", "ACME-FB-4",
                "PRO", "MEDIUM", 55, null);
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        assertThat(data.path("state").asText()).isEqualTo("PENDING_APPROVAL");
        assertThat(data.path("currentNode").asText()).isEqualTo("default-approval");
        assertThat(TestSupport.nodeNames(data)).containsExactly("default-approval");
        List<String> actions = TestSupport.timelineActions(data);
        assertThat(actions).contains("WORKFLOW_SELECTED", "ROUTED_TO_APPROVAL");
    }

    private void stubAgentUnavailable() {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
    }
}
