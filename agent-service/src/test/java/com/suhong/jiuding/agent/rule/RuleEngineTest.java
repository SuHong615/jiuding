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
package com.suhong.jiuding.agent.rule;

import com.suhong.jiuding.agent.skill.SkillRunResult;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Rule actions, decision mapping, trace and single-rule failure isolation. */
class RuleEngineTest {

    private final RuleEngine engine = new RuleEngine();

    @Test
    void scoreMapsToDecisionAndRiskLevel() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-1", "add 30", leaf("plan", "eq", "PRO"), action("addScore", 30)));

        SkillRunResult result = engine.run(rules, Map.of("plan", "PRO"), "t-acme", "run-1", "scripted");

        assertThat(result.score()).isEqualTo(30);
        assertThat(result.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.verdict()).isEqualTo("WARN");
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).matched()).isTrue();
        assertThat(result.trace().get(0).reason()).contains("plan eq PRO");
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.model()).isEqualTo("scripted");
    }

    @Test
    void lowScoreAutoApprovesAndHighScoreRejects() {
        RuleSet rules = ruleSet(0, 20, 80);

        assertThat(engine.run(rules, Map.of(), null, "run-a", "scripted").decision())
                .isEqualTo("AUTO_APPROVE");
        assertThat(engine.run(rules, Map.of(), null, "run-a", "scripted").verdict())
                .isEqualTo("PASS");

        RuleSet high = ruleSet(0, 20, 80,
                rule("R-HIGH", "add 90", leaf("plan", "eq", "PRO"), action("addScore", 90)));
        SkillRunResult rejected = engine.run(high, Map.of("plan", "PRO"), null, "run-b", "scripted");
        assertThat(rejected.decision()).isEqualTo("REJECT");
        assertThat(rejected.verdict()).isEqualTo("FAIL");
    }

    @Test
    void explicitSetDecisionOverridesScoreMapping() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-BLACKLIST", "blacklist", leaf("blacklist", "eq", true),
                        action("addScore", 1), action("setDecision", "REJECT")));

        SkillRunResult result = engine.run(rules, Map.of("blacklist", true), null, "run-c", "scripted");
        assertThat(result.decision()).isEqualTo("REJECT");
        assertThat(result.actions()).anyMatch(a -> "setDecision".equals(a.action()));
    }

    @Test
    void mostRestrictiveExplicitDecisionWins() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-A", "reject", leaf("flag", "eq", true), action("setDecision", "REJECT")),
                rule("R-B", "auto", leaf("flag", "eq", true), action("setDecision", "AUTO_APPROVE")));

        assertThat(engine.run(rules, Map.of("flag", true), null, "run-d", "scripted").decision())
                .isEqualTo("REJECT");
    }

    @Test
    void allSevenActionsAreExecutedAndSurfaced() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-ACTIONS", "all actions", leaf("plan", "eq", "PRO"),
                        action("addScore", 10),
                        actionWithMessage("addFinding", null, "发现风险", "MEDIUM"),
                        action("setDecision", "NEEDS_REVIEW"),
                        action("setRiskLevel", "HIGH"),
                        action("requireRole", "L2"),
                        action("setSlaHours", 8),
                        action("addTag", "manual-review")));

        SkillRunResult result = engine.run(rules, Map.of("plan", "PRO"), null, "run-e", "scripted");

        assertThat(result.score()).isEqualTo(10);
        assertThat(result.actions()).hasSize(7);
        assertThat(result.findings()).hasSize(1);
        assertThat(result.findings().get(0).severity()).isEqualTo("MEDIUM");
        assertThat(result.decision()).isEqualTo("NEEDS_REVIEW");
        assertThat(result.actions()).extracting("action").contains("requireRole", "setSlaHours", "addTag");
        assertThat(result.actions().stream().filter(a -> "setRiskLevel".equals(a.action())).findFirst()
                .orElseThrow().value()).isEqualTo("HIGH");
    }

    @Test
    void failingRuleDoesNotAbortRunAndMarksWarn() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-BAD-REGEX", "bad regex", leaf("email", "matches", "("), action("addScore", 10)),
                rule("R-OK", "normal", leaf("plan", "eq", "PRO"), action("addScore", 25)));

        SkillRunResult result = engine.run(rules, Map.of("email", "ops@acme.com", "plan", "PRO"),
                null, "run-f", "scripted");

        assertThat(result.verdict()).isEqualTo("WARN"); // error counted, then normal rule applied
        assertThat(result.score()).isEqualTo(25);
        assertThat(result.trace()).hasSize(2);
        assertThat(result.trace().stream().filter(t -> "R-BAD-REGEX".equals(t.ruleId())).findFirst()
                .orElseThrow().reason()).startsWith("error:");
        assertThat(result.trace().stream().filter(t -> "R-OK".equals(t.ruleId())).findFirst()
                .orElseThrow().matched()).isTrue();
    }

    @Test
    void unmatchedRulesAreTracedWithReasons() {
        RuleSet rules = ruleSet(0, 20, 80,
                rule("R-1", "not matched", leaf("plan", "eq", "ENTERPRISE"), action("addScore", 10)));

        SkillRunResult result = engine.run(rules, Map.of("plan", "PRO"), null, "run-g", "scripted");
        assertThat(result.trace()).hasSize(1);
        assertThat(result.trace().get(0).matched()).isFalse();
        assertThat(result.trace().get(0).reason()).contains("ENTERPRISE");
        assertThat(result.score()).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private static RuleSet ruleSet(int initialScore, int approveMax, int rejectMin, Rule... rules) {
        return new RuleSet("test-skill", "1.0.0", initialScore, List.of(rules),
                new DecisionPolicy(approveMax, rejectMin, List.of(
                        new DecisionPolicy.RiskLevel("LOW", approveMax),
                        new DecisionPolicy.RiskLevel("MEDIUM", 60),
                        new DecisionPolicy.RiskLevel("HIGH", 100)), true));
    }

    private static Rule rule(String id, String description, Map<String, Object> when, Map<String, Object> action) {
        return new Rule(id, description, "HIGH", ConditionParser.parse(when),
                List.of(RuleAction.from(action)));
    }

    private static Rule rule(String id, String description, Map<String, Object> when,
                             Map<String, Object>... actions) {
        List<RuleAction> parsed = new java.util.ArrayList<>();
        for (Map<String, Object> action : actions) {
            parsed.add(RuleAction.from(action));
        }
        return new Rule(id, description, "HIGH", ConditionParser.parse(when), parsed);
    }

    private static Map<String, Object> leaf(String field, String op, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("op", op);
        map.put("value", value);
        return map;
    }

    private static Map<String, Object> action(String name, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("action", name);
        map.put("value", value);
        return map;
    }

    private static Map<String, Object> actionWithMessage(String name, Object value, String message, String severity) {
        Map<String, Object> map = action(name, value);
        map.put("message", message);
        map.put("severity", severity);
        return map;
    }
}
