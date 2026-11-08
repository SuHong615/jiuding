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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.AuditCsvExport;
import com.suhong.jiuding.tenant.api.AuditView;
import com.suhong.jiuding.tenant.api.OverviewView;
import com.suhong.jiuding.tenant.api.TenantCreateRequest;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.domain.Plan;
import com.suhong.jiuding.tenant.domain.RiskLevel;
import com.suhong.jiuding.tenant.domain.TenantSource;
import com.suhong.jiuding.tenant.domain.TenantStateMachine;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.repository.AuditRepository;
import com.suhong.jiuding.tenant.repository.AuditRow;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.TenantQuery;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Paging;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Tenant lifecycle, row level isolation, audit and internal service-to-service operations. */
@Service
public class TenantService {

    private static final Logger log = LoggerFactory.getLogger(TenantService.class);

    private final TenantRepository tenants;
    private final AuditRepository auditRepository;
    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final TenantAssembler assembler;
    private final ApprovalClient approvalClient;
    private final RiskReviewClient riskReviewClient;
    private final AuditService auditService;
    private final TenantProperties properties;

    public TenantService(TenantRepository tenants,
                         AuditRepository auditRepository,
                         OrgUnitRepository orgUnits,
                         AccountRepository accounts,
                         TenantAssembler assembler,
                         ApprovalClient approvalClient,
                         RiskReviewClient riskReviewClient,
                         AuditService auditService,
                         TenantProperties properties) {
        this.tenants = tenants;
        this.auditRepository = auditRepository;
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.assembler = assembler;
        this.approvalClient = approvalClient;
        this.riskReviewClient = riskReviewClient;
        this.auditService = auditService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ public API

    public TenantView create(TenantCreateRequest request) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requirePlatformWide(scope);
        String actor = SecurityScope.actor(scope);

        TenantRow row = assembler.build(request, TenantSource.MANUAL);
        if (tenants.findByCode(row.tenantCode()).isPresent()) {
            throw BizException.conflict("tenant code already exists: " + row.tenantCode());
        }
        try {
            tenants.insert(row);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("tenant code already exists: " + row.tenantCode());
        }
        auditService.record(row.id(), actor, "CREATED",
                "tenant " + row.tenantCode() + " created with source " + row.source());
        if (Boolean.TRUE.equals(request.autoSubmit())) {
            return submitRow(row, actor);
        }
        return view(row);
    }

    public PageResult<TenantView> list(String status, String plan, String keyword, boolean includeDeleted,
                                       int page, int size) {
        TenantScope scope = SecurityScope.require();
        requireNotMember(scope, "tenant list");
        if (includeDeleted && !SecurityScope.isPlatformWide(scope)) {
            throw BizException.forbidden("includeDeleted requires platform administrator role");
        }
        String scopeTenantId = SecurityScope.scopedTenantId(scope);
        Paging.validate(page, size, properties.getMaxPageSize());

        TenantQuery query = new TenantQuery(
                status == null || status.isBlank() ? null : TenantStatus.parse(status).name(),
                plan == null || plan.isBlank() ? null : Plan.parse(plan).name(),
                keyword == null || keyword.isBlank() ? null : keyword.trim(),
                includeDeleted);
        long total = tenants.count(scopeTenantId, query);
        List<TenantRow> rows = total == 0 ? List.of() : tenants.search(scopeTenantId, query, page, size);
        return PageResult.of(rows.stream().map(this::view).toList(), total, page, size);
    }

    public TenantView get(String id) {
        TenantScope scope = SecurityScope.require();
        requireNotMember(scope, "tenant detail");
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        return view(row);
    }

