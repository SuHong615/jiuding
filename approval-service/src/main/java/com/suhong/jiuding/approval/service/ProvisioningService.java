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
import com.suhong.jiuding.approval.client.DownstreamException;
import com.suhong.jiuding.approval.client.TenantServiceClient;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import com.suhong.jiuding.approval.domain.ApprovalEntity;
import com.suhong.jiuding.approval.domain.ApprovalNodeEntity;
import com.suhong.jiuding.approval.domain.ApprovalState;
import com.suhong.jiuding.approval.domain.NodeState;
import com.suhong.jiuding.approval.domain.NodeType;
import com.suhong.jiuding.approval.domain.WorkflowNodes;
import com.suhong.jiuding.approval.repo.ApprovalRepository;
import com.suhong.jiuding.approval.support.Texts;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Post-approval provisioning hand-off.
 *
 * <p>Sequence: tenant status {@code APPROVED} -&gt; (best effort) {@code PROVISIONING}
 * -&gt; agent-service {@code provisioning} skill -&gt; tenant status {@code ACTIVE}.
 * Any downstream failure leaves the approval in {@code PROVISIONING} and records a
 * timeline entry; {@link #retryPending()} is invoked by the 30s scheduler so the
 * chain is retried without surfacing 5xx to the caller.
 */
@Service
public class ProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);

    private final ApprovalRepository repository;
    private final TenantServiceClient tenantServiceClient;
    private final AgentServiceClient agentServiceClient;
    private final ApprovalProperties properties;

    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();
    private final Map<String, Instant> lastFailureTimeline = new ConcurrentHashMap<>();

    public ProvisioningService(ApprovalRepository repository,
                               TenantServiceClient tenantServiceClient,
                               AgentServiceClient agentServiceClient,
                               ApprovalProperties properties) {
        this.repository = repository;
        this.tenantServiceClient = tenantServiceClient;
        this.agentServiceClient = agentServiceClient;
        this.properties = properties;
    }

    /** Starts (or continues) the provisioning chain for an approval; never throws for downstream failures. */
    public void provision(String approvalId, String actor) {
        if (!inFlight.add(approvalId)) {
            log.debug("provisioning already in flight for approval {}", approvalId);
            return;
        }
        try {
            ApprovalEntity approval = repository.findById(approvalId).orElse(null);
            if (approval == null) {
                return;
            }
            if (approval.getState() == ApprovalState.ACTIVE || approval.getState() == ApprovalState.REJECTED) {
                return;
            }
            if (approval.getState() != ApprovalState.PROVISIONING) {
                Instant now = Instant.now();
                approval.setState(ApprovalState.PROVISIONING);
                approval.setCurrentNode(WorkflowNodes.PROVISIONING);
                approval.setUpdatedAt(now);
                if (!repository.update(approval)) {
                    log.warn("approval {} changed concurrently before provisioning, retry later", approvalId);
                    return;
                }
                ApprovalNodeEntity node = new ApprovalNodeEntity();
                node.setApprovalId(approvalId);
                node.setSeq(repository.nextNodeSeq(approvalId));
                node.setNode(WorkflowNodes.PROVISIONING);
                node.setNodeType(NodeType.AUTO);
                node.setState(NodeState.PENDING);
                repository.insertNode(node);
                repository.appendTimeline(approvalId, actor, "PROVISIONING_STARTED",
                        "downstream provisioning started");
            }
            doProvision(approval, actor);
        } catch (RuntimeException ex) {
            log.warn("unexpected provisioning error for approval {}: {}", approvalId, ex.getMessage());
            recordFailure(approvalId, "unexpected: " + ex.getMessage());
        } finally {
            inFlight.remove(approvalId);
        }
    }

    /** Retries every approval stuck in PROVISIONING due to a downstream failure. */
    public int retryPending() {
        List<String> ids = repository.findProvisioningIds(20);
        int retried = 0;
        for (String id : ids) {
            try {
                provision(id, "scheduler");
                retried++;
            } catch (RuntimeException ex) {
                log.warn("provisioning retry failed for approval {}: {}", id, ex.getMessage());
            }
        }
        return retried;
    }

    private void doProvision(ApprovalEntity approval, String actor) {
        String approvalId = approval.getId();
        String tenantId = approval.getTenantId();
        try {
            callApproved(approval);
            repository.appendTimeline(approvalId, actor, "TENANT_APPROVED", "tenant " + tenantId + " set to APPROVED");

            try {
                tenantServiceClient.updateStatus(tenantId, "PROVISIONING",
                        "approval " + approvalId + " provisioning");
            } catch (DownstreamException ex) {
                // Some tenant-service builds move APPROVED -> PROVISIONING on their own.
                log.debug("tenant {} PROVISIONING transition skipped: {}", tenantId, ex.getMessage());
            }

            JsonNode tenant = safeTenant(tenantId);
            Map<String, Object> input = buildProvisioningInput(approval, tenant);
            JsonNode provisioningResult = agentServiceClient.runSkill("provisioning", input, tenantId);
            if (provisioningResult != null
                    && "FAIL".equalsIgnoreCase(provisioningResult.path("verdict").asText(""))) {
                throw new DownstreamException("provisioning skill returned verdict FAIL");
            }
            repository.appendTimeline(approvalId, actor, "PROVISIONING_SKILL", "provisioning skill executed");

            tenantServiceClient.updateStatus(tenantId, "ACTIVE", "approval " + approvalId + " provisioned");
            markSuccess(approval, actor);
        } catch (DownstreamException ex) {
            recordFailure(approvalId, ex.getMessage());
        } catch (RuntimeException ex) {
            recordFailure(approvalId, "unexpected: " + ex.getMessage());
        }
    }

    private void callApproved(ApprovalEntity approval) {
        try {
            tenantServiceClient.updateStatus(approval.getTenantId(), "APPROVED",
                    "approval " + approval.getId() + " approved");
        } catch (DownstreamException ex) {
            if (tenantAlreadyAdvanced(approval.getTenantId())) {
                log.info("tenant {} already advanced, continuing provisioning for approval {}",
                        approval.getTenantId(), approval.getId());
                return;
            }
            throw ex;
        }
    }

    private boolean tenantAlreadyAdvanced(String tenantId) {
        JsonNode tenant = safeTenant(tenantId);
        if (tenant == null) {
            return false;
        }
        String status = tenant.path("status").asText("");
        return "APPROVED".equalsIgnoreCase(status)
                || "PROVISIONING".equalsIgnoreCase(status)
                || "ACTIVE".equalsIgnoreCase(status);
    }

    private JsonNode safeTenant(String tenantId) {
        try {
            return tenantServiceClient.getTenant(tenantId);
        } catch (RuntimeException ex) {
            log.debug("tenant snapshot unavailable for {}: {}", tenantId, ex.getMessage());
            return null;
        }
    }

    private Map<String, Object> buildProvisioningInput(ApprovalEntity approval, JsonNode tenant) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("approvalId", approval.getId());
        input.put("tenantId", approval.getTenantId());
        input.put("tenantCode", approval.getTenantCode());
        input.put("plan", approval.getPlan());
        input.put("riskLevel", approval.getRiskLevel());
        input.put("riskScore", approval.getRiskScore());
        if (tenant != null && !tenant.isNull()) {
            copyText(input, tenant, "region");
            copyText(input, tenant, "isolationMode");
            copyText(input, tenant, "name");
            copyText(input, tenant, "status");
            copyNumber(input, tenant, "quotaSeats");
            copyNumber(input, tenant, "quotaStorageGb");
            copyNumber(input, tenant, "quotaApiRpm");
        }
        return input;
    }

    private void copyText(Map<String, Object> input, JsonNode tenant, String field) {
        JsonNode value = tenant.path(field);
        if (value.isTextual() && !value.asText().isBlank()) {
            input.put(field, value.asText());
        }
    }

    private void copyNumber(Map<String, Object> input, JsonNode tenant, String field) {
        JsonNode value = tenant.path(field);
        if (value.isNumber()) {
            input.put(field, value.asInt());
        }
    }

    private void markSuccess(ApprovalEntity approval, String actor) {
        String approvalId = approval.getId();
        Instant now = Instant.now();
        List<ApprovalNodeEntity> nodes = new ArrayList<>(repository.findNodes(approvalId));
        nodes.stream()
                .filter(node -> WorkflowNodes.PROVISIONING.equals(node.getNode()))
                .filter(node -> node.getState() == NodeState.PENDING)
                .reduce((first, second) -> second)
                .ifPresent(node -> {
                    node.setState(NodeState.APPROVED);
                    node.setDecidedBy("provisioning-agent");
                    node.setDecidedAt(now);
                    node.setComment("resources provisioned");
                    repository.updateNode(node);
                });

        ApprovalNodeEntity active = new ApprovalNodeEntity();
        active.setApprovalId(approvalId);
        active.setSeq(repository.nextNodeSeq(approvalId));
        active.setNode(WorkflowNodes.ACTIVE);
        active.setNodeType(NodeType.AUTO);
        active.setState(NodeState.APPROVED);
        active.setDecidedBy("provisioning-agent");
        active.setDecidedAt(now);
        active.setComment("tenant ACTIVE");
        repository.insertNode(active);

        approval.setState(ApprovalState.ACTIVE);
        approval.setCurrentNode(WorkflowNodes.ACTIVE);
        approval.setUpdatedAt(now);
        if (!repository.update(approval)) {
            log.warn("approval {} changed concurrently before ACTIVE, retry later", approvalId);
            return;
        }
        repository.appendTimeline(approvalId, actor, "PROVISIONED", "tenant " + approval.getTenantId() + " is ACTIVE");
        lastFailureTimeline.remove(approvalId);
        log.info("approval {} provisioned, tenant {} is ACTIVE", approvalId, approval.getTenantId());
    }

    private void recordFailure(String approvalId, String message) {
        log.warn("provisioning failed for approval {}: {} (stays PROVISIONING, will retry)", approvalId, message);
        Instant now = Instant.now();
        Instant last = lastFailureTimeline.get(approvalId);
        if (last == null || last.plusSeconds(properties.getProvisioningRetryDelaySeconds()).isBefore(now)) {
            repository.appendTimeline(approvalId, "provisioning-agent", "PROVISION_FAILED",
                    Texts.truncate("will retry: " + message, 1024));
            lastFailureTimeline.put(approvalId, now);
        }
    }
}
