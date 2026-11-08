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
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.RatingAssessRequest;
import com.suhong.jiuding.tenant.api.RatingOverrideRequest;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.domain.RatingCalculator;
import com.suhong.jiuding.tenant.domain.RatingGrade;
import com.suhong.jiuding.tenant.domain.RatingSource;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.RatingRepository;
import com.suhong.jiuding.tenant.repository.RatingRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Paging;
import com.suhong.jiuding.tenant.support.TenantAccess;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant rating engine (docs/11 section 4 and 6.4).
 *
 * <p>Assessment is deterministic: the five dimensions are computed locally from persisted
 * tenant attributes, then the optional {@code tenant-rating} skill adjustment is applied
 * (bounded, degrade to base only). Every assessment appends an immutable history row and
 * refreshes the denormalised current grade/score on {@code tenants}. Manual overrides are
 * platform-admin only and always require a reason.
 */
@Service
public class RatingService {

    private final RatingRepository ratings;
    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final TenantAccess access;
    private final RatingSkillClient skillClient;
    private final AuditService auditService;
    private final TenantProperties properties;

    public RatingService(RatingRepository ratings,
                         TenantRepository tenants,
                         OrgUnitRepository orgUnits,
                         AccountRepository accounts,
                         TenantAccess access,
                         RatingSkillClient skillClient,
                         AuditService auditService,
                         TenantProperties properties) {
        this.ratings = ratings;
        this.tenants = tenants;
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.access = access;
        this.skillClient = skillClient;
        this.auditService = auditService;
        this.properties = properties;
    }

    // ------------------------------------------------------------------ queries

