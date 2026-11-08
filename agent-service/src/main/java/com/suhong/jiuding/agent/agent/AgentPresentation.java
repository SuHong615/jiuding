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

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Human-readable presentation for the four platform agents (docs/15 §3):
 * catalog entries, display names/purposes, one-line run summaries and Chinese
 * ReAct step titles/labels.
 *
 * <p>Kept separate from {@link AgentScripts} so the deterministic execution
 * scripts stay free of presentation concerns and both stay trivially testable.
 */
@Component
public class AgentPresentation {

    public static final String AGENT_INTAKE = "tenant-intake";
    public static final String AGENT_RISK = "risk-review";
    public static final String AGENT_APPROVAL = "approval";
    public static final String AGENT_PROVISIONING = "provisioning";

    private final ObjectMapper objectMapper;

    public AgentPresentation(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** One catalog card for the Agent page. */
    public record CatalogEntry(String agentType, String displayName, String description,
                               List<String> inputs, List<String> outputs, Object exampleInput) {
    }

    public List<CatalogEntry> catalog() {
        List<CatalogEntry> entries = new ArrayList<>();

        Map<String, Object> intakeExample = new LinkedHashMap<>();
        intakeExample.put("tenantCode", "acme");
        intakeExample.put("name", "Acme 云服务");
        intakeExample.put("contactEmail", "ops@acme.com");
        intakeExample.put("plan", "standard");
        intakeExample.put("region", "shanghai");
        entries.add(new CatalogEntry(AGENT_INTAKE, "租户准入校验",
                "校验租户资料完整性、邮箱格式与 plan/region 别名，给出准入建议",
                List.of("tenantId 或内联租户档案（tenantCode/name/contactEmail/plan/region）",
                        "duplicateCandidate（可选）"),
                List.of("status（INTAKE_OK / INTAKE_INCOMPLETE）", "normalized（plan/region/isolationMode）",
                        "issues", "verdict"),
                intakeExample));

        Map<String, Object> riskExample = new LinkedHashMap<>();
        riskExample.put("tenantCode", "risky-casino");
        riskExample.put("plan", "PRO");
        riskExample.put("region", "cn-shanghai");
        riskExample.put("quotaSeats", 150);
        riskExample.put("contactEmail", "owner@mailinator.com");
        riskExample.put("attributes", Map.of("industry", "gambling"));
        entries.add(new CatalogEntry(AGENT_RISK, "租户风险审查",
                "根据行业、区域、配额、联系人与黑名单计算风险等级、评分与审批建议",
                List.of("tenantId / tenantCode", "plan、region、quotaSeats",
                        "contactEmail", "attributes.industry、attributes.blacklist"),
                List.of("riskLevel（LOW/MEDIUM/HIGH）", "score", "decision（AUTO_APPROVE/NEEDS_REVIEW/REJECT）",
                        "reasons、findings"),
                riskExample));

        Map<String, Object> approvalExample = new LinkedHashMap<>();
        approvalExample.put("plan", "PRO");
        approvalExample.put("riskLevel", "MEDIUM");
        approvalExample.put("riskScore", 30);
        entries.add(new CatalogEntry(AGENT_APPROVAL, "审批策略",
                "由风险等级与套餐决定自动通过 / 人工审批节点（L1/L2）与 SLA",
                List.of("plan", "riskLevel", "riskScore", "reasons（可选）"),
                List.of("decision", "requireRole（SYSTEM/L1/L2）", "slaHours", "autoApproved"),
                approvalExample));

        Map<String, Object> provisioningExample = new LinkedHashMap<>();
        provisioningExample.put("tenantCode", "bigco");
        provisioningExample.put("plan", "ENTERPRISE");
        provisioningExample.put("region", "cn-shanghai");
        provisioningExample.put("isolationMode", "DEDICATED_SCHEMA");
        provisioningExample.put("riskLevel", "MEDIUM");
        entries.add(new CatalogEntry(AGENT_PROVISIONING, "资源开通",
                "生成命名空间、隔离 schema 与席位/存储/API 配额计划",
                List.of("tenantCode", "plan", "region", "isolationMode",
                        "quotaSeats/quotaStorageGb/quotaApiRpm（可选）"),
                List.of("status（PLANNED）", "namespace、schema", "quotas", "steps"),
                provisioningExample));

        return entries;
    }

    public boolean known(String agentType) {
        return displayName(agentType) != null;
    }

    public String displayName(String agentType) {
        return switch (normalize(agentType)) {
            case AGENT_INTAKE -> "租户准入校验";
            case AGENT_RISK -> "租户风险审查";
            case AGENT_APPROVAL -> "审批策略";
            case AGENT_PROVISIONING -> "资源开通";
            default -> null;
        };
    }

    /** One-sentence purpose shown next to the display name. */
    public String purpose(String agentType) {
        return switch (normalize(agentType)) {
            case AGENT_INTAKE -> "校验资料完整性并给出准入建议";
            case AGENT_RISK -> "输出风险等级/评分/发现";
            case AGENT_APPROVAL -> "决定自动通过/人工节点/SLA";
            case AGENT_PROVISIONING -> "生成命名空间/配额计划";
            default -> "Agent 运行";
        };
    }

    /** One-line run summary derived from the structured output. */
    public String summary(String agentType, Object output) {
        Map<String, Object> data = asMap(output);
        if (data == null) {
            if (output instanceof String text && !text.isBlank()) {
                return truncate(text, 160);
            }
            return "运行完成，可查看结构化输出。";
        }
        if (data.get("error") != null && data.get("decision") == null) {
            return "运行失败：" + truncate(String.valueOf(data.get("error")), 140);
        }
        return switch (normalize(agentType)) {
            case AGENT_INTAKE -> intakeSummary(data);
            case AGENT_RISK -> riskSummary(data);
            case AGENT_APPROVAL -> approvalSummary(data);
            case AGENT_PROVISIONING -> provisioningSummary(data);
            default -> "运行完成。";
        };
    }

    private String intakeSummary(Map<String, Object> data) {
        StringBuilder text = new StringBuilder("租户准入：").append(value(data, "status", "完成"));
        Object normalized = data.get("normalized");
        if (normalized instanceof Map<?, ?> map) {
            Object plan = map.get("plan");
            Object region = map.get("region");
            if (plan != null) {
                text.append("，plan=").append(plan);
            }
            if (region != null) {
                text.append("，region=").append(region);
            }
        }
        Object issues = data.get("issues");
        if (issues instanceof List<?> list) {
            text.append(list.isEmpty() ? "，无阻断问题" : "，" + list.size() + " 个待处理项");
        }
        return text.toString();
    }

    private String riskSummary(Map<String, Object> data) {
        String level = value(data, "riskLevel", "未知等级");
        String score = value(data, "score", "0");
        String decision = value(data, "decision", "NEEDS_REVIEW");
        return "风险审查：" + level + " / " + score + " 分 → " + decisionText(decision);
    }

    private String approvalSummary(Map<String, Object> data) {
        String decision = value(data, "decision", "NEEDS_REVIEW");
        if ("AUTO_APPROVE".equals(decision)) {
            return "审批策略：自动通过（" + value(data, "requireRole", "SYSTEM")
                    + "，SLA " + value(data, "slaHours", "4") + "h）";
        }
        if ("REJECT".equals(decision)) {
            return "审批策略：自动驳回（" + joinReasons(data) + "）";
        }
        return "审批策略：人工审批 " + value(data, "requireRole", "L1")
                + "，SLA " + value(data, "slaHours", "24") + "h";
    }

    private String provisioningSummary(Map<String, Object> data) {
        StringBuilder text = new StringBuilder("资源开通：")
                .append(value(data, "plan", "PRO")).append(" / ")
                .append(value(data, "isolationMode", "SHARED_SCHEMA")).append(" → ")
                .append(value(data, "namespace", "ns-unknown"));
        Object quotas = data.get("quotas");
        if (quotas instanceof Map<?, ?> map && map.get("seats") != null) {
            text.append("（").append(map.get("seats")).append(" 席位）");
        }
        return text.toString();
    }

    private String joinReasons(Map<String, Object> data) {
        Object reasons = data.get("reasons");
        if (reasons instanceof List<?> list && !list.isEmpty()) {
            return truncate(String.valueOf(list.get(0)), 100);
        }
        return "命中驳回策略";
    }

    private static String decisionText(String decision) {
        return switch (decision.toUpperCase(Locale.ROOT)) {
            case "AUTO_APPROVE" -> "建议自动通过";
            case "REJECT" -> "建议驳回";
            case "NONE" -> "无审批决策";
            default -> "需要人工审批";
        };
    }

    /** Chinese title for one ReAct step. */
    public String stepTitle(String action, String actionInput) {
        if (action == null || action.isBlank()) {
            return "执行步骤";
        }
        return switch (action) {
            case AgentTools.TENANT_GET -> "读取租户资料";
            case AgentTools.APPROVAL_LIST_PENDING -> "读取待办审批";
            case "skill.run" -> skillStepTitle(skillIdOf(actionInput));
            case "finalize" -> "生成结论";
            default -> "调用工具 " + action;
        };
    }

    /** Short Chinese label for the action chip. */
    public String actionLabel(String action) {
        if (action == null || action.isBlank()) {
            return "执行";
        }
        return switch (action) {
            case AgentTools.TENANT_GET -> "读取资料";
            case AgentTools.APPROVAL_LIST_PENDING -> "读取审批";
            case "skill.run" -> "执行规则";
            case "finalize" -> "生成结论";
            default -> "执行";
        };
    }

    private static String skillStepTitle(String skillId) {
        if (skillId == null) {
            return "调用技能规则";
        }
        return switch (skillId) {
            case "risk-review" -> "调用风险规则";
            case "approval-policy" -> "计算审批策略";
            case "provisioning" -> "生成资源计划";
            case "tenant-intake" -> "校验租户资料";
            case "tenant-rating" -> "计算评级调整分";
            default -> "调用技能规则";
        };
    }

    private String skillIdOf(String actionInput) {
        if (actionInput == null || actionInput.isBlank()) {
            return null;
        }
        try {
            Object parsed = objectMapper.readValue(actionInput, Object.class);
            if (parsed instanceof Map<?, ?> map && map.get("skillId") != null) {
                return String.valueOf(map.get("skillId"));
            }
        } catch (Exception ignored) {
            // malformed inputs simply fall back to the generic title
        }
        return null;
    }

    /** Attach titles to steps that were persisted before v5. */
    public Step decorate(Step step) {
        if (step == null) {
            return null;
        }
        String title = step.title() != null && !step.title().isBlank()
                ? step.title() : stepTitle(step.action(), step.actionInput());
        String label = step.actionLabel() != null && !step.actionLabel().isBlank()
                ? step.actionLabel() : actionLabel(step.action());
        return new Step(step.index(), step.thought(), step.action(), step.actionInput(),
                step.observation(), title, label);
    }

    public List<Step> decorate(List<Step> steps) {
        if (steps == null) {
            return List.of();
        }
        List<Step> decorated = new ArrayList<>(steps.size());
        for (Step step : steps) {
            decorated.add(decorate(step));
        }
        return decorated;
    }

    private static String normalize(String agentType) {
        return agentType == null ? "" : agentType.trim().toLowerCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            return (Map<String, Object>) map;
        }
        return null;
    }

    private static String value(Map<String, Object> data, String key, String fallback) {
        Object value = data.get(key);
        return value == null ? fallback : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
