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
package com.suhong.jiuding.tenant.retention;

import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.OrgUnitCreateRequest;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.service.AccountService;
import com.suhong.jiuding.tenant.service.OrgUnitService;
import com.suhong.jiuding.tenant.service.RatingService;
import com.suhong.jiuding.tenant.service.TenantService;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import com.suhong.jiuding.tenant.support.TestSecurity;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * docs/15 section 8: retention purge windows (login events / audits / collect jobs / soft
 * deleted tenants), the cascade delete of the tenant identity domain and the final
 * {@code TENANT_PURGED} audit entry.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RetentionServiceTest {

    @Autowired
    private RetentionService retentionService;
    @Autowired
    private RetentionProperties retentionProperties;
    @Autowired
    private TenantService tenantService;
    @Autowired
    private OrgUnitService orgUnitService;
    @Autowired
    private AccountService accountService;
    @Autowired
    private RatingService ratingService;
    @Autowired
    private TenantRepository tenantRepository;
    @Autowired
    private JdbcTemplate jdbc;

    private String oldTenantId;

    @BeforeEach
    void setUp() {
        TenantFixtures.clean(jdbc);
        retentionProperties.setAutoPurgeEnabled(true);
        TestSecurity.platformAdmin();
        oldTenantId = createSoftDeletedTenant("t-purge-old", "Purge Old", 200);
        createSoftDeletedTenant("t-purge-recent", "Purge Recent", 10);
        TestSecurity.clear();
    }

    @AfterEach
    void tearDown() {
        retentionProperties.setAutoPurgeEnabled(true);
        TestSecurity.clear();
    }

    @Test
    void purgeRemovesOnlyExpiredRowsAndCascadesSoftDeletedTenants() {
        LocalDateTime now = TimeUtil.now();
        insertLoginEvent("old-event", now.minusDays(100));
        insertLoginEvent("new-event", now);
        insertTenantLoginEvent(oldTenantId, "cascade-event", now);
        insertAudit("fixture-old", now.minusDays(400), "OLD_ACTION");
        insertAudit("fixture-new", now, "NEW_ACTION");
        insertCollectJob(now.minusDays(100), "old-job");
        insertCollectJob(now, "new-job");

        PurgeStats stats = retentionService.purge();

        assertThat(stats.loginEvents()).isEqualTo(1);
        assertThat(stats.audits()).isEqualTo(1);
        assertThat(stats.collectJobs()).isEqualTo(1);
        assertThat(stats.tenants()).isEqualTo(1);

        assertThat(countLoginEvents("old-event")).isZero();
        assertThat(countLoginEvents("new-event")).isEqualTo(1);
        assertThat(countAuditAction("OLD_ACTION")).isZero();
        assertThat(countAuditAction("NEW_ACTION")).isEqualTo(1);
        assertThat(countCollectJobsBySource("old-job")).isZero();
        assertThat(countCollectJobsBySource("new-job")).isEqualTo(1);

        // The expired soft deleted tenant is gone with its whole identity domain.
        assertThat(tenantRepository.findById(oldTenantId)).isEmpty();
        assertThat(countByTenant("org_units", oldTenantId)).isZero();
        assertThat(countByTenant("accounts", oldTenantId)).isZero();
        assertThat(countByTenant("tenant_ratings", oldTenantId)).isZero();
        assertThat(countByTenant("account_login_events", oldTenantId)).isZero();
        // Only the final TENANT_PURGED entry remains for the purged tenant.
        assertThat(countByTenant("tenant_audits", oldTenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'TENANT_PURGED'", Long.class, oldTenantId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'TENANT_PURGED'", String.class, oldTenantId)).contains("t-purge-old");

        // The recently soft deleted tenant is retained.
        assertThat(tenantRepository.findByCode("t-purge-recent")).isPresent();
    }

    @Test
    void purgeWindowsRespectTheConfiguredRetentionDays() {
        LocalDateTime now = TimeUtil.now();
        insertLoginEvent("just-inside", now.minusDays(retentionProperties.getLoginEventDays() - 1));
        insertLoginEvent("just-expired", now.minusDays(retentionProperties.getLoginEventDays() + 1));
        insertAudit("fixture-audit-inside",
                now.minusDays(retentionProperties.getAuditDays() - 1), "AUDIT_INSIDE");
        insertAudit("fixture-audit-expired",
                now.minusDays(retentionProperties.getAuditDays() + 1), "AUDIT_EXPIRED");
        insertCollectJob(now.minusDays(retentionProperties.getCollectJobDays() - 1), "inside-job");
        insertCollectJob(now.minusDays(retentionProperties.getCollectJobDays() + 1), "expired-job");

        TestSecurity.platformAdmin();
        String boundaryOld = createSoftDeletedTenant("t-boundary-old", "Boundary Old",
                retentionProperties.getSoftDeletedTenantDays() + 1);
        String boundaryNew = createSoftDeletedTenant("t-boundary-new", "Boundary New",
                retentionProperties.getSoftDeletedTenantDays() - 1);
        TestSecurity.clear();

        PurgeStats stats = retentionService.purge();

        assertThat(stats.loginEvents()).isEqualTo(1);
        assertThat(stats.audits()).isEqualTo(1);
        assertThat(stats.collectJobs()).isEqualTo(1);
        assertThat(stats.tenants()).isEqualTo(2);

        assertThat(countLoginEvents("just-inside")).isEqualTo(1);
        assertThat(countLoginEvents("just-expired")).isZero();
        assertThat(countAuditAction("AUDIT_INSIDE")).isEqualTo(1);
        assertThat(countAuditAction("AUDIT_EXPIRED")).isZero();
        assertThat(countCollectJobsBySource("inside-job")).isEqualTo(1);
        assertThat(countCollectJobsBySource("expired-job")).isZero();
        assertThat(tenantRepository.findById(boundaryOld)).isEmpty();
        assertThat(tenantRepository.findById(boundaryNew)).isPresent();
    }

    @Test
    void scheduledPurgeHonoursTheAutoPurgeSwitch() {
        LocalDateTime now = TimeUtil.now();
        insertAudit("fixture-switch", now.minusDays(400), "SWITCH_ACTION");

        retentionProperties.setAutoPurgeEnabled(false);
        retentionService.scheduledPurge();
        assertThat(countAuditAction("SWITCH_ACTION")).isEqualTo(1);

        retentionProperties.setAutoPurgeEnabled(true);
        retentionService.scheduledPurge();
        assertThat(countAuditAction("SWITCH_ACTION")).isZero();
    }

    // ------------------------------------------------------------------ helpers

    private String createSoftDeletedTenant(String code, String name, int deletedDaysAgo) {
        TenantView tenant = TenantFixtures.createTenant(tenantService, code, name);
        String tenantId = tenant.id();
        String orgId = orgUnitService.create(tenantId,
                new OrgUnitCreateRequest(null, "HQ", "Headquarters", "COMPANY", 0, null)).id();
        accountService.create(tenantId, new AccountCreateRequest("member-" + code, "Member",
                code + "@acme.io", null, orgId, "MEMBER", "Passw0rd123"));
        ratingService.assess(tenantId, null);
        tenantService.delete(tenantId);
        jdbc.update("UPDATE tenants SET deleted_at = ? WHERE id = ?",
                TimeUtil.now().minusDays(deletedDaysAgo), tenantId);
        return tenantId;
    }

    private void insertLoginEvent(String username, LocalDateTime at) {
        insertTenantLoginEvent("fixture-tenant", username, at);
    }

    private void insertTenantLoginEvent(String tenantId, String username, LocalDateTime at) {
        jdbc.update("INSERT INTO account_login_events"
                        + " (id, tenant_id, account_id, username, at, ip, user_agent, success, reason)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId, null, username, at,
                "127.0.0.1", "retention-test", true, null);
    }

    private void insertAudit(String tenantId, LocalDateTime at, String action) {
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId, at, "tester", action, "retention fixture");
    }

    private void insertCollectJob(LocalDateTime createdAt, String sourceName) {
        jdbc.update("INSERT INTO collect_jobs"
                        + " (id, mode, source_name, status, total, imported, duplicated, failed, created_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), "MANUAL_BATCH", sourceName, "SUCCEEDED",
                0, 0, 0, 0, createdAt);
    }

    private long countLoginEvents(String username) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM account_login_events WHERE username = ?",
                Long.class, username);
        return count == null ? 0L : count;
    }

    private long countAuditAction(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE action = ?",
                Long.class, action);
        return count == null ? 0L : count;
    }

    private long countCollectJobsBySource(String sourceName) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM collect_jobs WHERE source_name = ?",
                Long.class, sourceName);
        return count == null ? 0L : count;
    }

    private long countByTenant(String table, String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table + " WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0L : count;
    }
}
