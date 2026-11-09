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
package com.suhong.jiuding.approval.workflowdef;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.approval.client.AgentServiceClient;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.AutoDecision;
import com.suhong.jiuding.approval.domain.NodeState;
import com.suhong.jiuding.approval.domain.NodeType;
import com.suhong.jiuding.approval.domain.WorkflowNodes;
import com.suhong.jiuding.approval.service.AutoDecisionOutcome;
import com.suhong.jiuding.approval.service.AutoDecisionService;
import com.suhong.jiuding.approval.support.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Executes a published workflow definition while creating an approval.
 *
 * <p>Execution follows {@code next} links from the entry node (the first node of
 * {@code definition.nodes}); PARALLEL children are executed sequentially in v12
 * (documented placeholder). AUTO_CHECK with skill {@code approval-policy} reuses
 * the existing auto-decision pipeline (agent-service + local fallback); other
 * skills are invoked as auxiliary checks and never abort the workflow. The first
 * HUMAN node stops execution and becomes the pending approval node; when no human
 * node exists the engine still produces an auto decision (falling back to the
 * built-in decision rules), guaranteeing a terminal or waiting state.
 */
@Component
public class WorkflowEngine {

    private static final Logger log = LoggerFactory.getLogger(WorkflowEngine.class);
    private static final String DEFAULT_SKILL = "approval-policy";
    private static final String FALLBACK_HUMAN_NODE = "FALLBACK_APPROVAL";

    private final AutoDecisionService autoDecisionService;
    private final AgentServiceClient agentServiceClient;
    private final ApprovalProperties properties;

    public WorkflowEngine(AutoDecisionService autoDecisionService,
                          AgentServiceClient agentServiceClient,
                          ApprovalProperties properties) {
        this.autoDecisionService = autoDecisionService;
        this.agentServiceClient = agentServiceClient;
        this.properties = properties;
    }

