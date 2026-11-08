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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Periodic rating refresh (docs/13 section 4, docs/14 section 3.4).
 *
 * <p>Once a day (cron configurable, switchable off) every ACTIVE, non deleted tenant is
 * re-assessed with {@code actor=system}. A failure for one tenant is logged as WARN and does
 * not interrupt the remaining tenants. The k8s CronJob uses
 * {@code POST /internal/ratings/refresh} which reports {@code {refreshed, skipped, failed}}.
 */
@Service
public class RatingRefreshService {

    private static final Logger log = LoggerFactory.getLogger(RatingRefreshService.class);
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 1000;

    private final TenantRepository tenants;
    private final RatingService ratingService;
    private final TenantProperties properties;

    public RatingRefreshService(TenantRepository tenants,
                                RatingService ratingService,
                                TenantProperties properties) {
        this.tenants = tenants;
        this.ratingService = ratingService;
        this.properties = properties;
    }

    /** Counters returned to the k8s CronJob (docs/14 section 3.4). */
    public record RefreshStats(int refreshed, int skipped, int failed) {
    }

    @Scheduled(cron = "${tenancy.rating.refresh-cron:0 0 3 * * *}")
    public void scheduledRefresh() {
        if (!properties.getRating().isAutoRefreshEnabled()) {
            log.debug("scheduled rating refresh is disabled");
            return;
        }
        RefreshStats stats = refreshConfigured();
        log.info("scheduled rating refresh finished: refreshed={}, skipped={}, failed={}",
                stats.refreshed(), stats.skipped(), stats.failed());
    }

    /** Uses the configured batch limit (docs/13 section 4: default 200). */
    public RefreshStats refreshConfigured() {
        return refresh(properties.getRating().getRefreshLimit());
    }

    /** Backwards compatible counter used by tests / metrics: number of successful refreshes. */
    public int refreshActive() {
        return refreshConfigured().refreshed();
    }

    public int refreshActive(int limit) {
        return refresh(limit).refreshed();
    }

    /**
     * Re-assesses up to {@code limit} ACTIVE tenants.
     *
     * <p>{@code skipped} counts the non-deleted tenants that were not attempted (inactive or
     * beyond the batch limit); {@code failed} counts per-tenant errors that were isolated.
     */
    public RefreshStats refresh(int limit) {
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        List<TenantRow> activeTenants = tenants.findActive(effectiveLimit);
        int refreshed = 0;
        int failed = 0;
        for (TenantRow tenant : activeTenants) {
            try {
                ratingService.assessAsSystem(tenant.id());
                refreshed++;
            } catch (RuntimeException ex) {
                failed++;
                // One broken tenant must never abort the scheduled batch.
                log.warn("scheduled rating refresh failed for tenant {}: {}",
                        tenant.tenantCode(), ex.toString());
            }
        }
        int skipped = (int) Math.max(0L, tenants.countNonDeleted() - activeTenants.size());
        return new RefreshStats(refreshed, skipped, failed);
    }
}
