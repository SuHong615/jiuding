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

import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Data lifecycle / retention (docs/15 section 8).
 *
 * <p>Deletes rows older than the configured retention windows for login events, audit
 * entries and collection jobs, then hard deletes soft deleted tenants whose retention
 * window elapsed together with their identity-domain children. Every purged tenant gets a
 * final {@code TENANT_PURGED} audit entry (written after its own trail was removed).
 */
@Service
public class RetentionService {

    private static final Logger log = LoggerFactory.getLogger(RetentionService.class);
    private static final int TENANT_BATCH_LIMIT = 500;
    private static final String PURGE_ACTOR = "system";

    private final JdbcTemplate jdbc;
    private final RetentionProperties properties;
    private final AuditService auditService;

    public RetentionService(JdbcTemplate jdbc, RetentionProperties properties, AuditService auditService) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.auditService = auditService;
    }

    @Scheduled(cron = "${tenancy.retention.purge-cron:0 0 4 * * *}")
    @Transactional
    public void scheduledPurge() {
        if (!properties.isAutoPurgeEnabled()) {
            log.debug("scheduled retention purge is disabled");
            return;
        }
        PurgeStats stats = purge();
        log.info("scheduled retention purge finished: loginEvents={}, audits={}, collectJobs={}, tenants={}",
                stats.loginEvents(), stats.audits(), stats.collectJobs(), stats.tenants());
    }

    /** Runs one purge pass and reports how many rows per table were removed. */
    @Transactional
    public PurgeStats purge() {
        LocalDateTime now = TimeUtil.now();
        int loginEvents = jdbc.update("DELETE FROM account_login_events WHERE at < ?",
                now.minusDays(days(properties.getLoginEventDays())));
        int audits = jdbc.update("DELETE FROM tenant_audits WHERE at < ?",
                now.minusDays(days(properties.getAuditDays())));
        int collectJobs = jdbc.update("DELETE FROM collect_jobs WHERE created_at < ?",
                now.minusDays(days(properties.getCollectJobDays())));
        int tenants = purgeSoftDeletedTenants(now);
        return new PurgeStats(loginEvents, audits, collectJobs, tenants);
    }

    private int purgeSoftDeletedTenants(LocalDateTime now) {
        LocalDateTime cutoff = now.minusDays(days(properties.getSoftDeletedTenantDays()));
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT id, tenant_code FROM tenants WHERE deleted_at IS NOT NULL AND deleted_at < ?"
                        + " ORDER BY deleted_at ASC LIMIT ?", cutoff, TENANT_BATCH_LIMIT);
        int purged = 0;
        for (Map<String, Object> row : rows) {
            String tenantId = String.valueOf(row.get("id"));
            String tenantCode = String.valueOf(row.get("tenant_code"));
            // Cascade the identity domain first; tenant_audits is removed before the final
            // TENANT_PURGED entry is appended so that entry survives the retention pass.
            jdbc.update("DELETE FROM account_login_events WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant_ratings WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM org_units WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM accounts WHERE tenant_id = ?", tenantId);
            jdbc.update("DELETE FROM tenant_audits WHERE tenant_id = ?", tenantId);
            int deleted = jdbc.update("DELETE FROM tenants WHERE id = ?", tenantId);
            if (deleted > 0) {
                purged++;
                recordPurgedAudit(tenantId, tenantCode);
            }
        }
        return purged;
    }

    private void recordPurgedAudit(String tenantId, String tenantCode) {
        auditService.record(tenantId, PURGE_ACTOR, "TENANT_PURGED",
                "soft-deleted tenant " + tenantCode + " hard deleted by the retention policy after "
                        + days(properties.getSoftDeletedTenantDays()) + " days");
    }

    private static int days(int configured) {
        return Math.max(1, configured);
    }
}
