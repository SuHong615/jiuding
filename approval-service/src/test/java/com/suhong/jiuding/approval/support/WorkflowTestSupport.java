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
package com.suhong.jiuding.approval.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Helpers for the v12 workflow-definition API tests. */
public final class WorkflowTestSupport {

    public static final String AUTH = HttpHeaders.AUTHORIZATION;

    private WorkflowTestSupport() {
    }

    public static String workflowBody(ObjectMapper mapper, String code, String name,
                                      String definitionJson) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("definition", mapper.readTree(definitionJson));
        return mapper.writeValueAsString(body);
    }

    /** Creates a DRAFT workflow as platform admin and returns the created view. */
    public static MvcResult createWorkflow(MockMvc mockMvc, JwtService jwtService, ObjectMapper mapper,
                                           String code, String definitionJson) throws Exception {
        return mockMvc.perform(post("/api/approval-workflows")
                        .header(AUTH, TestSupport.admin(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(workflowBody(mapper, code, code, definitionJson)))
                .andExpect(status().isOk())
                .andReturn();
    }

    public static String createWorkflowId(MockMvc mockMvc, JwtService jwtService, ObjectMapper mapper,
                                          String code, String definitionJson) throws Exception {
        return TestSupport.dataOf(createWorkflow(mockMvc, jwtService, mapper, code, definitionJson), mapper)
                .path("id").asText();
    }

    /** Publishes a workflow as platform admin and returns the published view. */
    public static MvcResult publish(MockMvc mockMvc, JwtService jwtService, String id) throws Exception {
        return mockMvc.perform(post("/api/approval-workflows/{id}/publish", id)
                        .header(AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
    }

    public static String createPublishedWorkflowId(MockMvc mockMvc, JwtService jwtService, ObjectMapper mapper,
                                                   String code, String definitionJson) throws Exception {
        String id = createWorkflowId(mockMvc, jwtService, mapper, code, definitionJson);
        publish(mockMvc, jwtService, id);
        return id;
    }

    public static MvcResult getWorkflow(MockMvc mockMvc, JwtService jwtService, String id) throws Exception {
        return mockMvc.perform(get("/api/approval-workflows/{id}", id)
                        .header(AUTH, TestSupport.admin(jwtService)))
                .andExpect(status().isOk())
                .andReturn();
    }

    public static String approvalBody(ObjectMapper mapper, String tenantId, String tenantCode,
                                      String plan, String riskLevel, Integer riskScore,
                                      String workflowCode) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantId", tenantId);
        body.put("tenantCode", tenantCode);
        body.put("plan", plan);
        body.put("riskLevel", riskLevel);
        if (riskScore != null) {
            body.put("riskScore", riskScore);
        }
        body.put("reasons", List.of("workflow definition test"));
        body.put("requestedBy", "tester");
        if (workflowCode != null) {
            body.put("workflowCode", workflowCode);
        }
        return mapper.writeValueAsString(body);
    }

    public static MvcResult createApproval(MockMvc mockMvc, JwtService jwtService, ObjectMapper mapper,
                                           String tenantId, String tenantCode, String plan,
                                           String riskLevel, Integer riskScore, String workflowCode) throws Exception {
        return mockMvc.perform(post("/api/approvals")
                        .header(AUTH, TestSupport.approver(jwtService))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(approvalBody(mapper, tenantId, tenantCode, plan, riskLevel, riskScore, workflowCode)))
                .andExpect(status().isOk())
                .andReturn();
    }
}
