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
package com.suhong.jiuding.tenant.onboarding;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.tenant.domain.OrgUnitStatus;
import com.suhong.jiuding.tenant.domain.Plan;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.ApprovalClient;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.service.RiskReviewClient;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Public self-service onboarding flow (docs/15 section 6.2).
 *
 * <ol>
 *   <li>create the onboarding application + DRAFT tenant (source SELF_SERVICE) + root org unit;</li>
 *   <li>run the risk-review skill (local conservative fallback on failure);</li>
 *   <li>compliant (LOW and not ENTERPRISE) → auto submit and activate (ACTIVATED); otherwise
 *       queue for manual approval (PENDING_MANUAL).</li>
 * </ol>
 * Public responses never expose internal identifiers.
 */
@Service
public class PublicOnboardingService {

    private static final Logger log = LoggerFactory.getLogger(PublicOnboardingService.class);

    private static final String ACTOR = "self-service";
    private static final char[] APPLICATION_NO_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;
    private final OnboardingApplicationRepository applications;
    private final RiskReviewClient riskReviewClient;
    private final ApprovalClient approvalClient;
    private final AuditService auditService;
    private final OnboardingProperties properties;
    private final OnboardingRateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public PublicOnboardingService(TenantRepository tenants,
                                   OrgUnitRepository orgUnits,
                                   OnboardingApplicationRepository applications,
                                   RiskReviewClient riskReviewClient,
                                   ApprovalClient approvalClient,
                                   AuditService auditService,
                                   OnboardingProperties properties,
                                   OnboardingRateLimiter rateLimiter,
                                   ObjectMapper objectMapper) {
        this.tenants = tenants;
        this.orgUnits = orgUnits;
        this.applications = applications;
        this.riskReviewClient = riskReviewClient;
        this.approvalClient = approvalClient;
        this.auditService = auditService;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    /** Creates the application, tenant and root organization, then runs the agent review. */
    @Transactional
    public OnboardingPublicView apply(OnboardingApplyRequest request, String clientIp) {
        if (!rateLimiter.tryAcquire(clientIp)) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED,
                    "too many onboarding requests, please retry later");
        }
        Plan plan = resolvePlan(request.plan());
        String region = Texts.defaultIfBlank(request.region(), properties.getDefaultRegion());
        String tenantCode = resolveTenantCode(request.tenantCode());

        TenantRow tenant = createTenant(request, tenantCode, plan, region);
        createRootOrgUnit(tenant);
        OnboardingApplicationRow application = createApplication(request, tenant, plan, region);

        application = updateApplication(application, OnboardingStatus.AGENT_REVIEW, null);

        RiskReviewClient.RiskAssessment assessment = riskReviewClient.review(tenant);
        boolean degraded = assessment == null;
        if (degraded) {
            // Conservative local fallback: never auto-activate when the agent tier is missing.
            assessment = new RiskReviewClient.RiskAssessment("MEDIUM", 50, "NEEDS_REVIEW",
                    List.of("risk-review skill unavailable; conservative manual review"));
        }
        TenantRow assessed = applyRisk(tenant, assessment);

        boolean compliant = !degraded
                && "LOW".equalsIgnoreCase(assessment.riskLevel())
                && plan != Plan.ENTERPRISE;
        if (compliant) {
            // Best effort: register the approval record, then activate immediately (docs/15 6.2).
            approvalClient.createApproval(assessed, ACTOR);
            activateTenant(assessed);
            OnboardingApplicationRow activated =
                    updateApplication(application, OnboardingStatus.ACTIVATED, assessment);
            auditService.record(tenant.id(), ACTOR, "ONBOARDING_ACTIVATED",
                    "self-service onboarding auto approved, applicationNo="
                            + application.applicationNo());
            return view(activated, OnboardingStatus.ACTIVATED);
        }

