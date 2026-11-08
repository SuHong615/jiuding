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
package com.suhong.jiuding.tenant.quota;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.TenantAccess;
import org.springframework.stereotype.Service;

/**
 * Quota / usage calculation (docs/17 section 3).
 *
 * <p>Usage is counted over non deleted rows: accounts ({@code deleted_at IS NULL}, any
 * status) and organization units. Storage usage is not collected yet and is reported as
 * null. Visible to platform administrators, tenant administrators and auditors; ORG_ADMIN /
 * MEMBER receive 403.
 */
@Service
public class QuotaService {

    private final TenantAccess access;
    private final TenantRepository tenants;
    private final AccountRepository accounts;
    private final OrgUnitRepository orgUnits;

    public QuotaService(TenantAccess access,
                        TenantRepository tenants,
                        AccountRepository accounts,
                        OrgUnitRepository orgUnits) {
        this.access = access;
        this.tenants = tenants;
        this.accounts = accounts;
        this.orgUnits = orgUnits;
    }

    /** {@code GET /api/tenants/{tenantId}/quota}. */
    public QuotaView quota(String tenantId) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        boolean allowed = SecurityScope.isPlatformWide(scope)
                || scope.hasRole("TENANT_ADMIN")
                || scope.hasRole("AUDITOR");
        if (!allowed) {
            throw BizException.forbidden(
                    "platform administrator, tenant administrator or auditor role required");
        }
        return build(tenant);
    }

    /** Internal variant used by the gateway RPM limiter (X-Internal-Token, no JWT scope). */
    public QuotaView quotaInternal(String tenantId) {
        TenantRow tenant = tenants.findById(tenantId)
                .filter(row -> row.deletedAt() == null)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenantId));
        return build(tenant);
    }

    private QuotaView build(TenantRow tenant) {
        long usedSeats = accounts.countByTenant(tenant.id());
        long usedOrgUnits = orgUnits.countByTenant(tenant.id());
        QuotaView.Usage seats = usage(tenant.quotaSeats(), usedSeats);
        QuotaView.Usage orgUnits = usage(tenant.quotaOrgUnits(), usedOrgUnits);
        QuotaView.StorageQuota storage = new QuotaView.StorageQuota(
                effectiveLimit(tenant.quotaStorageGb()), null, null);
        QuotaView.ApiQuota apiRpm = new QuotaView.ApiQuota(effectiveLimit(tenant.quotaApiRpm()));
        return new QuotaView(tenant.id(), tenant.plan(), seats, orgUnits, storage, apiRpm,
                status(seats, orgUnits));
    }

    private QuotaView.Usage usage(Integer rawLimit, long used) {
        Integer limit = effectiveLimit(rawLimit);
        if (limit == null) {
            return new QuotaView.Usage(null, used, null, null);
        }
        long remaining = Math.max(0L, limit - used);
        int percent = (int) Math.round(used * 100.0 / limit);
        return new QuotaView.Usage(limit, used, remaining, percent);
    }

    private String status(QuotaView.Usage... usages) {
        boolean warning = false;
        for (QuotaView.Usage usage : usages) {
            if (usage.limit() == null) {
                continue;
            }
            if (usage.used() >= usage.limit()) {
                return "EXCEEDED";
            }
            if (usage.percent() != null && usage.percent() >= 80) {
                warning = true;
            }
        }
        return warning ? "WARNING" : "OK";
    }

    private Integer effectiveLimit(Integer raw) {
        return raw == null || raw <= 0 ? null : raw;
    }
}