    public PageResult<RatingView> history(String tenantId, int page, int size) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireRatingRead(scope);
        Paging.validate(page, size, properties.getMaxPageSize());
        long total = ratings.count(tenant.id());
        List<RatingRow> rows = total == 0
                ? List.of()
                : ratings.historyFrom(tenant.id(), (long) page * size, size + 1);
        List<RatingView> items = new java.util.ArrayList<>(Math.min(size, rows.size()));
        for (int index = 0; index < size && index < rows.size(); index++) {
            RatingRow previous = index + 1 < rows.size() ? rows.get(index + 1) : null;
            items.add(RatingView.of(rows.get(index), previous));
        }
        return PageResult.of(items, total, page, size);
    }

    public RatingView current(String tenantId) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireRatingRead(scope);
        return currentOf(tenant);
    }

    // ------------------------------------------------------------------ commands

    /**
     * Appends the immutable history row and refreshes the denormalised current rating on
     * {@code tenants} in one transaction. A concurrent version conflict therefore rolls the
     * history insert back so history and current grade never diverge.
     */
    @Transactional
    public RatingView assess(String tenantId, RatingAssessRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireAssess(scope);
        String note = Texts.blankToNull(request == null ? null : request.note());
        return doAssess(tenant, access.actor(scope), note, "RATING_ASSESSED");
    }

    /** Scheduled refresh entry point (docs/13 section 4): actor {@code system}, no caller scope. */
    @Transactional
    public RatingView assessAsSystem(String tenantId) {
        TenantRow tenant = tenants.findById(tenantId)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenantId));
        if (tenant.deletedAt() != null) {
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        return doAssess(tenant, "system", "scheduled rating refresh", "RATING_REFRESHED");
    }

    private RatingView doAssess(TenantRow tenant, String actor, String note, String auditAction) {
        int orgUnitCount = (int) orgUnits.countByTenant(tenant.id());
        int accountCount = (int) accounts.countByTenant(tenant.id());
        RatingCalculator.Result base = RatingCalculator.calculate(tenant, orgUnitCount, accountCount);
        int adjustment = skillClient.adjustment(tenant, base.dims());
        RatingCalculator.Result result = adjustment == 0
                ? base
                : RatingCalculator.adjust(base.dims(), adjustment);

        // docs/14 section 3.3: the delta is relative to the grade that was current before
        // this assessment (the denormalised tenants columns).
        String previousGrade = tenant.ratingGrade();
        Integer previousScore = tenant.ratingScore();

        RatingRow row = new RatingRow(
                UUID.randomUUID().toString(),
                tenant.id(),
                result.grade().name(),
                result.score(),
                result.dims().business(),
                result.dims().compliance(),
                result.dims().financial(),
                result.dims().security(),
                result.dims().service(),
                RatingSource.SKILL.name(),
                actor,
                note,
                TimeUtil.now());
        ratings.insert(row);
        updateCurrent(tenant, result.grade(), result.score());
        auditService.record(tenant.id(), actor, auditAction,
                "rating assessed: " + result.grade() + "/" + result.score()
                        + " (business=" + result.dims().business()
                        + ", compliance=" + result.dims().compliance()
                        + ", financial=" + result.dims().financial()
                        + ", security=" + result.dims().security()
                        + ", service=" + result.dims().service()
                        + (adjustment == 0 ? "" : ", skillAdjustment=" + adjustment) + ")");
        if (previousGrade != null && !previousGrade.equals(result.grade().name())) {
            auditService.record(tenant.id(), actor, "RATING_GRADE_CHANGED",
                    "rating grade changed " + previousGrade + " -> " + result.grade().name());
        }
        return RatingView.of(row, previousGrade, previousScore);
    }

    public RatingView override(String tenantId, RatingOverrideRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requirePlatformAdmin(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        RatingGrade grade = RatingGrade.parse(request.grade());
        int score = request.score();
        if (score < 0 || score > 100) {
            throw BizException.invalid("score must be between 0 and 100");
        }
        String reason = Texts.blankToNull(request.reason());
        if (reason == null) {
            throw BizException.invalid("reason is required for a manual rating override");
        }
        RatingRow latest = ratings.latest(tenant.id()).orElse(null);
        int business = latest == null ? 0 : latest.dimBusiness();
        int compliance = latest == null ? 0 : latest.dimCompliance();
        int financial = latest == null ? 0 : latest.dimFinancial();
        int security = latest == null ? 0 : latest.dimSecurity();
        int service = latest == null ? 0 : latest.dimService();
        String actor = access.actor(scope);
        String previousGrade = tenant.ratingGrade();
        Integer previousScore = tenant.ratingScore();
        RatingRow row = new RatingRow(
                UUID.randomUUID().toString(),
                tenant.id(),
                grade.name(),
                score,
                business,
                compliance,
                financial,
                security,
                service,
                RatingSource.MANUAL.name(),
                actor,
                Texts.truncate(reason, 512),
                TimeUtil.now());
        ratings.insert(row);
        updateCurrent(tenant, grade, score);
        auditService.record(tenant.id(), actor, "RATING_OVERRIDDEN",
                "manual rating override: " + grade + "/" + score + ", reason=" + reason);
        return RatingView.of(row, previousGrade, previousScore);
    }

    /** Internal (service-to-service) current rating for approval / agent flows. */
    public RatingView currentInternal(String tenantId) {
        TenantRow tenant = tenants.findById(tenantId)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenantId));
        if (tenant.deletedAt() != null) {
            // Keep the internal endpoints consistent with GET /internal/tenants/{id}.
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        return currentOf(tenant);
    }

    // ------------------------------------------------------------------ helpers

    private RatingView currentOf(TenantRow tenant) {
        if (tenant.ratingGrade() == null) {
            throw BizException.notFound("no rating has been assessed for tenant: " + tenant.id());
        }
        List<RatingRow> latestRows = ratings.historyFrom(tenant.id(), 0, 2);
        if (latestRows.isEmpty()) {
            return new RatingView(
                    null,
                    tenant.id(),
                    tenant.ratingGrade(),
                    tenant.ratingScore() == null ? 0 : tenant.ratingScore(),
                    null,
                    null,
                    0,
                    false,
                    new RatingView.Dims(0, 0, 0, 0, 0),
                    RatingSource.SKILL.name(),
                    "system",
                    null,
                    TimeUtil.iso(tenant.ratingAssessedAt()));
        }
        RatingRow latest = latestRows.get(0);
        RatingRow previous = latestRows.size() > 1 ? latestRows.get(1) : null;
        return RatingView.of(latest, previous);
    }

    private void updateCurrent(TenantRow tenant, RatingGrade grade, int score) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("rating_grade", grade.name());
        fields.put("rating_score", score);
        fields.put("rating_assessed_at", TimeUtil.now());
        fields.put("updated_at", TimeUtil.now());
        if (tenants.updateFields(tenant.id(), tenant.version(), fields) == 0) {
            throw BizException.conflict("tenant " + tenant.tenantCode()
                    + " was modified concurrently, please retry the rating");
        }
    }

    private TenantRow scopedTenant(TenantScope scope, String tenantId) {
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        return tenant;
    }
}
