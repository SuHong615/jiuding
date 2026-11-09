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

import com.suhong.jiuding.approval.api.ApprovalCreateRequest;
import com.suhong.jiuding.approval.api.ApprovalOverview;
import com.suhong.jiuding.approval.api.ApprovalView;
import com.suhong.jiuding.approval.client.TenantServiceClient;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.ApprovalNodeEntity;
import com.suhong.jiuding.approval.domain.ApprovalState;
import com.suhong.jiuding.approval.domain.AutoDecision;
import com.suhong.jiuding.approval.domain.DecisionType;
import com.suhong.jiuding.approval.domain.NodeState;
import com.suhong.jiuding.approval.domain.NodeType;
import com.suhong.jiuding.approval.domain.WorkflowNodes;
import com.suhong.jiuding.approval.repo.ApprovalRepository;
import com.suhong.jiuding.approval.support.Texts;
import com.suhong.jiuding.approval.workflowdef.PlannedNode;
import com.suhong.jiuding.approval.workflowdef.PublishedWorkflow;
import com.suhong.jiuding.approval.workflowdef.WorkflowDefinitionModel;
import com.suhong.jiuding.approval.workflowdef.WorkflowDefinitionService;
import com.suhong.jiuding.approval.workflowdef.WorkflowEngine;
import com.suhong.jiuding.approval.workflowdef.WorkflowPlan;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Core approval state machine, decisions, SLA escalation and read models. */
@Service
public class ApprovalService {

    private static final Logger log = LoggerFactory.getLogger(ApprovalService.class);
    private static final Set<String> PLANS = Set.of("FREE", "PRO", "ENTERPRISE");
    private static final int MAX_REASONS = 20;
    private static final int MAX_REASON_LENGTH = 256;

    private final ApprovalRepository repository;
    private final ApprovalViewMapper viewMapper;
    private final AutoDecisionService autoDecisionService;
    private final ProvisioningService provisioningService;
    private final TenantServiceClient tenantServiceClient;
    private final ApprovalProperties properties;
    private final WorkflowDefinitionService workflowDefinitionService;
    private final WorkflowEngine workflowEngine;

    public ApprovalService(ApprovalRepository repository,
                           ApprovalViewMapper viewMapper,
                           AutoDecisionService autoDecisionService,
                           ProvisioningService provisioningService,
                           TenantServiceClient tenantServiceClient,
                           ApprovalProperties properties,
                           WorkflowDefinitionService workflowDefinitionService,
                           WorkflowEngine workflowEngine) {
        this.repository = repository;
        this.viewMapper = viewMapper;
        this.autoDecisionService = autoDecisionService;
        this.provisioningService = provisioningService;
        this.tenantServiceClient = tenantServiceClient;
        this.properties = properties;
        this.workflowDefinitionService = workflowDefinitionService;
        this.workflowEngine = workflowEngine;
    }

    // ------------------------------------------------------------------ create

    public ApprovalView create(ApprovalCreateRequest request) {
        Principal principal = AuthContext.get();
        String actor = Texts.firstNonBlank(request.requestedBy(),
                principal == null ? "system" : Texts.firstNonBlank(principal.username(), "system"));
        String tenantId = request.tenantId().trim();
        String plan = normalizePlan(request.plan());
        String riskLevel = AutoDecisionService.normalizeRisk(request.riskLevel(), request.riskScore());
        Integer riskScore = request.riskScore() == null ? deriveScore(riskLevel) : clamp(request.riskScore());
        List<String> reasons = normalizeReasons(request.reasons());

        Optional<ApprovalEntity> live = repository.findActiveByTenant(tenantId);
        if (live.isPresent()) {
            log.info("reusing live approval {} for tenant {}", live.get().getId(), tenantId);
            return viewMapper.toView(live.get());
        }

        Optional<PublishedWorkflow> published = workflowDefinitionService.resolvePublished(request.workflowCode());
        if (published.isPresent()) {
            return createFromDefinition(request, published.get(), actor, plan, riskLevel, riskScore, reasons);
        }
        return createDefaultFlow(request, actor, plan, riskLevel, riskScore, reasons);
    }

