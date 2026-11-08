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

import com.suhong.jiuding.agent.skill.Action;
import com.suhong.jiuding.agent.skill.Finding;
import com.suhong.jiuding.agent.skill.SkillRunResult;
import com.suhong.jiuding.agent.skill.TraceItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Deterministic rule engine (docs/04-skill-spec.md).
 *
 * <p>Guarantees:
 * <ul>
 *   <li>single-rule failures never abort the run — they are logged as WARN and
 *       recorded in the trace ({@code matched=false, reason="error: ..."});</li>
 *   <li>explicit {@code setDecision}/{@code setRiskLevel} actions override the
 *       score mapping;</li>
 *   <li>output is fully ordered/deterministic, so tests and audit diffs are stable.</li>
 * </ul>
 */
@Component
public class RuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleEngine.class);

    /** Decision collision priority: the most restrictive explicit decision wins. */
    private static final List<String> DECISION_PRIORITY = List.of("REJECT", "NEEDS_REVIEW", "AUTO_APPROVE");

    private static final long WARN_THRESHOLD_MS = 2000L;

    /**
     * Execute a skill rule set against an input document.
     *
     * @param ruleSet  parsed rules (may be empty — then the skill is a passthrough)
     * @param input    request input object
     * @param tenantId optional tenant scope, injected as {@code tenantId} when absent
     * @param runId    run correlation id
     * @param model    model channel label ({@code scripted} or a real model name)
     */
    public SkillRunResult run(RuleSet ruleSet, Map<String, Object> input, String tenantId,
                              String runId, String model) {
        long start = System.currentTimeMillis();
        RuleSet safeRules = ruleSet == null ? RuleSet.empty("unknown", "0") : ruleSet;
        Map<String, Object> context = new LinkedHashMap<>();
        if (input != null) {
            context.putAll(input);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            context.putIfAbsent("tenantId", tenantId);
        }

        int score = safeRules.initialScore();
        List<Finding> findings = new ArrayList<>();
        List<Action> actions = new ArrayList<>();
        List<TraceItem> trace = new ArrayList<>();
        String explicitDecision = null;
        String explicitRiskLevel = null;
        int ruleErrors = 0;

        for (Rule rule : safeRules.rules()) {
            try {
                Condition.Evaluation evaluation = rule.when().evaluate(context);
                trace.add(new TraceItem(rule.id(), rule.severity(), evaluation.matched(), evaluation.reason()));
                if (!evaluation.matched()) {
                    continue;
                }
                for (RuleAction action : rule.then()) {
                    Execution execution = apply(rule, action, score);
                    score = execution.score();
                    if (execution.finding() != null) {
                        findings.add(execution.finding());
                    }
                    if (execution.decision() != null) {
                        explicitDecision = moreRestrictive(explicitDecision, execution.decision());
                    }
                    if (execution.riskLevel() != null) {
                        explicitRiskLevel = execution.riskLevel();
                    }
                    actions.add(new Action(rule.id(), action.action(), action.value(), action.message()));
                }
            } catch (Exception ex) {
                ruleErrors++;
                log.warn("rule {} of skill {} failed (continuing): {}", rule.id(), safeRules.skillId(), ex.toString());
                trace.add(new TraceItem(rule.id(), rule.severity(), false, "error: " + ex.getMessage()));
            }
        }

        String riskLevel = explicitRiskLevel != null ? explicitRiskLevel : mapRiskLevel(safeRules.decision(), score);
        String decision = explicitDecision != null ? explicitDecision
                : mapDecision(safeRules.decision(), score);
        String verdict = verdict(decision, findings, ruleErrors);
        long duration = System.currentTimeMillis() - start;
        if (duration > WARN_THRESHOLD_MS) {
            log.warn("skill {} run took {}ms (> {}ms)", safeRules.skillId(), duration, WARN_THRESHOLD_MS);
        }
        SkillRunResult result = new SkillRunResult(safeRules.skillId(), runId, verdict, score,
                decision, findings, actions, trace, duration, model);
        return withRiskLevel(result, riskLevel);
    }

    private record Execution(int score, Finding finding, String decision, String riskLevel) {
    }

    private Execution apply(Rule rule, RuleAction action, int currentScore) {
        String severity = action.severity() == null ? rule.severity() : action.severity();
        switch (action.action()) {
            case "addScore" -> {
                BigDecimal delta = Operator.number(action.value());
                if (delta == null) {
                    throw new IllegalArgumentException("addScore requires a numeric value");
                }
                return new Execution(currentScore + delta.intValue(), null, null, null);
            }
            case "addFinding" -> {
                String message = action.message() == null
                        ? (rule.description() == null ? rule.id() : rule.description())
                        : action.message();
                return new Execution(currentScore,
                        new Finding(rule.id(), severity, message), null, null);
            }
            case "setDecision" -> {
                return new Execution(currentScore, null, decisionName(action.value()), null);
            }
            case "setRiskLevel" -> {
                if (action.value() == null) {
                    throw new IllegalArgumentException("setRiskLevel requires a value");
                }
                return new Execution(currentScore, null, null,
                        String.valueOf(action.value()).toUpperCase(Locale.ROOT));
            }
            case "requireRole", "setSlaHours", "addTag" ->
                    // validation only: the values are surfaced verbatim in `actions`
                    validateActionValue(action);
            default -> throw new IllegalArgumentException("unsupported action: " + action.action());
        }
        return new Execution(currentScore, null, null, null);
    }

    private void validateActionValue(RuleAction action) {
        if (action.value() == null) {
            throw new IllegalArgumentException(action.action() + " requires a value");
        }
        if ("setSlaHours".equals(action.action()) && Operator.number(action.value()) == null) {
            throw new IllegalArgumentException("setSlaHours requires a numeric value");
        }
    }

    private static String decisionName(Object value) {
        if (value == null) {
            throw new IllegalArgumentException("setDecision requires a value");
        }
        String normalized = String.valueOf(value).trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (!DECISION_PRIORITY.contains(normalized)) {
            throw new IllegalArgumentException("invalid decision: " + value
                    + " (expected AUTO_APPROVE|NEEDS_REVIEW|REJECT)");
        }
        return normalized;
    }

    /** REJECT beats NEEDS_REVIEW beats AUTO_APPROVE, regardless of rule order. */
    private static String moreRestrictive(String current, String candidate) {
        if (current == null) {
            return candidate;
        }
        int currentRank = DECISION_PRIORITY.indexOf(current);
        int candidateRank = DECISION_PRIORITY.indexOf(candidate);
        return candidateRank < currentRank ? candidate : current;
    }

    private static String mapDecision(DecisionPolicy policy, int score) {
        if (policy == null || !policy.emit()) {
            return "NONE";
        }
        if (score <= policy.autoApproveMaxScore()) {
            return "AUTO_APPROVE";
        }
        if (score >= policy.rejectMinScore()) {
            return "REJECT";
        }
        return "NEEDS_REVIEW";
    }

    private static String mapRiskLevel(DecisionPolicy policy, int score) {
        if (policy == null || policy.riskLevels().isEmpty()) {
            return null;
        }
        for (DecisionPolicy.RiskLevel level : policy.riskLevels()) {
            if (score <= level.maxScore()) {
                return level.level();
            }
        }
        return policy.riskLevels().get(policy.riskLevels().size() - 1).level();
    }

    private static String verdict(String decision, List<Finding> findings, int ruleErrors) {
        if ("REJECT".equals(decision) || hasSeverity(findings, "HIGH")) {
            return "FAIL";
        }
        if ("NEEDS_REVIEW".equals(decision) || ruleErrors > 0 || hasSeverity(findings, "MEDIUM")) {
            return "WARN";
        }
        return "PASS";
    }

    private static boolean hasSeverity(List<Finding> findings, String severity) {
        return findings.stream().anyMatch(f -> severity.equalsIgnoreCase(f.severity()));
    }

    /**
     * Risk level is not part of the {@link SkillRunResult} contract, so it is exposed
     * through a decision action only when explicitly set. Kept as a no-op hook so
     * the mapping is computed and traceable in one place.
     */
    private SkillRunResult withRiskLevel(SkillRunResult result, String riskLevel) {
        if (riskLevel == null) {
            return result;
        }
        List<Action> actions = new ArrayList<>(result.actions());
        boolean present = actions.stream().anyMatch(a -> "setRiskLevel".equals(a.action()));
        if (!present) {
            actions.add(new Action("$derived", "setRiskLevel", riskLevel, "risk level derived from score"));
        }
        return new SkillRunResult(result.skillId(), result.runId(), result.verdict(), result.score(),
                result.decision(), result.findings(), actions, result.trace(),
                result.durationMs(), result.model());
    }
}
