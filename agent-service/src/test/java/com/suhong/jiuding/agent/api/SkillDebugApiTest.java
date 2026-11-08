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

import com.suhong.jiuding.agent.support.SkillCenterTestSupport;
import com.suhong.jiuding.common.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** In-memory skill debug endpoint: matched/unmatched rules, version selection, no persistence. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SkillDebugApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private String admin() {
        return SkillCenterTestSupport.bearer(SkillCenterTestSupport.adminToken(jwtService));
    }

    @Test
    void debugBuiltinSkillExplainsMatchedAndUnmatchedRules() throws Exception {
        Integer hitsBefore = count("rule_hits");
        Integer reviewsBefore = count("skill_reviews");

        String body = """
                {"input":{"tenantCode":"risky-casino","plan":"PRO","region":"cn-shanghai",
                 "quotaSeats":150,"contactEmail":"owner@mailinator.com",
                 "attributes":{"industry":"gambling"}}}
                """;
        mockMvc.perform(post("/api/skills/risk-review/debug")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.version").value("1.0.0"))
                .andExpect(jsonPath("$.data.decision").value("REJECT"))
                .andExpect(jsonPath("$.data.actions", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.data.matchedRules", hasSize(greaterThanOrEqualTo(6))))
                .andExpect(jsonPath("$.data.matchedRules[?(@.ruleId == 'R-RISK-001')].matched", hasItem(true)))
                .andExpect(jsonPath("$.data.matchedRules[?(@.ruleId == 'R-RISK-003')].matched", hasItem(false)))
                .andExpect(jsonPath("$.data.matchedRules[?(@.ruleId == 'R-RISK-002')].description").isNotEmpty())
                .andExpect(jsonPath("$.data.durationMs").isNumber())
                .andExpect(jsonPath("$.data.evaluatedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.notes", hasSize(greaterThanOrEqualTo(1))));

        // debug is read-only: no rule_hits and no review audit rows are written
        org.assertj.core.api.Assertions.assertThat(count("rule_hits")).isEqualTo(hitsBefore);
        org.assertj.core.api.Assertions.assertThat(count("skill_reviews")).isEqualTo(reviewsBefore);
    }

    @Test
    void explicitVersionSelectsThatVersionsRules() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, "1.0.0", 50, "五十");
        upload(id, "1.0.1", 70, "七十");

        debug(id, "1.0.0", 50);
        debug(id, "1.0.1", 70);
        // no version -> newest stored version (1.0.1)
        debug(id, null, 70);
    }

    @Test
    void skillWithoutRulesReturnsNotesInsteadOf500() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                id: %s
                name: 无规则技能
                version: 1.0.0
                type: RISK
                description: 没有 rules.yaml
                ---
                # 角色
                只输出 JSON。
                """.formatted(id));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(files))
                        .header("Authorization", admin()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/skills/" + id + "/debug")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("NONE"))
                .andExpect(jsonPath("$.data.matchedRules", hasSize(0)))
                .andExpect(jsonPath("$.data.notes", hasItem(containsString("未配置"))));
    }

    @Test
    void unknownVersionReturns404() throws Exception {
        String id = SkillCenterTestSupport.uniqueSkillId();
        upload(id, "1.0.0", 50, "五十");
        mockMvc.perform(post("/api/skills/" + id + "/debug")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":{},\"version\":\"9.9.9\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(54400));
    }

    private void debug(String id, String version, int expectedScore) throws Exception {
        String body = version == null
                ? "{\"input\":{\"plan\":\"ENTERPRISE\"}}"
                : "{\"input\":{\"plan\":\"ENTERPRISE\"},\"version\":\"" + version + "\"}";
        mockMvc.perform(post("/api/skills/" + id + "/debug")
                        .header("Authorization", admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.decision").value("NEEDS_REVIEW"))
                .andExpect(jsonPath("$.data.score").value(expectedScore))
                .andExpect(jsonPath("$.data.matchedRules[0].ruleId").value("R-DBG-001"))
                .andExpect(jsonPath("$.data.matchedRules[0].matched").value(true));
        if (version != null) {
            mockMvc.perform(get("/api/skills/" + id + "/versions/" + version)
                            .header("Authorization", admin()))
                    .andExpect(status().isOk());
        }
    }

    private void upload(String id, String version, int score, String message) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("SKILL.md", """
                ---
                id: %s
                name: 调试技能
                version: %s
                type: RISK
                description: 调试用技能
                ---
                # 角色
                调试。
                """.formatted(id, version));
        files.put("rules.yaml", """
                skillId: %s
                version: %s
                initialScore: 0
                rules:
                  - id: R-DBG-001
                    description: ENTERPRISE 命中
                    severity: MEDIUM
                    when: { field: plan, op: eq, value: ENTERPRISE }
                    then:
                      - { action: addScore, value: %d }
                      - { action: addFinding, message: %s, severity: MEDIUM }
                decision:
                  autoApproveMaxScore: 20
                  rejectMinScore: 80
                  riskLevels:
                    - { level: LOW, maxScore: 20 }
                    - { level: MEDIUM, maxScore: 60 }
                    - { level: HIGH, maxScore: 100 }
                """.formatted(id, version, score, message));
        mockMvc.perform(multipart("/api/skills/upload")
                        .file(SkillCenterTestSupport.zipPart(files))
                        .header("Authorization", admin()))
                .andExpect(status().isOk());
    }

    private Integer count(String table) {
        List<String> allowed = List.of("rule_hits", "skill_reviews");
        if (!allowed.contains(table)) {
            throw new IllegalArgumentException("unexpected table: " + table);
        }
        Integer value = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }
}
