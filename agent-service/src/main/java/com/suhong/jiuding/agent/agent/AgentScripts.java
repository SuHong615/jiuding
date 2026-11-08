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
package com.suhong.jiuding.agent.agent;

import com.suhong.jiuding.agent.skill.Action;
import com.suhong.jiuding.agent.skill.Finding;
import com.suhong.jiuding.agent.skill.SkillRunResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Built-in deterministic scripts for the four platform agents:
 * {@code tenant-intake}, {@code risk-review}, {@code approval}, {@code provisioning}.
 *
 * <p>Every script outputs a JSON object (the {@code output} field of an agent
 * run) and never throws for missing optional fields.
 */
@Component
public class AgentScripts {

    private static final Map<String, String> PLAN_ALIASES = Map.of(
            "basic", "FREE", "starter", "FREE", "free", "FREE",
            "pro", "PRO", "professional", "PRO", "standard", "PRO",
            "enterprise", "ENTERPRISE", "premium", "ENTERPRISE");

    private static final Map<String, String> REGION_ALIASES = Map.of(
            "shanghai", "cn-shanghai", "上海", "cn-shanghai",
            "beijing", "cn-beijing", "北京", "cn-beijing",
            "shenzhen", "cn-shenzhen", "深圳", "cn-shenzhen",
            "guangzhou", "cn-guangzhou", "广州", "cn-guangzhou",
            "singapore", "ap-southeast-1", "新加坡", "ap-southeast-1");

    private static final Map<String, String> ISOLATION_ALIASES = Map.of(
            "shared", "SHARED_SCHEMA", "shared_schema", "SHARED_SCHEMA",
            "dedicated-schema", "DEDICATED_SCHEMA", "dedicated_schema", "DEDICATED_SCHEMA",
            "dedicated-db", "DEDICATED_DB", "dedicated_db", "DEDICATED_DB");

    private static final String EMAIL_REGEX = "^[^@\\s]+@[^@\\s]+\\.[A-Za-z]{2,}$";

    private final Map<String, AgentScript> scripts;

    public AgentScripts() {
        Map<String, AgentScript> map = new LinkedHashMap<>();
        map.put("tenant-intake", new AgentScript("tenant-intake", "tenant-intake",
                "先读取/校验租户基础档案，再做字段归一化与必填检查", this::intakeTool, this::intake));
        map.put("risk-review", new AgentScript("risk-review", "risk-review",
                "调用 risk-review 规则引擎，依据行业/区域/配额/邮箱/黑名单计算风险",
                context -> skillInvocation("risk-review", context), this::riskReview));
        map.put("approval", new AgentScript("approval", "approval-policy",
                "调用 approval-policy 规则引擎，得出审批决策、所需角色与 SLA",
                context -> skillInvocation("approval-policy", context), this::approval));
        map.put("provisioning", new AgentScript("provisioning", "provisioning",
                "调用 provisioning 规则引擎，生成命名空间/配额/schema 资源计划",
                context -> skillInvocation("provisioning", context), this::provisioning));
        this.scripts = Map.copyOf(map);
    }

    public AgentScript require(String agentType) {
        AgentScript script = scripts.get(agentType == null ? "" : agentType.trim().toLowerCase(Locale.ROOT));
        if (script == null) {
            throw new BizException(ErrorCode.INVALID_ARGUMENT,
                    "unknown agentType: " + agentType + " (expected tenant-intake|risk-review|approval|provisioning)");
        }
        return script;
    }

    public java.util.Set<String> agentTypes() {
        return scripts.keySet();
    }

    // ------------------------------------------------------------------ tools

    private AgentScript.ToolInvocation intakeTool(AgentScript.ScriptContext context) {
        Map<String, Object> input = context.input();
        boolean hasInlineProfile = input.containsKey("tenantCode") || input.containsKey("name");
        String tenantId = firstNonBlank(context.tenantId(), string(input.get("tenantId")));
        if (!hasInlineProfile && tenantId != null) {
            Map<String, Object> toolInput = new LinkedHashMap<>();
            toolInput.put("tenantId", tenantId);
            return new AgentScript.ToolInvocation("tenant.get", toolInput);
        }
        return skillInvocation("tenant-intake", context);
    }

