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

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Authentication / authorisation matrix for the approval API. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_permission;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class ApprovalPermissionTest {

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
    void unauthenticatedRequestIsRejected() throws Exception {
        mockMvc.perform(post("/api/approvals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, "t-perm-0", "ACME-PERM-0",
                                "PRO", "MEDIUM", 50)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void auditorCannotDecide() throws Exception {
        String id = createPending("t-perm-1", "ACME-PERM-1");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.auditor(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision("APPROVE", "should fail")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void tenantAdminCannotDecide() throws Exception {
        String id = createPending("t-perm-2", "ACME-PERM-2");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.operator(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision("APPROVE", "should fail")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void mcpApiKeyWithoutApproverRoleCannotDecide() throws Exception {
        String id = createPending("t-perm-3", "ACME-PERM-3");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision("APPROVE", "should fail")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void rejectWithoutCommentIsRejected() throws Exception {
        String id = createPending("t-perm-4", "ACME-PERM-4");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision("REJECT", null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void approverCanRejectWithComment() throws Exception {
        String id = createPending("t-perm-5", "ACME-PERM-5");
        mockMvc.perform(post("/api/approvals/{id}/decisions", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decision("REJECT", "compliance issue")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("REJECTED"))
                .andExpect(jsonPath("$.data.decisionComment").value("compliance issue"));
        verify(tenantServiceClient).updateStatus(eq("t-perm-5"), eq("REJECTED"), anyString());
    }

    @Test
    void tenantScopedPrincipalCannotReadAnotherTenantsApproval() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/approvals")
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(TestSupport.createBody(objectMapper, "t-other-perm", "ACME-OTHER",
                                "PRO", "MEDIUM", 50)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        String id = data.path("id").asText();

        mockMvc.perform(get("/api/approvals/{id}", id)
                        .header(HttpHeaders.AUTHORIZATION, TestSupport.operator(jwtService)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    private String decision(String decision, String comment) throws Exception {
        if (comment == null) {
            return objectMapper.writeValueAsString(Map.of("decision", decision));
        }
        return objectMapper.writeValueAsString(Map.of("decision", decision, "comment", comment));
    }

    private String createPending(String tenantId, String tenantCode) throws Exception {
        when(agentServiceClient.runSkill(eq("approval-policy"), anyMap(), anyString()))
                .thenThrow(new DownstreamException("agent-service down"));
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