    /** Built-in fallback flow used when no published workflow definition applies (pre-v12 behaviour). */
    private ApprovalView createDefaultFlow(ApprovalCreateRequest request, String actor, String plan,
                                           String riskLevel, Integer riskScore, List<String> reasons) {
        Instant now = Instant.now();
        ApprovalEntity approval = buildEntity(request, plan, riskLevel, riskScore, reasons, now);
        approval.setState(ApprovalState.RISK_CHECK);
        approval.setCurrentNode(WorkflowNodes.RISK_CHECK);
        repository.insert(approval);

        addNode(approval, WorkflowNodes.RISK_CHECK, NodeType.AUTO, NodeState.APPROVED, null,
                "system", now, "riskLevel=" + riskLevel + " riskScore=" + riskScore, null);
        repository.appendTimeline(approval.getId(), actor, "CREATED",
                "tenant=" + approval.getTenantCode() + " plan=" + plan + " risk=" + riskLevel + "/" + riskScore);
        repository.appendTimeline(approval.getId(), "system", "RISK_CHECK",
                "riskLevel=" + riskLevel + " riskScore=" + riskScore
                        + (reasons.isEmpty() ? "" : " reasons=" + String.join("; ", reasons)));
        if (request.workflowCode() != null && !request.workflowCode().isBlank()) {
            repository.appendTimeline(approval.getId(), actor, "WORKFLOW_FALLBACK",
                    "no published workflow for code=" + request.workflowCode() + ", default flow used");
        }

        approval.setState(ApprovalState.AUTO_DECISION);
        approval.setCurrentNode(WorkflowNodes.AUTO_DECISION);
        approval.setUpdatedAt(Instant.now());
        save(approval);
        addNode(approval, WorkflowNodes.AUTO_DECISION, NodeType.AUTO, NodeState.PENDING, null, null, null, null, null);

        return runAutoDecision(approval, actor);
    }

    /** Creates an approval whose nodes/decision come from a published workflow definition (v12). */
    private ApprovalView createFromDefinition(ApprovalCreateRequest request, PublishedWorkflow workflow, String actor,
                                              String plan, String riskLevel, Integer riskScore, List<String> reasons) {
        Instant now = Instant.now();
        ApprovalEntity approval = buildEntity(request, plan, riskLevel, riskScore, reasons, now);
        WorkflowDefinitionModel model = workflow.model();
        String entryNode = model.entry() == null || model.entry().id() == null
                ? WorkflowNodes.RISK_CHECK
                : Texts.truncate(model.entry().id(), 32);
        approval.setState(ApprovalState.RISK_CHECK);
        approval.setCurrentNode(entryNode);
        repository.insert(approval);
        repository.appendTimeline(approval.getId(), actor, "CREATED",
                "tenant=" + approval.getTenantCode() + " workflow=" + workflow.code() + "@" + workflow.version()
                        + " plan=" + plan + " risk=" + riskLevel + "/" + riskScore);
        repository.appendTimeline(approval.getId(), actor, "WORKFLOW_SELECTED",
                "code=" + workflow.code() + " version=" + workflow.version()
                        + " nodes=" + model.flatten().size());

        WorkflowPlan planResult = workflowEngine.plan(approval, workflow);
        Instant plannedAt = Instant.now();
        for (PlannedNode planned : planResult.nodes()) {
            boolean decided = planned.state() == NodeState.APPROVED;
            addNode(approval, planned.nodeId(), planned.dbNodeType(), planned.state(), planned.role(),
                    decided ? "workflow-engine" : null,
                    decided ? plannedAt : null,
                    planned.comment(), planned.slaHours());
            repository.appendTimeline(approval.getId(), "workflow-engine",
                    Texts.truncate(planned.nodeType(), 64), Texts.truncate(planned.comment(), 1024));
        }
        repository.appendTimeline(approval.getId(), actor, "WORKFLOW_PLAN",
                "code=" + workflow.code() + " version=" + workflow.version()
                        + " nodes=" + planResult.nodes().size()
                        + (planResult.annotations().isEmpty()
                        ? "" : " notes=" + String.join("; ", planResult.annotations())));

        if (planResult.waitingAtHuman()) {
            approval.setState(ApprovalState.PENDING_APPROVAL);
            approval.setCurrentNode(Texts.truncate(planResult.humanNodeId(), 32));
            approval.setAutoDecided(false);
            approval.setSlaDueAt(Instant.now().plusSeconds(planResult.humanSlaHours() * 3600L));
            approval.setUpdatedAt(Instant.now());
            save(approval);
            repository.appendTimeline(approval.getId(), actor, "ROUTED_TO_APPROVAL",
                    "definition node=" + planResult.humanNodeId() + " role=" + planResult.humanRole()
                            + " slaHours=" + planResult.humanSlaHours());
            log.info("approval {} uses workflow {} v{} and waits at human node {}",
                    approval.getId(), workflow.code(), workflow.version(), planResult.humanNodeId());
            return viewMapper.toView(reload(approval.getId()));
        }

        AutoDecisionOutcome outcome = planResult.decision();
        if (outcome == null) {
            outcome = new AutoDecisionOutcome(AutoDecision.NEEDS_REVIEW, WorkflowNodes.ROLE_L1,
                    properties.getL1SlaHours(), "workflow-definition", "definition produced no decision");
        }
        return switch (outcome.decision()) {
            case AUTO_APPROVE -> autoApprove(approval, outcome);
            case REJECT -> autoReject(approval, outcome);
            case NEEDS_REVIEW, NONE -> routeToHuman(approval, outcome, actor);
        };
    }