    private AgentScript.ToolInvocation skillInvocation(String skillId, AgentScript.ScriptContext context) {
        Map<String, Object> input = new LinkedHashMap<>(context.input());
        input.remove("_skillId");
        input.remove("_agentType");
        String tenantId = firstNonBlank(context.tenantId(), string(input.get("tenantId")));
        if (tenantId != null) {
            input.putIfAbsent("tenantId", tenantId);
        }
        Map<String, Object> toolInput = new LinkedHashMap<>();
        toolInput.put("skillId", skillId);
        toolInput.put("tenantId", tenantId);
        toolInput.put("input", input);
        return new AgentScript.ToolInvocation("skill.run", toolInput);
    }

    // -------------------------------------------------------------- finalizers

    private Map<String, Object> intake(AgentScript.ScriptContext context, Map<String, Object> observation,
                                       SkillRunResult skillResult) {
        // tenant.get returns a tenant profile; skill.run returns a SkillRunResult.
        Map<String, Object> merged = new LinkedHashMap<>();
        if (skillResult == null) {
            merged.putAll(observation);
        }
        context.input().forEach(merged::putIfAbsent);
        String tenantId = firstNonBlank(context.tenantId(), string(merged.get("tenantId")));
        merged.putIfAbsent("tenantId", tenantId);

        String tenantCode = string(merged.get("tenantCode"));
        String name = string(merged.get("name"));
        String email = string(merged.get("contactEmail"));

        List<Map<String, Object>> issues = new ArrayList<>();
        if (tenantCode == null) {
            issues.add(issue("tenantCode", "HIGH", "缺少租户编码 tenantCode"));
        }
        if (name == null) {
            issues.add(issue("name", "HIGH", "缺少租户名称 name"));
        }
        if (email != null && !email.matches(EMAIL_REGEX)) {
            issues.add(issue("contactEmail", "MEDIUM", "邮箱格式不合法: " + email));
        }

        String rawPlan = string(merged.get("plan"));
        String rawRegion = string(merged.get("region"));
        String rawIsolation = string(merged.get("isolationMode"));
        String plan = normalizePlan(rawPlan);
        String region = normalizeRegion(rawRegion);
        String isolation = normalizeIsolation(rawIsolation);
        if (rawPlan != null && !plan.equals(rawPlan)) {
            issues.add(issue("plan", "LOW", "plan 使用别名 " + rawPlan + "，已归一化为 " + plan));
        }
        if (rawRegion != null && !region.equals(rawRegion)) {
            issues.add(issue("region", "LOW", "region 使用别名 " + rawRegion + "，已归一化为 " + region));
        }

        boolean highIssue = issues.stream().anyMatch(i -> "HIGH".equals(i.get("severity")));
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("plan", plan);
        normalized.put("region", region);
        normalized.put("isolationMode", isolation);

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tenantId", tenantId);
        output.put("tenantCode", tenantCode);
        output.put("name", name);
        output.put("status", highIssue ? "INTAKE_INCOMPLETE" : "INTAKE_OK");
        output.put("normalized", normalized);
        output.put("issues", issues);
        output.put("duplicateCandidate", Boolean.TRUE.equals(merged.get("duplicateCandidate")));
        output.put("verdict", skillResult == null ? (highIssue ? "FAIL" : "PASS") : skillResult.verdict());
        if (skillResult != null) {
            output.put("skillId", skillResult.skillId());
            output.put("score", skillResult.score());
            output.put("decision", skillResult.decision());
        }
        return output;
    }

