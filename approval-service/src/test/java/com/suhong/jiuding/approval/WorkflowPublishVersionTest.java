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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Publishing archives the previously published version of the same code. */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:approval_wf_publish;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "tenancy.approval.scan-interval-ms=3600000",
        "tenancy.approval.scan-initial-delay-ms=3600000"
})
class WorkflowPublishVersionTest {

    private static final String DEF_V1 = """
            {"nodes":[
              {"id":"risk","type":"AUTO_CHECK","skill":"risk-review","next":"approval"},
              {"id":"approval","type":"HUMAN","role":"APPROVER","slaHours":24,"next":null}]}
            """;
    private static final String DEF_V2 = """
            {"nodes":[
              {"id":"stage","type":"PARALLEL","next":"approval","nodes":[
                {"id":"check","type":"AUTO_CHECK","skill":"risk-review"}]},
              {"id":"approval","type":"HUMAN","role":"SENIOR_APPROVER","slaHours":8,"next":null}]}
            """;

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;

    @Test
    void publishingNewVersionArchivesPrevious() throws Exception {
        String v1 = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "version-flow", DEF_V1);
        JsonNode published1 = TestSupport.dataOf(WorkflowTestSupport.publish(mockMvc, jwtService, v1), objectMapper);
        assertThat(published1.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published1.path("publishedAt").isMissingNode()).isFalse();

        String v2 = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "version-flow", DEF_V2);
        JsonNode draft2 = TestSupport.dataOf(
                WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v2), objectMapper);
        assertThat(draft2.path("version").asInt()).isEqualTo(2);
        assertThat(draft2.path("status").asText()).isEqualTo("DRAFT");
        assertThat(draft2.path("publishedAt").isMissingNode()).isTrue();

        JsonNode published2 = TestSupport.dataOf(WorkflowTestSupport.publish(mockMvc, jwtService, v2), objectMapper);
        assertThat(published2.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(published2.path("version").asInt()).isEqualTo(2);

        JsonNode archived1 = TestSupport.dataOf(
                WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v1), objectMapper);
        assertThat(archived1.path("status").asText()).isEqualTo("ARCHIVED");

        MvcResult list = mockMvc.perform(get("/api/approval-workflows")
                        .param("code", "version-flow")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = TestSupport.dataOf(list, objectMapper);
        assertThat(items).hasSize(2);
        long published = 0;
        for (JsonNode item : items) {
            if ("PUBLISHED".equals(item.path("status").asText())) {
                published++;
                assertThat(item.path("version").asInt()).isEqualTo(2);
            }
        }
        assertThat(published).isEqualTo(1);
    }

    @Test
    void publishIsIdempotent() throws Exception {
        String id = WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "version-idem", DEF_V1);
        JsonNode again = TestSupport.dataOf(WorkflowTestSupport.publish(mockMvc, jwtService, id), objectMapper);
        assertThat(again.path("status").asText()).isEqualTo("PUBLISHED");
        assertThat(again.path("version").asInt()).isEqualTo(1);

        MvcResult list = mockMvc.perform(get("/api/approval-workflows")
                        .param("code", "version-idem")
                        .param("status", "PUBLISHED")
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(TestSupport.dataOf(list, objectMapper)).hasSize(1);
    }

    @Test
    void newVersionAfterArchiveIncrements() throws Exception {
        String v1 = WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "version-next", DEF_V1);
        String v2 = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "version-next", DEF_V2);
        WorkflowTestSupport.publish(mockMvc, jwtService, v2);
        String v3 = WorkflowTestSupport.createWorkflowId(
                mockMvc, jwtService, objectMapper, "version-next", DEF_V1);
        JsonNode data = TestSupport.dataOf(WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v3), objectMapper);
        assertThat(data.path("version").asInt()).isEqualTo(3);
        assertThat(data.path("status").asText()).isEqualTo("DRAFT");

        JsonNode archived = TestSupport.dataOf(WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v1), objectMapper);
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");
    }

    @Test
    void archivedVersionCannotBePublishedAgain() throws Exception {
        String v1 = WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "version-archived", DEF_V1);
        String v2 = WorkflowTestSupport.createPublishedWorkflowId(
                mockMvc, jwtService, objectMapper, "version-archived", DEF_V2);

        mockMvc.perform(post("/api/approval-workflows/{id}/publish", v1)
                        .header(WorkflowTestSupport.AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        JsonNode archived = TestSupport.dataOf(WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v1), objectMapper);
        assertThat(archived.path("status").asText()).isEqualTo("ARCHIVED");
        JsonNode published = TestSupport.dataOf(WorkflowTestSupport.getWorkflow(mockMvc, jwtService, v2), objectMapper);
        assertThat(published.path("status").asText()).isEqualTo("PUBLISHED");
    }
}
