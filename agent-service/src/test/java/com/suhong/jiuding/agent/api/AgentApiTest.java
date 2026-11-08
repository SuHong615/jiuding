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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** End-to-end contract tests for the offline (scripted) agent runtime (docs/02 §4). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AgentApiTest {

    private static final String API_KEY = "dev-mcp-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void riskReviewAgentCallsSkillToolAndReturnsStructuredJson() throws Exception {
        JsonNode data = runAgent(Map.of(
                "agentType", "risk-review",
                "tenantId", "t-risky",
                "input", Map.of(
                        "tenantCode", "risky-casino",
                        "plan", "PRO",
                        "region", "cn-shanghai",
                        "quotaSeats", 150,
                        "contactEmail", "owner@mailinator.com",
                        "attributes", Map.of("industry", "gambling"))));

        assertThat(data.path("agentType").asText()).isEqualTo("risk-review");
        assertThat(data.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(data.path("model").asText()).isEqualTo("scripted");
        assertThat(data.path("steps").size()).isGreaterThanOrEqualTo(2);
        assertThat(data.path("steps").get(0).path("action").asText()).isEqualTo("skill.run");
        assertThat(data.path("steps").get(0).path("observation").asText()).isNotEmpty();
        assertThat(data.path("output").path("riskLevel").asText()).isEqualTo("HIGH");
        assertThat(data.path("output").path("decision").asText()).isEqualTo("REJECT");
        assertThat(data.path("output").path("score").asInt()).isEqualTo(125);
        assertThat(data.path("output").path("reasons").isArray()).isTrue();
    }

    @Test
    void approvalAgentDerivesRoleAndSlaFromPolicySkill() throws Exception {
        JsonNode data = runAgent(Map.of(
                "agentType", "approval",
                "tenantId", "t-acme",
                "input", Map.of("plan", "PRO", "riskLevel", "LOW", "riskScore", 5)));

        assertThat(data.path("output").path("decision").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(data.path("output").path("requireRole").asText()).isEqualTo("SYSTEM");
        assertThat(data.path("output").path("slaHours").asInt()).isEqualTo(4);
        assertThat(data.path("output").path("autoApproved").asBoolean()).isTrue();
    }

    @Test
    void provisioningAgentBuildsResourcePlan() throws Exception {
        JsonNode data = runAgent(Map.of(
                "agentType", "provisioning",
                "tenantId", "t-bigco",
                "input", Map.of(
                        "tenantCode", "bigco",
                        "plan", "ENTERPRISE",
                        "region", "cn-shanghai",
                        "isolationMode", "DEDICATED_SCHEMA",
                        "riskLevel", "MEDIUM")));

        JsonNode output = data.path("output");
        assertThat(output.path("status").asText()).isEqualTo("PLANNED");
        assertThat(output.path("namespace").asText()).isEqualTo("ns-bigco");
        assertThat(output.path("schema").asText()).isEqualTo("tenant_bigco");
        assertThat(output.path("quotas").path("seats").asInt()).isEqualTo(500);
        assertThat(output.path("quotas").path("storageGb").asInt()).isEqualTo(2048);
        assertThat(output.path("steps").isArray()).isTrue();
        assertThat(output.path("verdict").asText()).isEqualTo("PASS");
    }

    @Test
    void tenantIntakeAgentNormalizesAliases() throws Exception {
        JsonNode data = runAgent(Map.of(
                "agentType", "tenant-intake",
                "tenantId", "t-new",
                "input", Map.of(
                        "tenantCode", "newco",
                        "name", "NewCo 科技",
                        "contactEmail", "ops@newco.com",
                        "plan", "standard",
                        "region", "shanghai")));

        JsonNode output = data.path("output");
        assertThat(output.path("status").asText()).isEqualTo("INTAKE_OK");
        assertThat(output.path("normalized").path("plan").asText()).isEqualTo("PRO");
        assertThat(output.path("normalized").path("region").asText()).isEqualTo("cn-shanghai");
        assertThat(output.path("normalized").path("isolationMode").asText()).isEqualTo("SHARED_SCHEMA");
    }

    @Test
    void agentRunsArePersistedAndQueryable() throws Exception {
        JsonNode data = runAgent(Map.of(
                "agentType", "risk-review",
                "tenantId", "t-acme",
                "input", Map.of("plan", "PRO", "quotaSeats", 50, "contactEmail", "ops@acme.com")));
        String runId = data.path("runId").asText();

        mockMvc.perform(get("/api/agents/runs/" + runId).header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.steps", hasSize(greaterThanOrEqualTo(2))))
                .andExpect(jsonPath("$.data.output.decision").value("AUTO_APPROVE"));

        mockMvc.perform(get("/api/agents/runs")
                        .param("agentType", "risk-review")
                        .param("page", "0")
                        .param("size", "10")
                        .header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.items[0].agentType").value("risk-review"));
    }

    @Test
    void toolsOverviewAndValidationAreExposed() throws Exception {
        mockMvc.perform(get("/api/agents/tools").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[?(@.name == 'skill.run')]", hasSize(1)))
                .andExpect(jsonPath("$.data[?(@.name == 'tenant.get')]", hasSize(1)));

        mockMvc.perform(get("/api/agents/overview").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skills", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.modelProvider").value("scripted"))
                .andExpect(jsonPath("$.data.runs").isNumber());

        mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"agentType\":\"unknown-agent\",\"input\":{}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode runAgent(Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/agents/runs")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode root = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode data = root.path("data");
        assertThat(data.path("status").asText())
                .as("agent run should succeed offline: %s", data)
                .isEqualTo("SUCCEEDED");
        return data;
    }

    @SuppressWarnings("unused")
    private static List<String> toolNames(JsonNode tools) {
        return java.util.stream.StreamSupport.stream(tools.spliterator(), false)
                .map(node -> node.path("name").asText())
                .toList();
    }
}
