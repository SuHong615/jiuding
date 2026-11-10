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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CRUD + platform-admin authorisation for {@code /api/approval-workflows} (docs/23 §3). */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_wf_crud;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class WorkflowDefinitionCrudTest {

    static final String HUMAN_DEF = """
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
    void adminCanCreateListGetUpdateDeleteDraft() throws Exception {
        MvcResult created = WorkflowTestSupport.createWorkflow(
                mockMvc, jwtService, objectMapper, "crud-flow", HUMAN_DEF);
        JsonNode data = TestSupport.dataOf(created, objectMapper);
        String id = data.path("id").asText();
        assertThat(data.path("code").asText()).isEqualTo("crud-flow");
        assertThat(data.path("status").asText()).isEqualTo("DRAFT");
        assertThat(data.path("version").asInt()).isEqualTo(1);
        assertThat(data.path("definition").path("nodes")).hasSize(2);
        assertThat(data.path("createdBy").asText()).isEqualTo("admin");

        mockMvc.perform(get("/api/approval-workflows")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        // legacy alias kept for the transition period
        mockMvc.perform(get("/api/workflows")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());

        mockMvc.perform(get("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        Map<String, Object> update = new LinkedHashMap<>();
        update.put("name", "updated-name");
        update.put("definition", objectMapper.readTree("""
                {"nodes":[{"id":"only","type":"HUMAN","role":"APPROVER","slaHours":4}]}
                """));
        mockMvc.perform(put("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(update)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("updated-name"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.definition.nodes[0].id").value("only"));

        mockMvc.perform(delete("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));

        mockMvc.perform(get("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void nonPlatformAdminIsForbidden() throws Exception {
        mockMvc.perform(get("/api/approval-workflows")
                        .header(WorkflowTestSupport.AUTH, TestSupport.approver(jwtService)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/approval-workflows")
                        .header(WorkflowTestSupport.AUTH, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WorkflowTestSupport.workflowBody(objectMapper, "forbidden-flow", "x", HUMAN_DEF)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void sameCodeCreatesNextVersion() throws Exception {
        JsonNode first = TestSupport.dataOf(
                WorkflowTestSupport.createWorkflow(mockMvc, jwtService, objectMapper, "crud-version", HUMAN_DEF),
                objectMapper);
        JsonNode second = TestSupport.dataOf(
                WorkflowTestSupport.createWorkflow(mockMvc, jwtService, objectMapper, "crud-version", HUMAN_DEF),
                objectMapper);
        assertThat(first.path("version").asInt()).isEqualTo(1);
        assertThat(second.path("version").asInt()).isEqualTo(2);
        assertThat(first.path("code").asText()).isEqualTo(second.path("code").asText());
    }

    @Test
    void publishedDefinitionCannotBeUpdatedOrDeleted() throws Exception {
        String id = WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "crud-published", HUMAN_DEF);

        mockMvc.perform(put("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nope\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(delete("/api/approval-workflows/{id}", id)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    void invalidCodeIsRejected() throws Exception {
        mockMvc.perform(post("/api/approval-workflows")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(WorkflowTestSupport.workflowBody(
                                objectMapper, "bad code!", "bad", HUMAN_DEF)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
