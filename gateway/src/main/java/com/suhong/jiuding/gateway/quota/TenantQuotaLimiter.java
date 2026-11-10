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
package com.suhong.jiuding.gateway.quota;

import com.suhong.jiuding.gateway.config.TenantQuotaProperties;
import com.suhong.jiuding.gateway.ratelimit.RateLimitStore;
import org.springframework.stereotype.Component;

/**
 * Sliding-window request counter per tenant id (docs/17 §5, docs/23 §5).
 *
 * <p>Key = {@code quota:<tenantId>}; window length
 * {@code tenancy.gateway.tenant-quota.window-seconds} (60s by default). Counting is delegated
 * to the {@link RateLimitStore} (Redis when configured, in-memory otherwise, with automatic
 * fallback on Redis failure).
 */
@Component
public class TenantQuotaLimiter {

    private final TenantQuotaProperties properties;
    private final RateLimitStore store;

    public TenantQuotaLimiter(TenantQuotaProperties properties, RateLimitStore store) {
        this.properties = properties;
        this.store = store;
    }

    /**
     * Records one request for the tenant and reports whether it is within the limit.
     *
     * @param limit positive API RPM limit
     * @return {@code false} when the sliding window is already full
     */
    public boolean tryAcquire(String tenantId, long limit) {
        long windowMs = Math.max(1L, properties.getWindowSeconds()) * 1000L;
        return store.tryAcquire("quota:" + tenantId, limit, windowMs);
    }

    /** Visible for tests. */
    public void reset() {
        store.reset();
    }
}
