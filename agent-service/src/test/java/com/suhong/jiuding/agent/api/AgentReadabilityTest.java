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
package com.suhong.jiuding.agent.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Agent run readability: displayName/purpose/summary + Chinese step titles (docs/15 §3). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentReadabilityTest {

    private static final String API_KEY = "dev-mcp-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void riskReviewRunExplainsWhatItDid() throws Exception {
        mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentType", "risk-review",
                                "tenantId", "t-acme",
                                "input", Map.of("plan", "PRO", "quotaSeats", 50,
                                        "contactEmail", "ops@acme.com")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("租户风险审查"))
                .andExpect(jsonPath("$.data.purpose").value(not(org.hamcrest.Matchers.emptyString())))
                .andExpect(jsonPath("$.data.summary", containsString("风险审查")))
                .andExpect(jsonPath("$.data.summary", containsString("自动通过")))
                .andExpect(jsonPath("$.data.steps", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.data.steps[0].title").value("调用风险规则"))
                .andExpect(jsonPath("$.data.steps[0].actionLabel").value("执行规则"))
                .andExpect(jsonPath("$.data.steps[1].title").value("生成结论"))
                .andExpect(jsonPath("$.data.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.input.plan").value("PRO"));
    }

    @Test
    void persistedRunKeepsReadableMetadata() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentType", "provisioning",
                                "tenantId", "t-bigco",
                                "input", Map.of("tenantCode", "bigco", "plan", "ENTERPRISE",
                                        "isolationMode", "DEDICATED_SCHEMA", "riskLevel", "MEDIUM")))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(
                new String(result.getResponse().getContentAsByteArray(), java.nio.charset.StandardCharsets.UTF_8))
                .path("data");
        String runId = data.path("runId").asText();
        assertThat(data.path("summary").asText()).contains("资源开通");

        mockMvc.perform(get("/api/agents/runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("资源开通"))
                .andExpect(jsonPath("$.data.purpose").isNotEmpty())
                .andExpect(jsonPath("$.data.summary", containsString("资源开通")))
                .andExpect(jsonPath("$.data.summary", containsString("ns-bigco")))
                .andExpect(jsonPath("$.data.steps[0].title").value("生成资源计划"))
                .andExpect(jsonPath("$.data.steps[1].title").value("生成结论"));

        mockMvc.perform(get("/api/agents/runs").param("agentType", "provisioning")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].displayName").value("资源开通"))
                .andExpect(jsonPath("$.data.items[0].summary").isNotEmpty());
    }

    @Test
    void approvalAndIntakeRunsHaveTailoredSummaries() throws Exception {
        mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentType", "approval",
                                "tenantId", "t-acme",
                                "input", Map.of("plan", "PRO", "riskLevel", "MEDIUM", "riskScore", 30)))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("审批策略"))
                .andExpect(jsonPath("$.data.summary", containsString("人工审批")))
                .andExpect(jsonPath("$.data.steps[0].title").value("计算审批策略"));

        mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "agentType", "tenant-intake",
                                "tenantId", "t-new",
                                "input", Map.of("tenantCode", "newco", "name", "NewCo",
                                        "contactEmail", "ops@newco.com", "plan", "standard",
                                        "region", "shanghai")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("租户准入校验"))
                .andExpect(jsonPath("$.data.summary", containsString("租户准入")))
                .andExpect(jsonPath("$.data.steps[0].title").value("校验租户资料"));
    }
}