    public WorkflowPlan plan(ApprovalEntity approval, PublishedWorkflow workflow) {
        WorkflowDefinitionModel model = workflow.model();
        Map<String, WorkflowNodeDefinition> byId = model.byId();
        List<PlannedNode> planned = new ArrayList<>();
        List<String> annotations = new ArrayList<>();
        Deque<WorkflowNodeDefinition> queue = new ArrayDeque<>();
        if (model.entry() != null) {
            queue.addLast(model.entry());
        }
        Set<String> visited = new LinkedHashSet<>();
        AutoDecisionOutcome decision = null;
        String pendingRole = null;
        Integer pendingSla = null;
        Integer slaOverride = null;
        int guard = 0;

        while (!queue.isEmpty() && guard++ < 200) {
            WorkflowNodeDefinition node = queue.pollFirst();
            if (node == null || node.id() == null || !visited.add(node.id())) {
                continue;
            }
            String type = node.normalizedType();
            String dbId = Texts.truncate(node.id(), 32);
            if (WorkflowNodeDefinition.TYPE_AUTO_CHECK.equals(type)) {
                String skill = node.effectiveSkill(DEFAULT_SKILL);
                if (DEFAULT_SKILL.equalsIgnoreCase(skill)) {
                    decision = autoDecisionService.decide(approval);
                    planned.add(new PlannedNode(dbId, WorkflowNodeDefinition.TYPE_AUTO_CHECK,
                            NodeType.AUTO, NodeState.APPROVED, null, null,
                            Texts.truncate("skill=approval-policy decision=" + decision.decision()
                                    + " source=" + decision.source() + " " + decision.detail(), 512)));
                    pendingRole = decision.requiredRole();
                    pendingSla = decision.slaHours() > 0 ? decision.slaHours() : null;
                    if (decision.decision() == AutoDecision.AUTO_APPROVE
                            || decision.decision() == AutoDecision.REJECT) {
                        return new WorkflowPlan(planned, decision, false, null, null, null,
                                workflow.code(), workflow.version(), List.copyOf(annotations));
                    }
                } else {
                    String summary = runAuxiliarySkill(approval, skill);
                    planned.add(new PlannedNode(dbId, WorkflowNodeDefinition.TYPE_AUTO_CHECK,
                            NodeType.AUTO, NodeState.APPROVED, null, null,
                            Texts.truncate("skill=" + skill + " " + summary, 512)));
                }
            } else if (WorkflowNodeDefinition.TYPE_HUMAN.equals(type)) {
                String role = node.effectiveRole(pendingRole == null ? WorkflowNodes.ROLE_L1 : pendingRole);
                int slaHours = node.slaHours() != null && node.slaHours() > 0
                        ? node.slaHours()
                        : (slaOverride != null ? slaOverride
                        : (pendingSla != null && pendingSla > 0 ? pendingSla : properties.getL1SlaHours()));
                planned.add(new PlannedNode(dbId, WorkflowNodeDefinition.TYPE_HUMAN,
                        NodeType.HUMAN, NodeState.PENDING, role, slaHours,
                        Texts.truncate("role=" + role + " slaHours=" + slaHours
                                + " definitionNode=" + node.id(), 512)));
                annotations.add("HUMAN node '" + node.id() + "' waits for role " + role);
                return new WorkflowPlan(planned, decision, true, dbId, role, slaHours,
                        workflow.code(), workflow.version(), List.copyOf(annotations));
            } else if (WorkflowNodeDefinition.TYPE_SLA.equals(type)) {
                int hours = node.slaHours() == null || node.slaHours() <= 0
                        ? properties.getL1SlaHours() : node.slaHours();
                slaOverride = hours;
                planned.add(new PlannedNode(dbId, WorkflowNodeDefinition.TYPE_SLA,
                        NodeType.AUTO, NodeState.APPROVED, null, hours, "slaHours=" + hours));
            } else if (WorkflowNodeDefinition.TYPE_PARALLEL.equals(type)) {
                List<String> childIds = node.children().stream()
                        .map(WorkflowNodeDefinition::id)
                        .filter(Objects::nonNull)
                        .toList();
                planned.add(new PlannedNode(dbId, WorkflowNodeDefinition.TYPE_PARALLEL,
                        NodeType.AUTO, NodeState.APPROVED, null, null,
                        Texts.truncate("[PARALLEL] executed sequentially: " + childIds, 512)));
                annotations.add("PARALLEL node '" + node.id()
                        + "' executed sequentially (v12 placeholder)");
                for (WorkflowNodeDefinition child : node.children()) {
                    if (child.id() != null && !visited.contains(child.id())) {
                        queue.addLast(child);
                    }
                }
                if (node.next() != null && byId.containsKey(node.next())) {
                    queue.addLast(byId.get(node.next()));
                }
                continue;
            } else {
                log.warn("workflow {} v{} contains unsupported node type '{}' (node {}), skipping",
                        workflow.code(), workflow.version(), node.type(), node.id());
                annotations.add("unsupported node type '" + node.type() + "' skipped: " + node.id());
            }
            if (node.next() != null && byId.containsKey(node.next())) {
                queue.addFirst(byId.get(node.next()));
            }
        }

        if (decision == null) {
            decision = autoDecisionService.decide(approval);
        }
        if (decision.decision() == AutoDecision.AUTO_APPROVE || decision.decision() == AutoDecision.REJECT) {
            return new WorkflowPlan(planned, decision, false, null, null, null,
                    workflow.code(), workflow.version(), List.copyOf(annotations));
        }
        String role = decision.requiredRole() == null ? WorkflowNodes.ROLE_L1 : decision.requiredRole();
        int slaHours = decision.slaHours() > 0 ? decision.slaHours() : properties.getL1SlaHours();
        planned.add(new PlannedNode(FALLBACK_HUMAN_NODE, WorkflowNodeDefinition.TYPE_HUMAN,
                NodeType.HUMAN, NodeState.PENDING, role, slaHours,
                "fallback human node generated by the workflow engine"));
        annotations.add("no HUMAN node in definition; fallback human node generated");
        return new WorkflowPlan(planned, decision, true, FALLBACK_HUMAN_NODE, role, slaHours,
                workflow.code(), workflow.version(), List.copyOf(annotations));
    }

    private String runAuxiliarySkill(ApprovalEntity approval, String skill) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("tenantId", approval.getTenantId());
        input.put("tenantCode", approval.getTenantCode());
        input.put("plan", approval.getPlan());
        input.put("riskLevel", approval.getRiskLevel());
        input.put("riskScore", approval.getRiskScore());
        input.put("reasons", approval.getReasons() == null ? List.of() : approval.getReasons());
        try {
            JsonNode result = agentServiceClient.runSkill(skill, input, approval.getTenantId());
            if (result == null || result.isNull() || result.isMissingNode()) {
                return "no result";
            }
            String verdict = result.path("verdict").asText("");
            String decision = result.path("decision").asText("");
            int findings = result.path("findings").isArray() ? result.path("findings").size() : 0;
            return "verdict=" + verdict + " decision=" + decision + " findings=" + findings;
        } catch (RuntimeException ex) {
            log.warn("auxiliary AUTO_CHECK skill '{}' unavailable for approval {}: {}",
                    skill, approval.getId(), ex.getMessage());
            return "unavailable: " + ex.getMessage();
        }
    }
}
