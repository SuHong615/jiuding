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
package com.suhong.jiuding.approval.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.approval.client.AgentServiceClient;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.AutoDecision;
import com.suhong.jiuding.approval.domain.WorkflowNodes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Computes the approval routing decision.
 *
 * <p>Preferred path: call agent-service {@code POST /api/skills/approval-policy/run}.
 * When that call fails (timeout, non-2xx, unparseable payload) a deterministic local
 * rule set takes over so the tenant submission flow never depends on the agent tier:
 * <pre>
 *   LOW  &amp;&amp; plan != ENTERPRISE  =&gt; AUTO_APPROVE
 *   MEDIUM                      =&gt; NEEDS_REVIEW, L1 (APPROVER)
 *   HIGH                        =&gt; NEEDS_REVIEW, L2 (SENIOR_APPROVER)
 * </pre>
 */
@Service
public class AutoDecisionService {

    private static final Logger log = LoggerFactory.getLogger(AutoDecisionService.class);

    private final AgentServiceClient agentServiceClient;
    private final ApprovalProperties properties;

    public AutoDecisionService(AgentServiceClient agentServiceClient, ApprovalProperties properties) {
        this.agentServiceClient = agentServiceClient;
        this.properties = properties;
    }

    public AutoDecisionOutcome decide(ApprovalEntity approval) {
        Map<String, Object> input = buildSkillInput(approval);
        try {
            JsonNode result = agentServiceClient.runSkill("approval-policy", input, approval.getTenantId());
            AutoDecisionOutcome outcome = parse(result, approval);
            if (outcome != null) {
                log.info("approval-policy decision for {}: {}", approval.getId(), outcome.detail());
                return outcome;
            }
            log.warn("approval-policy returned no actionable decision for {}: {}, falling back to local rules",
                    approval.getId(), result);
        } catch (RuntimeException ex) {
            log.warn("approval-policy unavailable for {} ({}), falling back to local rules",
                    approval.getId(), ex.getMessage());
        }
        return localFallback(approval);
    }

    Map<String, Object> buildSkillInput(ApprovalEntity approval) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", approval.getTenantId());
        input.put("tenantCode", approval.getTenantCode());
        input.put("plan", approval.getPlan());
        input.put("riskLevel", approval.getRiskLevel());
        input.put("riskScore", approval.getRiskScore());
        input.put("reasons", approval.getReasons() == null ? List.of() : approval.getReasons());
        return input;
    }

    /** Returns null when the payload does not carry a usable decision. */
    AutoDecisionOutcome parse(JsonNode result, ApprovalEntity approval) {
        if (result == null || result.isMissingNode() || result.isNull()) {
            return null;
        }
        String decisionRaw = firstText(result, "decision", "approvalDecision", "verdictDecision");
        if (decisionRaw == null) {
            decisionRaw = firstText(result.path("output"), "decision", "approvalDecision");
        }
        if (decisionRaw == null) {
            decisionRaw = firstText(result.path("result"), "decision");
        }
        AutoDecision decision = AutoDecision.parse(decisionRaw);
        if (decision == AutoDecision.NONE) {
            return null;
        }

        String role = normalizeRole(firstText(result, "requiredRole", "requireRole", "assigneeRole"));
        Integer slaHours = firstInt(result, "slaHours", "sla_hours");

        JsonNode actions = result.path("actions");
        if (actions.isArray()) {
            for (JsonNode action : actions) {
                String name = firstText(action, "action", "type", "name");
                if (name == null) {
                    continue;
                }
                String normalized = name.trim().toLowerCase(Locale.ROOT);
                String value = text(action.path("value"));
                if (value == null) {
                    value = text(action.path("role"));
                }
                if ("requirerole".equals(normalized) || "require_role".equals(normalized)) {
                    role = normalizeRole(value);
                } else if ("setslahours".equals(normalized) || "set_sla_hours".equals(normalized)) {
                    slaHours = intOrNull(value);
                }
            }
        }

        if (decision == AutoDecision.AUTO_APPROVE || decision == AutoDecision.REJECT) {
            role = null;
            slaHours = 0;
        } else {
            if (role == null) {
                role = defaultRole(approval);
            }
            if (slaHours == null || slaHours <= 0) {
                slaHours = defaultSla(role);
            }
        }
        String detail = "decision=" + decision + " role=" + role + " slaHours=" + slaHours + " source=agent";
        return new AutoDecisionOutcome(decision, role, slaHours, "agent", detail);
    }

    AutoDecisionOutcome localFallback(ApprovalEntity approval) {
        String risk = normalizeRisk(approval.getRiskLevel(), approval.getRiskScore());
        boolean enterprise = "ENTERPRISE".equalsIgnoreCase(approval.getPlan());
        if ("LOW".equals(risk) && !enterprise) {
            String detail = "decision=AUTO_APPROVE rule=LOW_AND_NOT_ENTERPRISE risk=LOW plan="
                    + approval.getPlan() + " source=local-fallback";
            return new AutoDecisionOutcome(AutoDecision.AUTO_APPROVE, null, 0, "local-fallback", detail);
        }
        boolean high = "HIGH".equals(risk);
        String role = high ? WorkflowNodes.ROLE_L2 : WorkflowNodes.ROLE_L1;
        int slaHours = high ? properties.getL2SlaHours() : properties.getL1SlaHours();
        String rule = high ? "HIGH_RISK_L2" : "MEDIUM_RISK_L1";
        String detail = "decision=NEEDS_REVIEW rule=" + rule + " risk=" + risk + " role=" + role
                + " slaHours=" + slaHours + " source=local-fallback";
        return new AutoDecisionOutcome(AutoDecision.NEEDS_REVIEW, role, slaHours, "local-fallback", detail);
    }

    private String defaultRole(ApprovalEntity approval) {
        String risk = normalizeRisk(approval.getRiskLevel(), approval.getRiskScore());
        return "HIGH".equals(risk) ? WorkflowNodes.ROLE_L2 : WorkflowNodes.ROLE_L1;
    }

    private int defaultSla(String role) {
        return WorkflowNodes.ROLE_L2.equals(role) ? properties.getL2SlaHours() : properties.getL1SlaHours();
    }

    static String normalizeRisk(String riskLevel, Integer riskScore) {
        String level = riskLevel == null ? null : riskLevel.trim().toUpperCase(Locale.ROOT);
        if ("LOW".equals(level) || "MEDIUM".equals(level) || "HIGH".equals(level)) {
            return level;
        }
        if (riskScore != null) {
            if (riskScore <= 30) {
                return "LOW";
            }
            if (riskScore <= 70) {
                return "MEDIUM";
            }
            return "HIGH";
        }
        return "MEDIUM";
    }

    static String normalizeRole(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "L1", "LEVEL1", "LEVEL_1", "APPROVER_L1" -> WorkflowNodes.ROLE_L1;
            case "L2", "LEVEL2", "LEVEL_2", "APPROVER_L2", "SENIOR_APPROVER" -> WorkflowNodes.ROLE_L2;
            case "ADMIN", "PLATFORM_ADMIN", "SYS_ADMIN" -> WorkflowNodes.ROLE_PLATFORM_ADMIN;
            default -> value;
        };
    }

    private static String firstText(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            String value = text(node.path(field));
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isBlank() ? null : value;
    }

    private static Integer firstInt(JsonNode node, String... fields) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                Integer parsed = intOrNull(value.asText());
                if (parsed != null) {
                    return parsed;
                }
            }
        }
        return null;
    }

    private static Integer intOrNull(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
