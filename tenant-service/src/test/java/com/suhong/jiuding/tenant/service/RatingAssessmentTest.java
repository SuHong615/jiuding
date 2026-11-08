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
import com.suhong.jiuding.tenant.api.RatingAssessRequest;
import com.suhong.jiuding.tenant.api.RatingOverrideRequest;
import com.suhong.jiuding.tenant.api.RatingView;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

@SpringBootTest
@ActiveProfiles("test")
class RatingAssessmentTest {

    @Autowired
    private RatingService ratingService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private JdbcTemplate jdbc;

    private String tenantId;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        TenantFixtures.clean(jdbc);
        tenantId = TenantFixtures.createTenant(tenantService, "t-acme", "Acme").id();
    }

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void assessmentIsDeterministicAndUpdatesCurrentRating() {
        BizException missing = catchThrowableOfType(
                () -> ratingService.current(tenantId), BizException.class);
        assertThat(missing).isNotNull();
        assertThat(missing.errorCode()).isEqualTo(ErrorCode.NOT_FOUND);

        RatingView first = ratingService.assess(tenantId, new RatingAssessRequest("initial"));
        assertThat(first.source()).isEqualTo("SKILL");
        assertThat(first.score()).isEqualTo(80);
        assertThat(first.grade()).isEqualTo("AA");
        assertThat(first.assessedBy()).isNotBlank();
        assertThat(first.dims().business()).isEqualTo(82);
        assertThat(first.dims().compliance()).isEqualTo(90);
        assertThat(first.dims().financial()).isEqualTo(70);
        assertThat(first.dims().security()).isEqualTo(75);
        assertThat(first.dims().service()).isEqualTo(75);
        int weightedSum = first.dims().business() * 30 + first.dims().compliance() * 25
                + first.dims().financial() * 20 + first.dims().security() * 15
                + first.dims().service() * 10;
        assertThat(first.score()).isEqualTo((int) Math.round(weightedSum / 100.0));

        RatingView second = ratingService.assess(tenantId, new RatingAssessRequest(null));
        assertThat(second.score()).isEqualTo(first.score());
        assertThat(second.grade()).isEqualTo(first.grade());

        RatingView current = ratingService.current(tenantId);
        assertThat(current.grade()).isEqualTo("AA");
        assertThat(current.score()).isEqualTo(80);

        PageResult<RatingView> history = ratingService.history(tenantId, 0, 20);
        assertThat(history.total()).isEqualTo(2);
        assertThat(history.items()).hasSize(2);

        TenantView tenant = tenantService.get(tenantId);
        assertThat(tenant.ratingGrade()).isEqualTo("AA");
        assertThat(tenant.ratingScore()).isEqualTo(80);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = 'RATING_ASSESSED'", Long.class, tenantId)).isEqualTo(2);
    }

    @Test
    void manualOverrideIsPlatformOnlyAndRequiresReason() {
        ratingService.assess(tenantId, new RatingAssessRequest(null));

        TestSecurity.tenantAdmin("t-acme");
        BizException forbidden = catchThrowableOfType(
                () -> ratingService.override(tenantId, new RatingOverrideRequest("A", 72, "recalibrated")),
                BizException.class);
        assertThat(forbidden).isNotNull();
        assertThat(forbidden.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);

        TestSecurity.platformAdmin();
        BizException noReason = catchThrowableOfType(
                () -> ratingService.override(tenantId, new RatingOverrideRequest("A", 72, "   ")),
                BizException.class);
        assertThat(noReason).isNotNull();
        assertThat(noReason.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);

        BizException badGrade = catchThrowableOfType(
                () -> ratingService.override(tenantId, new RatingOverrideRequest("AAAA", 72, "x")),
                BizException.class);
        assertThat(badGrade).isNotNull();
        assertThat(badGrade.errorCode()).isEqualTo(ErrorCode.INVALID_ARGUMENT);

        RatingView overridden = ratingService.override(tenantId,
                new RatingOverrideRequest("A", 72, "manual recalibration after audit"));
        assertThat(overridden.source()).isEqualTo("MANUAL");
        assertThat(overridden.grade()).isEqualTo("A");
        assertThat(overridden.score()).isEqualTo(72);
        assertThat(overridden.note()).isEqualTo("manual recalibration after audit");

        RatingView current = ratingService.current(tenantId);
        assertThat(current.grade()).isEqualTo("A");
        assertThat(ratingService.history(tenantId, 0, 20).total()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = 'RATING_OVERRIDDEN'", Long.class, tenantId)).isEqualTo(1);
    }

    @Test
    void tierAndRiskInputsChangeTheScoreAsDocumented() {
        RatingView pro = ratingService.assess(tenantId, new RatingAssessRequest(null));

        String enterpriseId = TenantFixtures.createTenant(tenantService, "t-ent", "Enterprise").id();
        jdbc.update("UPDATE tenants SET plan = 'ENTERPRISE' WHERE id = ?", enterpriseId);
        RatingView enterprise = ratingService.assess(enterpriseId, null);
        assertThat(enterprise.score()).isGreaterThan(pro.score());
        assertThat(enterprise.dims().financial()).isGreaterThan(pro.dims().financial());

        jdbc.update("UPDATE tenants SET risk_level = 'HIGH', risk_score = 85 WHERE id = ?", tenantId);
        RatingView risky = ratingService.assess(tenantId, new RatingAssessRequest(null));
        assertThat(risky.dims().compliance()).isLessThan(pro.dims().compliance());
        assertThat(risky.score()).isLessThan(pro.score());
    }
}
