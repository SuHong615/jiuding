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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.agent.skill.SkillService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Contract tests for /api/skills/* (docs/02 搂4). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillApiTest {

    private static final String API_KEY = "dev-mcp-key";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SkillService skillService;

    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    @Test
    void listsTheFiveBuiltInSkills() throws Exception {
        mockMvc.perform(get("/api/skills").param("source", "BUILTIN").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data", hasSize(5)))
                .andExpect(jsonPath("$.data[?(@.id == 'risk-review')]", hasSize(1)));
    }

    @Test
    void returnsSkillDetailWithManifestAndRules() throws Exception {
        mockMvc.perform(get("/api/skills/risk-review").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value("risk-review"))
                .andExpect(jsonPath("$.data.instructions").isNotEmpty())
                .andExpect(jsonPath("$.data.rules.rules", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.data.rules.rules[0].id").isNotEmpty());
    }

    @Test
    void reloadRescansSkillsDirectory() throws Exception {
        mockMvc.perform(post("/api/skills/reload").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reloaded", greaterThanOrEqualTo(5)))
                .andExpect(jsonPath("$.data.skills", hasSize(greaterThanOrEqualTo(5))));
    }

    @Test
    void runsRiskReviewAndReturnsDecisionWithTrace() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "tenantId", "t-risky",
                "input", Map.of(
                        "tenantCode", "risky-casino",
                        "plan", "PRO",
                        "region", "cn-shanghai",
                        "quotaSeats", 150,
                        "contactEmail", "owner@mailinator.com",
                        "attributes", Map.of("industry", "gambling"))));

        mockMvc.perform(post("/api/skills/risk-review/run")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.skillId").value("risk-review"))
                .andExpect(jsonPath("$.data.verdict").value("FAIL"))
                .andExpect(jsonPath("$.data.decision").value("REJECT"))
                .andExpect(jsonPath("$.data.findings", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.actions", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.trace", hasSize(greaterThanOrEqualTo(6))));
    }

    @Test
    void approvalPolicyRunExposesRoleAndSlaActions() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "input", Map.of("plan", "PRO", "riskLevel", "MEDIUM", "riskScore", 30)));

        mockMvc.perform(post("/api/skills/approval-policy/run")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.actions[?(@.action == 'requireRole')].value", hasItem("L1")))
                .andExpect(jsonPath("$.data.actions[?(@.action == 'setSlaHours')].value", hasItem(24)));
    }

    @Test
    void unknownSkillReturnsSkillNotFoundCode() throws Exception {
        // tenancy-common maps SKILL_NOT_FOUND (54400) to HTTP 404 (resources that do not
        // exist must not look like malformed requests); the stable business code is what
        // clients switch on.
        mockMvc.perform(post("/api/skills/nope/run")
                        .header("X-API-Key", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));
    }

    @Test
    void requestsWithoutCredentialsAreRejected() throws Exception {
        mockMvc.perform(get("/api/skills"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void ruleHitsArePersistedForRuns() {
        var result = skillService.run("risk-review",
                Map.of("plan", "PRO", "quotaSeats", 50, "contactEmail", "ops@acme.com"), "t-acme");
        org.assertj.core.api.Assertions.assertThat(result.trace()).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(result.runId()).isNotBlank();
        Integer persisted = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM rule_hits WHERE run_id = ?", Integer.class, result.runId());
        org.assertj.core.api.Assertions.assertThat(persisted)
                .isEqualTo(result.trace().size());
        // v5: built-ins are mirrored into skill_packages (source=BUILTIN, APPROVED, enabled)
        Integer builtinRows = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM skill_packages WHERE source = 'BUILTIN' AND enabled = TRUE",
                Integer.class);
        org.assertj.core.api.Assertions.assertThat(builtinRows).isEqualTo(5);
    }
}