    /**
     * Soft delete (docs/11 section 6.1): platform administrator only, non ACTIVE tenants
     * only. The row stays in the database with {@code deleted_at} set and disappears from
     * default list/get results; every follow-up lifecycle action becomes a 404.
     */
    public TenantView delete(String id) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        TenantRow row = loadAny(id);
        if (row.deletedAt() != null) {
            throw BizException.conflict("tenant " + row.tenantCode() + " is already deleted");
        }
        if (TenantStatus.ACTIVE.name().equals(row.status())) {
            throw BizException.conflict("ACTIVE tenant " + row.tenantCode()
                    + " cannot be deleted; suspend or terminate it first");
        }
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deleted_at", now);
        fields.put("updated_at", now);
        if (tenants.updateFields(row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), SecurityScope.actor(scope), "TENANT_DELETED",
                "tenant " + row.tenantCode() + " soft deleted (status " + row.status() + ")");
        return view(loadAny(id));
    }

    public TenantView update(String id, TenantCreateRequest request) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireWrite(scope);
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        if (!TenantStatus.DRAFT.name().equals(row.status())
                && !TenantStatus.REJECTED.name().equals(row.status())) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "tenant can only be updated in DRAFT or REJECTED status, current=" + row.status());
        }
        Map<String, Object> fields = assembler.updateFields(row, request);
        fields.put("updated_at", TimeUtil.now());
        int changed = tenants.updateFields(row.id(), row.version(), fields);
        if (changed == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), SecurityScope.actor(scope), "UPDATED", "tenant profile updated");
        return view(load(id));
    }

    public TenantView submit(String id) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireWrite(scope);
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        return submitRow(row, SecurityScope.actor(scope));
    }

    public TenantView suspend(String id, String reason) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireWrite(scope);
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        TenantStatus current = TenantStatus.parse(row.status());
        if (current != TenantStatus.ACTIVE) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "only ACTIVE tenants can be suspended, current=" + current);
        }
        return changeStatus(row, TenantStatus.SUSPENDED, "SUSPENDED", reason, SecurityScope.actor(scope));
    }

    public TenantView activate(String id) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireWrite(scope);
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        TenantStatus current = TenantStatus.parse(row.status());
        if (current != TenantStatus.SUSPENDED) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "only SUSPENDED tenants can be activated, current=" + current);
        }
        return changeStatus(row, TenantStatus.ACTIVE, "ACTIVATED", null, SecurityScope.actor(scope));
    }

    public TenantView terminate(String id, String reason) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireWrite(scope);
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        TenantStatus current = TenantStatus.parse(row.status());
        if (current == TenantStatus.TERMINATED) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "tenant " + row.tenantCode() + " is already TERMINATED");
        }
        return changeStatus(row, TenantStatus.TERMINATED, "TERMINATED", reason, SecurityScope.actor(scope));
    }

    public List<AuditView> audits(String id) {
        TenantScope scope = SecurityScope.require();
        requireNotMember(scope, "tenant audit trail");
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);
        return auditRepository.findByTenant(id, 200).stream().map(AuditView::of).toList();
    }

    /**
     * Compliance audit CSV export (docs/14 section 3.2): platform administrators, tenant
     * administrators and auditors only, ISO-8601 range (default last 30 days), at most 10000
     * rows, RFC4180 style escaping.
     */
    public AuditCsvExport exportAudits(String id, String fromRaw, String toRaw) {
        TenantScope scope = SecurityScope.require();
        if (SecurityScope.isMember(scope) || SecurityScope.isOrgAdmin(scope)) {
            throw BizException.forbidden("platform administrator, tenant administrator or auditor role required");
        }
        boolean allowed = SecurityScope.isPlatformWide(scope)
                || SecurityScope.isGlobalAuditor(scope)
                || (scope != null && (scope.hasRole("TENANT_ADMIN") || scope.hasRole("AUDITOR")));
        if (!allowed) {
            throw BizException.forbidden("platform administrator, tenant administrator or auditor role required");
        }
        TenantRow row = load(id);
        SecurityScope.assertVisible(scope, row);

        LocalDateTime to = parseIsoOrDefault(toRaw, TimeUtil.now());
        LocalDateTime from = parseIsoOrDefault(fromRaw, to.minusDays(30));
        if (from.isAfter(to)) {
            throw BizException.invalid("from must be before to");
        }
        List<AuditRow> rows = auditRepository.findRange(row.id(), from, to, MAX_AUDIT_EXPORT_ROWS);
        String csv = toAuditCsv(rows);
        return new AuditCsvExport(csv.getBytes(StandardCharsets.UTF_8),
                "tenant-audits-" + row.id() + ".csv");
    }

    public OverviewView overview() {
        TenantScope scope = SecurityScope.require();
        requireNotMember(scope, "tenant overview");
        String scopeTenantId = SecurityScope.scopedTenantId(scope);

        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (TenantStatus status : TenantStatus.values()) {
            byStatus.put(status.name(), 0L);
        }
        byStatus.putAll(tenants.countBy("status", scopeTenantId));

        Map<String, Long> byPlan = new LinkedHashMap<>();
        for (Plan plan : Plan.values()) {
            byPlan.put(plan.name(), 0L);
        }
        byPlan.putAll(tenants.countBy("plan", scopeTenantId));

        Map<String, Long> byRisk = new LinkedHashMap<>();
        for (RiskLevel level : RiskLevel.values()) {
            byRisk.put(level.name(), 0L);
        }
        byRisk.put("UNKNOWN", 0L);
        byRisk.putAll(tenants.countBy("risk_level", scopeTenantId));

        long total = tenants.count(scopeTenantId, TenantQuery.empty());
        long active = byStatus.getOrDefault(TenantStatus.ACTIVE.name(), 0L);
        long pending = byStatus.getOrDefault(TenantStatus.PENDING_APPROVAL.name(), 0L);
        return new OverviewView(total, active, pending, byStatus, byPlan, byRisk);
    }

    // ------------------------------------------------------------------ internal API

    public TenantView getInternal(String id) {
        return view(load(id));
    }

    /**
     * Status callback used by approval-service. Idempotent: re-applying the current
     * status returns the tenant unchanged so the APPROVED -&gt; PROVISIONING -&gt; ACTIVE
     * chain can be retried without 5xx.
     */
    public TenantView updateStatusInternal(String id, String status, String reason) {
        TenantRow row = load(id);
        TenantStatus target = TenantStatus.parse(status);
        TenantStatus current = TenantStatus.parse(row.status());
        if (current == target) {
            return view(row);
        }
        TenantStateMachine.assertTransition(current, target);

        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", target.name());
        fields.put("updated_at", now);
        switch (target) {
            case SUBMITTED -> fields.put("submitted_at", now);
            case APPROVED, REJECTED -> fields.put("decided_at", now);
            case ACTIVE -> {
                if (row.activatedAt() == null) {
                    fields.put("activated_at", now);
                }
            }
            default -> {
                // no extra timestamp for RISK_REVIEW / PENDING_APPROVAL / PROVISIONING
            }
        }
        if (target == TenantStatus.REJECTED) {
            fields.put("reject_reason", Texts.truncate(reason, 512));
        }
        int changed = tenants.updateFields(row.id(), row.version(), fields);
        if (changed == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), "approval-service", "STATUS_CHANGED", describe(current, target, reason));
        return view(load(id));
    }

    public TenantView updateRiskInternal(String id, String riskLevel, Integer riskScore) {
        TenantRow row = load(id);
        RiskLevel level = RiskLevel.parse(riskLevel);
        Integer score = riskScore == null ? row.riskScore() : riskScore;
        if (score != null && (score < 0 || score > 100)) {
            throw BizException.invalid("riskScore must be between 0 and 100");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("risk_level", level.name());
        fields.put("risk_score", score);
        fields.put("updated_at", TimeUtil.now());
        int changed = tenants.updateFields(row.id(), row.version(), fields);
        if (changed == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(id, "approval-service", "RISK_UPDATED",
                "risk " + row.riskLevel() + "/" + row.riskScore() + " -> " + level.name() + "/" + score);
        return view(load(id));
    }

    /** Used by the collection job (already platform-scope checked) after importing tenants. */
    public TenantView submitForCollection(String tenantId, String actor) {
        return submitRow(load(tenantId), actor);
    }

    // ------------------------------------------------------------------ internals

    private TenantView submitRow(TenantRow row, String actor) {
        TenantStatus current = TenantStatus.parse(row.status());
        if (!TenantStateMachine.canSubmit(current)) {
            throw new BizException(ErrorCode.APPROVAL_INVALID_STATE,
                    "tenant " + row.tenantCode() + " cannot be submitted from status " + current);
        }
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", TenantStatus.SUBMITTED.name());
        fields.put("submitted_at", now);
        fields.put("reject_reason", null);
        fields.put("updated_at", now);
        if (tenants.updateFields(row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), actor, "SUBMITTED",
                "tenant submitted for approval (" + current + " -> SUBMITTED)");

        TenantRow submitted = load(row.id());
        // Skill-driven risk review (deterministic rule engine in agent-service):
        // riskLevel/riskScore are persisted on the tenant before the approval is created
        // so the approval policy and the dashboard both see the assessed risk.
        TenantRow assessed = applyRiskAssessment(submitted, actor);
        boolean accepted = approvalClient.createApproval(assessed, actor, assessed.riskLevel() == null
                ? List.of()
                : List.of("riskLevel=" + assessed.riskLevel() + ", riskScore=" + assessed.riskScore()));
        if (!accepted) {
            auditService.record(row.id(), actor, "APPROVAL_DEGRADED",
                    "approval-service unavailable; tenant kept in SUBMITTED (approval not created) for manual intervention");
            log.warn("tenant {} degraded to SUBMITTED because approval-service is unavailable",
                    submitted.tenantCode());
            return view(submitted);
        }

        Map<String, Object> riskFields = new LinkedHashMap<>();
        riskFields.put("status", TenantStatus.RISK_REVIEW.name());
        riskFields.put("updated_at", TimeUtil.now());
        if (tenants.updateFields(assessed.id(), assessed.version(), riskFields) == 0) {
            log.warn("tenant {} advanced concurrently while entering RISK_REVIEW; returning current state",
                    submitted.tenantCode());
        } else {
            auditService.record(row.id(), actor, "APPROVAL_REQUESTED",
                    "approval created; tenant status -> RISK_REVIEW");
        }
        return view(load(row.id()));
    }

    /**
     * Executes the {@code risk-review} skill and persists the assessment. A missing
     * agent tier is non-fatal: the tenant keeps its current (default LOW) risk.
     */
    private TenantRow applyRiskAssessment(TenantRow row, String actor) {
        RiskReviewClient.RiskAssessment assessment = riskReviewClient.review(row);
        if (assessment == null) {
            return row;
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("risk_level", assessment.riskLevel());
        fields.put("risk_score", assessment.riskScore());
        fields.put("updated_at", TimeUtil.now());
        if (tenants.updateFields(row.id(), row.version(), fields) == 0) {
            log.warn("tenant {} risk assessment lost a concurrent update; re-reading current row", row.tenantCode());
            return load(row.id());
        }
        auditService.record(row.id(), "agent-service", "RISK_ASSESSED",
                "risk-review: " + assessment.riskLevel() + "/" + assessment.riskScore()
                        + " decision=" + assessment.decision()
                        + (assessment.reasons().isEmpty() ? "" : " findings=" + String.join("; ", assessment.reasons())));
        return load(row.id());
    }

    private TenantView changeStatus(TenantRow row, TenantStatus target, String action, String reason, String actor) {
        TenantStatus current = TenantStatus.parse(row.status());
        TenantStateMachine.assertTransition(current, target);
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", target.name());
        fields.put("updated_at", now);
        if (target == TenantStatus.ACTIVE && row.activatedAt() == null) {
            fields.put("activated_at", now);
        }
        int changed = tenants.updateFields(row.id(), row.version(), fields);
        if (changed == 0) {
            throw BizException.conflict("tenant " + row.tenantCode() + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), actor, action, describe(current, target, reason));
        return view(load(row.id()));
    }

    private String describe(TenantStatus current, TenantStatus target, String reason) {
        String detail = "status " + current + " -> " + target;
        if (reason != null && !reason.isBlank()) {
            detail += ": " + reason.trim();
        }
        return detail;
    }

    private static final int MAX_AUDIT_EXPORT_ROWS = 10_000;

    private static String csvCell(String value) {
        if (value == null) {
            return "";
        }
        boolean needsQuotes = value.indexOf(',') >= 0 || value.indexOf('"') >= 0
                || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0;
        if (!needsQuotes) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static String toAuditCsv(List<AuditRow> rows) {
        StringBuilder csv = new StringBuilder("at,actor,action,detail\n");
        for (AuditRow row : rows) {
            csv.append(csvCell(TimeUtil.iso(row.at()))).append(',')
                    .append(csvCell(row.actor())).append(',')
                    .append(csvCell(row.action())).append(',')
                    .append(csvCell(row.detail())).append('\n');
        }
        return csv.toString();
    }

    /** Parses ISO-8601 (instant/offset/local) into the UTC wall-clock DB representation. */
    private static LocalDateTime parseIsoOrDefault(String raw, LocalDateTime fallback) {
        String value = Texts.blankToNull(raw);
        if (value == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(value).toInstant().atZone(ZoneOffset.UTC).toLocalDateTime();
        } catch (DateTimeParseException ignored) {
            // not an offset timestamp, retry as local date time
        }
        try {
            return LocalDateTime.parse(value);
        } catch (DateTimeParseException ex) {
            throw BizException.invalid("invalid ISO-8601 timestamp: " + raw);
        }
    }

    private TenantRow load(String id) {
        TenantRow row = loadAny(id);
        if (row.deletedAt() != null) {
            throw BizException.notFound("tenant not found: " + id);
        }
        return row;
    }

    private TenantRow loadAny(String id) {
        return tenants.findById(id).orElseThrow(() -> BizException.notFound("tenant not found: " + id));
    }

    /** Tenant projection enriched with the docs/11 identity counts. */
    private TenantView view(TenantRow row) {
        return TenantView.of(row,
                (int) orgUnits.countByTenant(row.id()),
                (int) accounts.countByTenant(row.id()));
    }

    /** docs/13 section 3: MEMBER has no access to tenant-level resources. */
    private void requireNotMember(TenantScope scope, String resource) {
        if (SecurityScope.isMember(scope)) {
            throw BizException.forbidden("MEMBER role cannot access " + resource);
        }
    }
}