    private ApprovalEntity buildEntity(ApprovalCreateRequest request, String plan, String riskLevel,
                                       Integer riskScore, List<String> reasons, Instant now) {
        ApprovalEntity approval = new ApprovalEntity();
        approval.setId(UUID.randomUUID().toString());
        approval.setTenantId(request.tenantId().trim());
        approval.setTenantCode(request.tenantCode().trim());
        approval.setPlan(plan);
        approval.setRiskLevel(riskLevel);
        approval.setRiskScore(riskScore);
        approval.setAutoDecided(false);
        approval.setEscalated(false);
        approval.setReasons(reasons);
        approval.setCreatedAt(now);
        approval.setUpdatedAt(now);
        approval.setVersion(0);
        return approval;
    }

    private ApprovalView runAutoDecision(ApprovalEntity approval, String actor) {
        AutoDecisionOutcome outcome = autoDecisionService.decide(approval);
        Instant now = Instant.now();
        findNode(approval.getId(), WorkflowNodes.AUTO_DECISION).ifPresent(node -> {
            node.setState(NodeState.APPROVED);
            node.setDecidedBy("agent");
            node.setDecidedAt(now);
            node.setComment(Texts.truncate(outcome.detail(), 512));
            repository.updateNode(node);
        });
        repository.appendTimeline(approval.getId(), "agent", "AUTO_DECISION", outcome.detail());
        return switch (outcome.decision()) {
            case AUTO_APPROVE -> autoApprove(approval, outcome);
            case REJECT -> autoReject(approval, outcome);
            case NEEDS_REVIEW, NONE -> routeToHuman(approval, outcome, actor);
        };
    }

    private ApprovalView autoApprove(ApprovalEntity approval, AutoDecisionOutcome outcome) {
        Instant now = Instant.now();
        approval.setAutoDecided(true);
        approval.setDecidedBy("agent");
        approval.setDecisionNote(Texts.truncate("auto approved (" + outcome.source() + ")", 512));
        approval.setState(ApprovalState.AUTO_APPROVED);
        approval.setCurrentNode(WorkflowNodes.APPROVED);
        approval.setUpdatedAt(now);
        save(approval);
        repository.appendTimeline(approval.getId(), "agent", "AUTO_APPROVED",
                "decision=AUTO_APPROVE source=" + outcome.source());

        Instant approvedAt = Instant.now();
        approval.setState(ApprovalState.APPROVED);
        approval.setCurrentNode(WorkflowNodes.APPROVED);
        approval.setUpdatedAt(approvedAt);
        save(approval);
        addNode(approval, WorkflowNodes.APPROVED, NodeType.AUTO, NodeState.APPROVED, null,
                "agent", approvedAt, outcome.detail(), null);
        repository.appendTimeline(approval.getId(), "agent", "APPROVED",
                "approval accepted automatically (" + outcome.source() + ")");
        provisioningService.provision(approval.getId(), "agent");
        return viewMapper.toView(reload(approval.getId()));
    }

