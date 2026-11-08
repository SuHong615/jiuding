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
package com.suhong.jiuding.agent.skill;

import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import com.suhong.jiuding.agent.rule.RuleEngine;
import com.suhong.jiuding.agent.rule.RuleSet;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Validates the four shipped skill packages in {@code skills/} (docs/04).
 *
 * <p>The {@code examples/} folder is consumed here, so a broken example or a
 * broken rule package fails the build.
 */
class SkillPackTest {

    private static final RuleEngine ENGINE = new RuleEngine();
    private static Map<String, SkillDefinition> skills;

    @BeforeAll
    static void loadShippedSkillPacks() {
        TenancyAgentProperties properties = new TenancyAgentProperties();
        properties.setSkillsDir("../skills");
        skills = new SkillLoader(properties).loadAll().stream()
                .collect(Collectors.toMap(SkillDefinition::id, Function.identity()));
    }

    @Test
    void fiveBuiltInSkillPacksArePresentWithValidManifests() {
        assertThat(skills.keySet()).containsExactlyInAnyOrder(
                "tenant-intake", "risk-review", "approval-policy", "provisioning", "tenant-rating");
        assertThat(skills.get("tenant-intake").type()).isEqualTo("INTAKE");
        assertThat(skills.get("risk-review").type()).isEqualTo("RISK");
        assertThat(skills.get("approval-policy").type()).isEqualTo("APPROVAL");
        assertThat(skills.get("provisioning").type()).isEqualTo("PROVISIONING");
        assertThat(skills.get("tenant-rating").type()).isEqualTo("RATING");
        skills.values().forEach(definition -> {
            assertThat(definition.manifest().version()).isNotBlank();
            assertThat(definition.manifest().description()).isNotBlank();
            assertThat(definition.instructions()).isNotBlank();
            assertThat(definition.path()).contains("skills");
        });
    }

    @Test
    void riskReviewCoversIndustryRegionQuotaEmailAndBlacklist() {
        RuleSet rules = skills.get("risk-review").rules();
        assertThat(rules.rules()).hasSizeGreaterThanOrEqualTo(6);
        List<String> ids = rules.rules().stream().map(rule -> rule.id()).toList();
        assertThat(ids).contains("R-RISK-001", "R-RISK-002", "R-RISK-003", "R-RISK-004",
                "R-RISK-005", "R-RISK-006");
        assertThat(rules.rules().stream().map(rule -> rule.when().describe()).collect(Collectors.joining(" ")))
                .contains("attributes.industry", "region", "quotaSeats", "contactEmail",
                        "attributes.blacklist");
    }

    @Test
    void riskReviewLowRiskExampleAutoApproves() {
        SkillRunResult result = run("risk-review", example("risk-review", "low-risk.input.json"));
        assertThat(result.decision()).isEqualTo("AUTO_APPROVE");
        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.score()).isEqualTo(-5);
        assertThat(riskLevelAction(result)).isEqualTo("LOW");
    }

    @Test
    void riskReviewHighRiskExampleRejectsWithFindings() {
        SkillRunResult result = run("risk-review", example("risk-review", "high-risk.input.json"));
        assertThat(result.decision()).isEqualTo("REJECT");
        assertThat(result.verdict()).isEqualTo("FAIL");
        assertThat(result.score()).isEqualTo(125);
        assertThat(result.findings()).extracting(Finding::ruleId)
                .contains("R-RISK-001", "R-RISK-002", "R-RISK-004", "R-RISK-005");
        assertThat(riskLevelAction(result)).isEqualTo("HIGH");
    }

    @Test
    void approvalPolicyLowRiskAutoApprovesWithRoleAndSla() {
        SkillRunResult result = run("approval-policy", example("approval-policy", "auto-approve.input.json"));
        assertThat(result.decision()).isEqualTo("AUTO_APPROVE");
        assertThat(actionValue(result, "requireRole")).isEqualTo("SYSTEM");
        assertThat(actionValue(result, "setSlaHours")).isEqualTo("4");
        assertThat(actionValue(result, "addTag")).isEqualTo("auto-approve");
    }

    @Test
    void approvalPolicyHighRiskEnterpriseDemandsL2AndRejectsOnHighScore() {
        SkillRunResult result = run("approval-policy", example("approval-policy", "l2-review.input.json"));
        assertThat(result.decision()).isEqualTo("REJECT");
        assertThat(actionValue(result, "requireRole")).isEqualTo("L2");
        assertThat(actionValue(result, "setSlaHours")).isEqualTo("8");
        assertThat(example("approval-policy", "l2-review.expected.json").get("score"))
                .isEqualTo(result.score());
    }

    @Test
    void provisioningPlansResourcesWithoutDecision() {
        SkillRunResult result = run("provisioning", example("provisioning", "enterprise.input.json"));
        assertThat(result.decision()).isEqualTo("NONE");
        assertThat(result.verdict()).isEqualTo("PASS");
        List<String> tags = result.actions().stream()
                .filter(action -> "addTag".equals(action.action()))
                .map(action -> String.valueOf(action.value()))
                .toList();
        assertThat(tags).contains("tier:enterprise", "isolation:dedicated-schema", "region:resolved");
    }

    @Test
    void tenantIntakeValidatesAndNormalizes() {
        SkillRunResult valid = run("tenant-intake", example("tenant-intake", "valid.input.json"));
        assertThat(valid.verdict()).isEqualTo("PASS");
        assertThat(valid.decision()).isEqualTo("AUTO_APPROVE");
        assertThat(valid.findings()).extracting(Finding::ruleId).contains("R-INTAKE-004", "R-INTAKE-005");

        SkillRunResult invalid = run("tenant-intake", example("tenant-intake", "invalid.input.json"));
        assertThat(invalid.verdict()).isEqualTo("FAIL");
        assertThat(invalid.decision()).isEqualTo("REJECT");
        assertThat(invalid.findings()).extracting(Finding::ruleId).contains("R-INTAKE-001");
    }

    // ------------------------------------------------------------------ helpers

    private static SkillRunResult run(String skillId, Map<String, Object> input) {
        return ENGINE.run(skills.get(skillId).rules(), input, "t-test", "run-" + skillId, "scripted");
    }

    private static String riskLevelAction(SkillRunResult result) {
        return actionValue(result, "setRiskLevel");
    }

    private static String actionValue(SkillRunResult result, String action) {
        String value = null;
        for (Action item : result.actions()) {
            if (action.equals(item.action()) && item.value() != null) {
                value = String.valueOf(item.value());
            }
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> example(String skillId, String file) {
        try {
            java.nio.file.Path path = java.nio.file.Path.of("../skills", skillId, "examples", file);
            String json = java.nio.file.Files.readString(path, java.nio.charset.StandardCharsets.UTF_8);
            return new com.fasterxml.jackson.databind.ObjectMapper().readValue(json, LinkedHashMap.class);
        } catch (Exception e) {
            throw new IllegalStateException("failed to read example " + skillId + "/" + file, e);
        }
    }
}