    private Map<String, Object> riskReview(AgentScript.ScriptContext context, Map<String, Object> observation,
                                           SkillRunResult skillResult) {
        Map<String, Object> merged = new LinkedHashMap<>(observation);
        context.input().forEach(merged::putIfAbsent);
        int score = skillResult == null ? intValue(merged.get("riskScore"), 0) : skillResult.score();
        String riskLevel = actionValue(skillResult, "setRiskLevel");
        if (riskLevel == null) {
            riskLevel = string(merged.get("riskLevel"));
        }
        if (riskLevel == null) {
            riskLevel = deriveRiskLevel(score);
        }
        String decision = skillResult == null ? null : skillResult.decision();
        if (decision == null || "NONE".equals(decision)) {
            decision = deriveDecision(riskLevel);
        }
        List<String> reasons = new ArrayList<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        if (skillResult != null) {
            for (Finding finding : skillResult.findings()) {
                if (!reasons.contains(finding.message())) {
                    reasons.add(finding.message());
                }
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("severity", finding.severity());
                item.put("message", finding.message());
                item.put("ruleId", finding.ruleId());
                findings.add(item);
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("未命中风险规则，按基线处理");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tenantId", firstNonBlank(context.tenantId(), string(merged.get("tenantId"))));
        output.put("riskLevel", riskLevel);
        output.put("score", score);
        output.put("decision", decision);
        output.put("verdict", skillResult == null ? "WARN" : skillResult.verdict());
        output.put("reasons", reasons);
        output.put("findings", findings);
        output.put("skillId", skillResult == null ? "risk-review" : skillResult.skillId());
        return output;
    }

    private Map<String, Object> approval(AgentScript.ScriptContext context, Map<String, Object> observation,
                                         SkillRunResult skillResult) {
        Map<String, Object> merged = new LinkedHashMap<>(observation);
        context.input().forEach(merged::putIfAbsent);
        String riskLevel = string(merged.get("riskLevel"));
        String plan = normalizePlan(string(merged.get("plan")));
        String decision = skillResult == null ? null : skillResult.decision();
        if (decision == null || "NONE".equals(decision)) {
            decision = deriveApprovalDecision(riskLevel, plan);
        }
        String requireRole = actionValue(skillResult, "requireRole");
        if (requireRole == null) {
            requireRole = switch (decision) {
                case "AUTO_APPROVE" -> "SYSTEM";
                case "REJECT" -> null;
                default -> "L1";
            };
        }
        Integer slaHours = intValue(actionValue(skillResult, "setSlaHours"), 24);
        List<String> reasons = new ArrayList<>();
        if (skillResult != null) {
            for (Finding finding : skillResult.findings()) {
                if (!reasons.contains(finding.message())) {
                    reasons.add(finding.message());
                }
            }
        }
        if (reasons.isEmpty()) {
            reasons.add("riskLevel=" + riskLevel + ", plan=" + plan + " 匹配默认审批策略");
        }
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tenantId", firstNonBlank(context.tenantId(), string(merged.get("tenantId"))));
        output.put("decision", decision);
        output.put("requireRole", requireRole);
        output.put("requiredRole", requireRole);
        output.put("slaHours", slaHours);
        output.put("autoApproved", "AUTO_APPROVE".equals(decision));
        output.put("verdict", skillResult == null ? "WARN" : skillResult.verdict());
        output.put("reasons", reasons);
        output.put("skillId", skillResult == null ? "approval-policy" : skillResult.skillId());
        return output;
    }

    private Map<String, Object> provisioning(AgentScript.ScriptContext context, Map<String, Object> observation,
                                             SkillRunResult skillResult) {
        Map<String, Object> merged = new LinkedHashMap<>(observation);
        context.input().forEach(merged::putIfAbsent);
        String tenantCode = string(merged.get("tenantCode"));
        String tenantId = firstNonBlank(context.tenantId(), string(merged.get("tenantId")));
        String plan = normalizePlan(string(merged.get("plan")));
        String isolation = normalizeIsolation(string(merged.get("isolationMode")));
        String slug = slug(firstNonBlank(tenantCode, tenantId, "tenant"));

        Map<String, Object> quotas = new LinkedHashMap<>();
        quotas.put("seats", intValue(merged.get("quotaSeats"), defaultSeats(plan)));
        quotas.put("storageGb", intValue(merged.get("quotaStorageGb"), defaultStorage(plan)));
        quotas.put("apiRpm", intValue(merged.get("quotaApiRpm"), defaultApiRpm(plan)));

        List<String> steps = new ArrayList<>();
        steps.add("validate-tenant-plan");
        steps.add("create-namespace:ns-" + slug);
        if ("DEDICATED_SCHEMA".equals(isolation) || "DEDICATED_DB".equals(isolation)) {
            steps.add("create-schema:tenant_" + slug.replace('-', '_'));
        }
        steps.add("apply-quota");
        steps.add("bind-policy-bundle");

        List<String> tags = new ArrayList<>();
        if (skillResult != null) {
            for (Action action : skillResult.actions()) {
                if ("addTag".equals(action.action()) && action.value() != null) {
                    tags.add(String.valueOf(action.value()));
                }
            }
        }

        Map<String, Object> output = new LinkedHashMap<>();
        output.put("tenantId", tenantId);
        output.put("tenantCode", tenantCode);
        output.put("status", "PLANNED");
        output.put("plan", plan);
        output.put("isolationMode", isolation);
        output.put("namespace", "ns-" + slug);
        output.put("schema", ("DEDICATED_SCHEMA".equals(isolation) || "DEDICATED_DB".equals(isolation))
                ? "tenant_" + slug.replace('-', '_') : null);
        output.put("quotas", quotas);
        output.put("steps", steps);
        output.put("tags", tags);
        output.put("verdict", skillResult == null ? "PASS" : skillResult.verdict());
        output.put("skillId", skillResult == null ? "provisioning" : skillResult.skillId());
        return output;
    }

    // ------------------------------------------------------------------ utils

    private static Map<String, Object> issue(String field, String severity, String message) {
        Map<String, Object> issue = new LinkedHashMap<>();
        issue.put("field", field);
        issue.put("severity", severity);
        issue.put("message", message);
        return issue;
    }

    private static String actionValue(SkillRunResult result, String action) {
        if (result == null) {
            return null;
        }
        String value = null;
        for (Action item : result.actions()) {
            if (action.equals(item.action()) && item.value() != null) {
                value = String.valueOf(item.value());
            }
        }
        return value;
    }

    static String normalizePlan(String raw) {
        if (raw == null) {
            return "PRO";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String mapped = PLAN_ALIASES.get(key);
        if (mapped != null) {
            return mapped;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "FREE", "PRO", "ENTERPRISE" -> upper;
            default -> "PRO";
        };
    }

    static String normalizeRegion(String raw) {
        if (raw == null || raw.isBlank()) {
            return "cn-shanghai";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String mapped = REGION_ALIASES.get(key);
        if (mapped != null) {
            return mapped;
        }
        return raw.trim().startsWith("cn-") || raw.trim().startsWith("ap-") ? raw.trim() : "cn-shanghai";
    }

    static String normalizeIsolation(String raw) {
        if (raw == null || raw.isBlank()) {
            return "SHARED_SCHEMA";
        }
        String key = raw.trim().toLowerCase(Locale.ROOT);
        String mapped = ISOLATION_ALIASES.get(key);
        if (mapped != null) {
            return mapped;
        }
        String upper = raw.trim().toUpperCase(Locale.ROOT);
        return switch (upper) {
            case "SHARED_SCHEMA", "DEDICATED_SCHEMA", "DEDICATED_DB" -> upper;
            default -> "SHARED_SCHEMA";
        };
    }

    static String deriveRiskLevel(int score) {
        if (score <= 20) {
            return "LOW";
        }
        return score <= 60 ? "MEDIUM" : "HIGH";
    }

    static String deriveDecision(String riskLevel) {
        if (riskLevel == null) {
            return "NEEDS_REVIEW";
        }
        return switch (riskLevel.toUpperCase(Locale.ROOT)) {
            case "LOW" -> "AUTO_APPROVE";
            case "HIGH" -> "NEEDS_REVIEW";
            default -> "NEEDS_REVIEW";
        };
    }

    static String deriveApprovalDecision(String riskLevel, String plan) {
        String level = riskLevel == null ? "MEDIUM" : riskLevel.toUpperCase(Locale.ROOT);
        if ("LOW".equals(level) && !"ENTERPRISE".equals(plan)) {
            return "AUTO_APPROVE";
        }
        return "NEEDS_REVIEW";
    }

    private static int defaultSeats(String plan) {
        return switch (plan) {
            case "FREE" -> 10;
            case "ENTERPRISE" -> 500;
            default -> 50;
        };
    }

    private static int defaultStorage(String plan) {
        return switch (plan) {
            case "FREE" -> 20;
            case "ENTERPRISE" -> 2048;
            default -> 200;
        };
    }

    private static int defaultApiRpm(String plan) {
        return switch (plan) {
            case "FREE" -> 60;
            case "ENTERPRISE" -> 6000;
            default -> 600;
        };
    }

    static String slug(String raw) {
        if (raw == null || raw.isBlank()) {
            return "tenant";
        }
        String slug = raw.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "tenant" : slug;
    }

    static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    static Integer intValue(Object value, Integer fallback) {
        BigDecimal number = com.suhong.jiuding.agent.rule.Operator.number(value);
        return number == null ? fallback : number.intValue();
    }

    static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