    private ApprovalView autoReject(ApprovalEntity approval, AutoDecisionOutcome outcome) {
        Instant now = Instant.now();
        approval.setAutoDecided(true);
        approval.setDecidedBy("agent");
        approval.setDecisionNote(Texts.truncate("auto rejected: " + outcome.detail(), 512));
        approval.setState(ApprovalState.REJECTED);
        approval.setCurrentNode(null);
        approval.setUpdatedAt(now);
        save(approval);
        addNode(approval, WorkflowNodes.REJECTED, NodeType.AUTO, NodeState.REJECTED, null,
                "agent", now, outcome.detail(), null);
        repository.appendTimeline(approval.getId(), "agent", "AUTO_REJECTED", outcome.detail());
        notifyTenantRejected(approval);
        return viewMapper.toView(reload(approval.getId()));
    }

    private ApprovalView routeToHuman(ApprovalEntity approval, AutoDecisionOutcome outcome, String actor) {
        int slaHours = outcome.slaHours() > 0 ? outcome.slaHours() : properties.getL1SlaHours();
        String role = outcome.requiredRole() == null ? WorkflowNodes.ROLE_L1 : outcome.requiredRole();
        Instant now = Instant.now();
        approval.setAutoDecided(false);
        approval.setDecidedBy(null);
        approval.setDecisionNote(null);
        approval.setState(ApprovalState.PENDING_APPROVAL);
        approval.setCurrentNode(WorkflowNodes.PENDING_APPROVAL);
        approval.setSlaDueAt(now.plusSeconds(slaHours * 3600L));
        approval.setUpdatedAt(now);
        save(approval);
        addNode(approval, WorkflowNodes.PENDING_APPROVAL, NodeType.HUMAN, NodeState.PENDING,
                role, null, null, null, slaHours);
        repository.appendTimeline(approval.getId(), actor, "ROUTED_TO_APPROVAL",
                "role=" + role + " slaHours=" + slaHours + " " + outcome.detail());
        log.info("approval {} routed to {} (sla {}h)", approval.getId(), role, slaHours);
        return viewMapper.toView(reload(approval.getId()));
    }

    // --------------------------------------------------------------- decisions

