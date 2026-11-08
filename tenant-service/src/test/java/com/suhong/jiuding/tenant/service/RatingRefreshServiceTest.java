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

import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * docs/13 section 4: the daily rating refresh only touches ACTIVE, non deleted tenants,
 * writes {@code RATING_REFRESHED} audits with actor {@code system}, honours the feature
 * switch and isolates per-tenant failures.
 */
@SpringBootTest
@ActiveProfiles("test")
class RatingRefreshServiceTest {

    @Autowired
    private RatingRefreshService refreshService;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private TenantProperties properties;
    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private RatingService ratingServiceSpy;

    private String activeId;
    private String draftId;
    private String deletedId;

    @BeforeEach
    void setUp() {
        TestSecurity.platformAdmin();
        TenantFixtures.clean(jdbc);
        activeId = TenantFixtures.createTenant(tenantService, "t-active", "Active Tenant").id();
        draftId = TenantFixtures.createTenant(tenantService, "t-draft", "Draft Tenant").id();
        deletedId = TenantFixtures.createTenant(tenantService, "t-deleted", "Deleted Tenant").id();
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", activeId);
        tenantService.delete(deletedId);
        TestSecurity.clear();
    }

    @AfterEach
    void tearDown() {
        reset(ratingServiceSpy);
        properties.getRating().setAutoRefreshEnabled(false);
        TestSecurity.clear();
    }

    @Test
    void refreshOnlyProcessesActiveNonDeletedTenants() {
        int refreshed = refreshService.refreshActive(200);

        assertThat(refreshed).isEqualTo(1);
        assertThat(ratingCount(activeId)).isEqualTo(1);
        assertThat(ratingCount(draftId)).isZero();
        assertThat(ratingCount(deletedId)).isZero();
        assertThat(auditCount(activeId, "RATING_REFRESHED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'RATING_REFRESHED'", String.class, activeId)).isEqualTo("system");

        TenantRow active = tenantRepository.findById(activeId).orElseThrow();
        TenantRow draft = tenantRepository.findById(draftId).orElseThrow();
        assertThat(active.ratingGrade()).isNotNull();
        assertThat(active.ratingScore()).isNotNull();
        assertThat(draft.ratingGrade()).isNull();
    }

    @Test
    void scheduledRefreshHonoursTheFeatureSwitch() {
        // The test profile disables the scheduler; a disabled switch must not read anything.
        refreshService.scheduledRefresh();
        assertThat(ratingCount(activeId)).isZero();

        properties.getRating().setAutoRefreshEnabled(true);
        refreshService.scheduledRefresh();
        assertThat(ratingCount(activeId)).isEqualTo(1);
    }

    @Test
    void oneBrokenTenantDoesNotAbortTheBatch() {
        TestSecurity.platformAdmin();
        String secondActiveId = TenantFixtures.createTenant(tenantService, "t-active-2", "Active Two").id();
        TestSecurity.clear();
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", secondActiveId);

        doThrow(new IllegalStateException("simulated rating failure"))
                .when(ratingServiceSpy).assessAsSystem(activeId);

        int refreshed = refreshService.refreshActive(200);

        // Only the healthy tenant counted; the failure was logged and swallowed.
        assertThat(refreshed).isEqualTo(1);
        assertThat(ratingCount(activeId)).isZero();
        assertThat(ratingCount(secondActiveId)).isEqualTo(1);
        assertThat(tenantRepository.findById(secondActiveId).orElseThrow().ratingGrade()).isNotNull();
    }

    private long ratingCount(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_ratings WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0L : count;
    }

    private long auditCount(String tenantId, String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = ?", Long.class, tenantId, action);
        return count == null ? 0L : count;
    }
}