        boolean approvalAccepted = approvalClient.createApproval(assessed, ACTOR);
        submitForManualReview(assessed, approvalAccepted);
        OnboardingApplicationRow pending =
                updateApplication(application, OnboardingStatus.PENDING_MANUAL, assessment);
        auditService.record(tenant.id(), ACTOR, "ONBOARDING_MANUAL_REVIEW",
                "self-service onboarding queued for manual review, applicationNo="
                        + application.applicationNo() + (degraded ? " (agent degraded)" : ""));
        log.info("onboarding application {} queued for manual review (risk={}/{}, approvalAccepted={})",
                application.applicationNo(), assessment.riskLevel(), assessment.riskScore(),
                approvalAccepted);
        return view(pending, OnboardingStatus.PENDING_MANUAL);
    }

    /** Public status lookup; merges the live tenant status (docs/15 6.2 step 4). */
    public OnboardingPublicView status(String applicationNo) {
        OnboardingApplicationRow application = applications.findByApplicationNo(applicationNo)
                .orElseThrow(() -> BizException.notFound("onboarding application not found"));
        OnboardingStatus status = mergedStatus(application);
        return view(application, status);
    }

    // ------------------------------------------------------------------ persistence

    private TenantRow createTenant(OnboardingApplyRequest request, String tenantCode, Plan plan,
                                   String region) {
        var now = TimeUtil.now();
        TenantRow row = new TenantRow(
                UUID.randomUUID().toString(),
                tenantCode,
                request.companyName().trim(),
                Texts.blankToNull(request.contactName()),
                Texts.blankToNull(request.contactEmail()),
                Texts.blankToNull(request.contactPhone()),
                plan.name(),
                region,
                "SHARED_SCHEMA",
                TenantStatus.DRAFT.name(),
                null,
                null,
                null,
                null,
                null,
                "SELF_SERVICE",
                attributesJson(request.industry(), request.notes()),
                null,
                now,
                now,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                0L);
        try {
            tenants.insert(row);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("tenantCode is not available");
        }
        auditService.record(row.id(), ACTOR, "ONBOARDING_TENANT_CREATED",
                "self-service tenant " + tenantCode + " created with plan " + plan);
        return row;
    }

    private void createRootOrgUnit(TenantRow tenant) {
        var now = TimeUtil.now();
        String id = UUID.randomUUID().toString();
        OrgUnitRow root = new OrgUnitRow(
                id,
                tenant.id(),
                null,
                "ROOT",
                tenant.name(),
                "COMPANY",
                "/" + id,
                0,
                0,
                null,
                OrgUnitStatus.ACTIVE.name(),
                now,
                now,
                0L);
        orgUnits.insert(root);
    }

    private OnboardingApplicationRow createApplication(OnboardingApplyRequest request, TenantRow tenant,
                                                       Plan plan, String region) {
        var now = TimeUtil.now();
        OnboardingApplicationRow row = new OnboardingApplicationRow(
                UUID.randomUUID().toString(),
                nextApplicationNo(),
                request.companyName().trim(),
                tenant.tenantCode(),
                Texts.blankToNull(request.contactName()),
                Texts.blankToNull(request.contactEmail()),
                Texts.blankToNull(request.contactPhone()),
                plan.name(),
                region,
                Texts.truncate(Texts.blankToNull(request.industry()), 64),
                Texts.truncate(Texts.blankToNull(request.notes()), 512),
                OnboardingStatus.SUBMITTED.name(),
                tenant.id(),
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now);
        applications.insert(row);
        auditService.record(tenant.id(), ACTOR, "ONBOARDING_SUBMITTED",
                "onboarding application " + row.applicationNo() + " submitted");
        return row;
    }

    private OnboardingApplicationRow updateApplication(OnboardingApplicationRow row,
                                                       OnboardingStatus status,
                                                       RiskReviewClient.RiskAssessment assessment) {
        // Keep the persisted agent fields when no assessment was produced yet (first
        // AGENT_REVIEW transition). Explicit locals avoid unboxing a null Integer column.
        String agentDecision = row.agentDecision();
        String agentRiskLevel = row.agentRiskLevel();
        Integer agentScore = row.agentScore();
        String agentReasons = row.agentReasons();
        if (assessment != null) {
            agentDecision = assessment.decision();
            agentRiskLevel = assessment.riskLevel();
            agentScore = assessment.riskScore();
            agentReasons = reasonsJson(assessment.reasons());
        }
        OnboardingApplicationRow updated = new OnboardingApplicationRow(
                row.id(),
                row.applicationNo(),
                row.companyName(),
                row.tenantCode(),
                row.contactName(),
                row.contactEmail(),
                row.contactPhone(),
                row.plan(),
                row.region(),
                row.industry(),
                row.notes(),
                status.name(),
                row.tenantId(),
                agentDecision,
                agentRiskLevel,
                agentScore,
                agentReasons,
                row.reviewedBy(),
                row.reviewedAt(),
                row.createdAt(),
                TimeUtil.now());
        applications.update(updated);
        return updated;
    }

    private TenantRow applyRisk(TenantRow tenant, RiskReviewClient.RiskAssessment assessment) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("risk_level", assessment.riskLevel());
        fields.put("risk_score", assessment.riskScore());
        fields.put("updated_at", TimeUtil.now());
        if (tenants.updateFields(tenant.id(), tenant.version(), fields) == 0) {
            throw BizException.conflict("onboarding tenant was modified concurrently, please retry");
        }
        return tenants.findById(tenant.id())
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenant.id()));
    }

    private void activateTenant(TenantRow tenant) {
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", TenantStatus.ACTIVE.name());
        fields.put("submitted_at", now);
        fields.put("decided_at", now);
        fields.put("activated_at", now);
        fields.put("updated_at", now);
        if (tenants.updateFields(tenant.id(), tenant.version(), fields) == 0) {
            throw BizException.conflict("onboarding tenant was modified concurrently, please retry");
        }
    }

    private void submitForManualReview(TenantRow tenant, boolean approvalAccepted) {
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", approvalAccepted
                ? TenantStatus.RISK_REVIEW.name() : TenantStatus.SUBMITTED.name());
        fields.put("submitted_at", now);
        fields.put("updated_at", now);
        if (tenants.updateFields(tenant.id(), tenant.version(), fields) == 0) {
            throw BizException.conflict("onboarding tenant was modified concurrently, please retry");
        }
    }

    // ------------------------------------------------------------------ helpers

    private OnboardingStatus mergedStatus(OnboardingApplicationRow application) {
        OnboardingStatus fallback = OnboardingStatus.parse(application.status());
        if (application.tenantId() == null) {
            return fallback;
        }
        return tenants.findById(application.tenantId())
                .map(tenant -> {
                    if (tenant.deletedAt() != null) {
                        return OnboardingStatus.REJECTED;
                    }
                    return switch (tenant.status()) {
                        case "ACTIVE" -> OnboardingStatus.ACTIVATED;
                        case "REJECTED", "TERMINATED" -> OnboardingStatus.REJECTED;
                        default -> fallback;
                    };
                })
                .orElse(fallback);
    }

    private OnboardingPublicView view(OnboardingApplicationRow application, OnboardingStatus status) {
        return new OnboardingPublicView(application.applicationNo(), application.tenantCode(),
                status.name(), messageFor(status));
    }

    private String messageFor(OnboardingStatus status) {
        return switch (status) {
            case ACTIVATED -> "入驻申请已自动审核通过，租户已激活";
            case PENDING_MANUAL -> "入驻申请已提交，等待人工审核";
            case REJECTED -> "入驻申请未通过审核";
            case SUBMITTED, AGENT_REVIEW -> "入驻申请审核中";
        };
    }

    private Plan resolvePlan(String raw) {
        return Plan.parseOrDefault(Texts.defaultIfBlank(raw, properties.getDefaultPlan()));
    }

    private String resolveTenantCode(String requested) {
        String value = Texts.blankToNull(requested);
        if (value == null) {
            for (int attempt = 0; attempt < 10; attempt++) {
                String candidate = "t-" + UUID.randomUUID().toString().substring(0, 8);
                if (tenants.findByCode(candidate).isEmpty()) {
                    return candidate;
                }
            }
            throw new BizException(ErrorCode.INTERNAL, "cannot allocate a tenant code, please retry");
        }
        if (!value.matches("^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$")) {
            throw BizException.invalid(
                    "tenantCode must start with a letter/digit and use letters, digits, '-' or '_'");
        }
        if (tenants.findByCode(value).isPresent()) {
            throw BizException.conflict("tenantCode is not available");
        }
        return value;
    }

    private String nextApplicationNo() {
        for (int attempt = 0; attempt < 10; attempt++) {
            StringBuilder builder = new StringBuilder("ONB");
            for (int index = 0; index < 10; index++) {
                builder.append(APPLICATION_NO_ALPHABET[RANDOM.nextInt(APPLICATION_NO_ALPHABET.length)]);
            }
            String candidate = builder.toString();
            if (applications.findByApplicationNo(candidate).isEmpty()) {
                return candidate;
            }
        }
        throw new BizException(ErrorCode.INTERNAL, "cannot allocate an application number, please retry");
    }

    private String attributesJson(String industry, String notes) {
        Map<String, Object> attributes = new LinkedHashMap<>();
        attributes.put("onboarding", "self-service");
        if (industry != null && !industry.isBlank()) {
            attributes.put("industry", industry.trim());
        }
        if (notes != null && !notes.isBlank()) {
            attributes.put("notes", notes.trim());
        }
        try {
            return objectMapper.writeValueAsString(attributes);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }

    private String reasonsJson(List<String> reasons) {
        try {
            return objectMapper.writeValueAsString(reasons == null ? List.of() : reasons);
        } catch (JsonProcessingException ex) {
            return null;
        }
    }
}
