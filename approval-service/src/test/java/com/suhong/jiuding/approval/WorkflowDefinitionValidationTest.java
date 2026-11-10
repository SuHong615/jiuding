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
import com.suhong.jiuding.approval.support.TestSupport;
import com.suhong.jiuding.approval.support.WorkflowTestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Validation endpoint + publish gating for workflow definitions. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_wf_validate;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class WorkflowDefinitionValidationTest {

    private static final String CYCLE_DEF = """
            {"nodes":[
              {"id":"a","type":"HUMAN","role":"APPROVER","next":"b"},
              {"id":"b","type":"HUMAN","role":"APPROVER","next":"a"}]}
            """;
    private static final String VALID_DEF = """
            {"nodes":[
              {"id":"risk","type":"AUTO_CHECK","skill":"risk-review","next":"approval"},
              {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24,"next":null}]}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;

    @Test
    void validateDraftWithCycleReportsErrors() throws Exception {
        String id = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "validate-cycle", CYCLE_DEF);
        MvcResult result = mockMvc.perform(post("/api/approval-workflows/{id}/validate", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(false))
                .andReturn();
        JsonNode data = TestSupport.dataOf(result, objectMapper);
        assertThat(data.path("errors")).isNotEmpty();
        assertThat(data.path("errors").toString()).contains("cycle");
        assertThat(data.path("nodeCount").asInt()).isEqualTo(2);
    }

    @Test
    void publishInvalidDefinitionIsRejected() throws Exception {
        String id = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "validate-publish-bad", CYCLE_DEF);
        mockMvc.perform(post("/api/approval-workflows/{id}/publish", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(get("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void fixingDraftAllowsValidationAndPublish() throws Exception {
        String id = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "validate-fix", CYCLE_DEF);
        mockMvc.perform(put("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "definition", objectMapper.readTree(VALID_DEF)))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/approval-workflows/{id}/validate", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.nodeCount").value(2));

        mockMvc.perform(post("/api/approval-workflows/{id}/publish", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty());
    }

    @Test
    void validateUnknownIdIsNotFound() throws Exception {
        mockMvc.perform(post("/api/approval-workflows/{id}/validate", "does-not-exist")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void validateRequiresPlatformAdmin() throws Exception {
        String id = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "validate-forbidden", VALID_DEF);
        mockMvc.perform(post("/api/approval-workflows/{id}/validate", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.auditor(jwtService)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }
}