    public ApprovalView decide(String id, String decisionRaw, String comment) {
        Principal principal = AuthContext.require();
        requireApprover(principal);
        DecisionType decision = DecisionType.parse(decisionRaw);
        if (decision == null) {
            throw BizException.invalid("decision must be APPROVE or REJECT");
        }
        if (decision == DecisionType.REJECT && (comment == null || comment.isBlank())) {
            throw BizException.invalid("comment is required when rejecting an approval");
        }
        ApprovalEntity approval = loadScoped(id);
        if (approval.getState() != ApprovalState.PENDING_APPROVAL) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "approval is not pending: " + approval.getState());
        }

        String actor = principal.username();
        Instant now = Instant.now();
        ApprovalNodeEntity human = repository.findPendingHumanNode(id).orElse(null);

        if (decision == DecisionType.APPROVE) {
            approval.setDecidedBy(actor);
            approval.setDecisionNote(Texts.truncate(comment, 512));
            approval.setState(ApprovalState.APPROVED);
            approval.setCurrentNode(WorkflowNodes.APPROVED);
            approval.setUpdatedAt(now);
            save(approval);
            completeHumanNode(human, NodeState.APPROVED, actor, now, comment);
            repository.appendTimeline(id, actor, "DECISION_APPROVED",
                    comment == null || comment.isBlank() ? "approved by " + actor : comment);
            provisioningService.provision(id, actor);
        } else {
            approval.setDecidedBy(actor);
            approval.setDecisionNote(Texts.truncate(comment, 512));
            approval.setState(ApprovalState.REJECTED);
            approval.setCurrentNode(null);
            approval.setUpdatedAt(now);
            save(approval);
            completeHumanNode(human, NodeState.REJECTED, actor, now, comment);
            repository.appendTimeline(id, actor, "DECISION_REJECTED", comment);
            notifyTenantRejected(approval);
        }
        return viewMapper.toView(reload(id));
    }

    private void completeHumanNode(ApprovalNodeEntity node, NodeState state, String actor,
                                   Instant at, String comment) {
        if (node == null) {
            return;
        }
        node.setState(state);
        node.setDecidedBy(actor);
        node.setDecidedAt(at);
        node.setComment(Texts.truncate(comment, 512));
        repository.updateNode(node);
    }

    // -------------------------------------------------------------- escalation

    /** Manual escalation (APPROVER / PLATFORM_ADMIN); idempotent once escalated. */
    public ApprovalView escalate(String id, String reason) {
        Principal principal = AuthContext.require();
        requireApprover(principal);
        loadScoped(id);
        escalateInternal(id, reason, principal.username(), true);
        return viewMapper.toView(reload(id));
    }

    /** Called by the scheduler: escalates every overdue PENDING_APPROVAL once. */
    public int escalateOverduePending() {
        List<String> ids = repository.findOverduePendingIds(Instant.now(), 50);
        int escalated = 0;
        for (String id : ids) {
            try {
                if (escalateInternal(id, "SLA breached", "scheduler", false)) {
                    escalated++;
                }
            } catch (RuntimeException ex) {
                log.warn("SLA escalation failed for approval {}: {}", id, ex.getMessage());
            }
        }
        return escalated;
    }

    private boolean escalateInternal(String id, String reason, String actor, boolean scoped) {
        ApprovalEntity approval = scoped
                ? loadScoped(id)
                : repository.findById(id).orElse(null);
        if (approval == null) {
            return false;
        }
        if (approval.getState() != ApprovalState.PENDING_APPROVAL) {
            if (scoped) {
                throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                        "only PENDING_APPROVAL can be escalated: " + approval.getState());
            }
            return false;
        }
        if (approval.isEscalated()) {
            return false;
        }
        Instant now = Instant.now();
        if (repository.markEscalated(id, now) == 0) {
            return false;
        }
        ApprovalNodeEntity human = repository.findPendingHumanNode(id).orElse(null);
        if (human != null) {
            human.setAssigneeRole(WorkflowNodes.ROLE_L2);
            human.setSlaHours(properties.getL2SlaHours());
            repository.updateNode(human);
        }
        String detail = "escalated to " + WorkflowNodes.ROLE_L2 + " (SLA breached); reason="
                + (reason == null || reason.isBlank() ? "SLA breached" : reason);
        repository.appendTimeline(id, actor, "ESCALATED", detail);
        log.warn("approval {} escalated to {}: {}", id, WorkflowNodes.ROLE_L2, reason);
        return true;
    }

    // ------------------------------------------------------------------- reads

    public ApprovalView get(String id) {
        return viewMapper.toView(loadScoped(id));
    }

    public PageResult<ApprovalView> list(String state, int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw BizException.invalid("page must be >= 0 and size between 1 and 100");
        }
        String normalizedState = normalizeStateFilter(state);
        String tenantFilter = scopedTenant();
        long total = repository.count(normalizedState, tenantFilter);
        List<ApprovalView> items = repository.findPage(normalizedState, tenantFilter, page, size).stream()
                .map(viewMapper::toView)
                .toList();
        return PageResult.of(items, total, page, size);
    }

    public List<ApprovalView> pending(int limit) {
        int capped = limit <= 0 ? 50 : Math.min(limit, 200);
        return repository.findPending(scopedTenant(), capped).stream().map(viewMapper::toView).toList();
    }

    public ApprovalOverview overview() {
        return repository.overview(scopedTenant(), Instant.now());
    }

    // ----------------------------------------------------------------- helpers

    private void requireApprover(Principal principal) {
        if (principal.isPlatformAdmin() || principal.hasRole(WorkflowNodes.ROLE_L1)) {
            return;
        }
        throw BizException.forbidden("decision requires APPROVER or PLATFORM_ADMIN");
    }

    private ApprovalEntity loadScoped(String id) {
        ApprovalEntity approval = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("approval not found: " + id));
        String scoped = scopedTenant();
        if (scoped != null && !scoped.equals(approval.getTenantId())) {
            throw BizException.notFound("approval not found: " + id);
        }
        return approval;
    }

    /** Platform admins and platform-wide roles (tenantId null) see every tenant. */
    private String scopedTenant() {
        TenantScope scope = TenantContext.get();
        if (scope == null || scope.isPlatformAdmin()) {
            return null;
        }
        String tenantId = scope.tenantId();
        return tenantId == null || tenantId.isBlank() ? null : tenantId;
    }

    private ApprovalEntity reload(String id) {
        return repository.findById(id)
                .orElseThrow(() -> BizException.notFound("approval not found: " + id));
    }

    private void save(ApprovalEntity approval) {
        if (!repository.update(approval)) {
            throw BizException.conflict("approval " + approval.getId() + " was modified concurrently");
        }
    }

    private ApprovalNodeEntity addNode(ApprovalEntity approval, String node, NodeType type, NodeState state,
                                       String role, String decidedBy, Instant decidedAt, String comment,
                                       Integer slaHours) {
        ApprovalNodeEntity entity = new ApprovalNodeEntity();
        entity.setApprovalId(approval.getId());
        entity.setSeq(repository.nextNodeSeq(approval.getId()));
        entity.setNode(node);
        entity.setNodeType(type);
        entity.setState(state);
        entity.setAssigneeRole(role);
        entity.setDecidedBy(decidedBy);
        entity.setDecidedAt(decidedAt);
        entity.setComment(Texts.truncate(comment, 512));
        entity.setSlaHours(slaHours);
        return repository.insertNode(entity);
    }

    private Optional<ApprovalNodeEntity> findNode(String approvalId, String node) {
        return repository.findNodes(approvalId).stream()
                .filter(candidate -> node.equals(candidate.getNode()))
                .findFirst();
    }

    private void notifyTenantRejected(ApprovalEntity approval) {
        try {
            tenantServiceClient.updateStatus(approval.getTenantId(), "REJECTED",
                    "approval " + approval.getId() + " rejected");
            repository.appendTimeline(approval.getId(), "system", "TENANT_STATUS", "tenant notified: REJECTED");
        } catch (RuntimeException ex) {
            log.info("tenant REJECTED callback failed for approval {}: {}", approval.getId(), ex.getMessage());
            repository.appendTimeline(approval.getId(), "system", "TENANT_STATUS_FAILED",
                    Texts.truncate("REJECTED callback failed: " + ex.getMessage(), 1024));
        }
    }

    private String normalizeStateFilter(String state) {
        if (state == null || state.isBlank()) {
            return null;
        }
        try {
            return ApprovalState.valueOf(state.trim().toUpperCase(Locale.ROOT)).name();
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown approval state: " + state);
        }
    }

    private String normalizePlan(String plan) {
        if (plan == null || plan.isBlank()) {
            return "PRO";
        }
        String normalized = plan.trim().toUpperCase(Locale.ROOT);
        if (!PLANS.contains(normalized)) {
            throw BizException.invalid("plan must be one of FREE, PRO, ENTERPRISE");
        }
        return normalized;
    }

    private List<String> normalizeReasons(List<String> reasons) {
        if (reasons == null || reasons.isEmpty()) {
            return List.of();
        }
        List<String> normalized = new ArrayList<>();
        for (String reason : reasons) {
            if (reason == null || reason.isBlank()) {
                continue;
            }
            normalized.add(Texts.truncate(reason.trim(), MAX_REASON_LENGTH));
            if (normalized.size() >= MAX_REASONS) {
                break;
            }
        }
        return List.copyOf(normalized);
    }

    private static int clamp(Integer score) {
        return Math.max(0, Math.min(100, score));
    }

    private static int deriveScore(String riskLevel) {
        return switch (riskLevel) {
            case "LOW" -> 10;
            case "HIGH" -> 80;
            default -> 50;
        };
    }
}
