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
package com.suhong.jiuding.tenant.offboarding;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.TenantView;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tenant offboarding (docs/18 section 3): platform administrator only, ACTIVE/SUSPENDED
 * tenants are terminated, soft deleted (data retained for the retention window) and audited
 * with the mandatory reason.
 */
@Service
public class TenantOffboardService {

    private final TenantRepository tenants;
    private final OrgUnitRepository orgUnits;
    private final AccountRepository accounts;
    private final AuditService auditService;

    public TenantOffboardService(TenantRepository tenants,
                                 OrgUnitRepository orgUnits,
                                 AccountRepository accounts,
                                 AuditService auditService) {
        this.tenants = tenants;
        this.orgUnits = orgUnits;
        this.accounts = accounts;
        this.auditService = auditService;
    }

    public TenantView offboard(String tenantId, OffboardRequest request) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        String reason = Texts.blankToNull(request == null ? null : request.reason());
        if (reason == null) {
            throw BizException.invalid("reason is required");
        }
        reason = Texts.truncate(reason, 512);

        TenantRow row = tenants.findById(tenantId)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenantId));
        if (row.deletedAt() != null) {
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        if (!TenantStatus.ACTIVE.name().equals(row.status())
                && !TenantStatus.SUSPENDED.name().equals(row.status())) {
            throw BizException.conflict("tenant status " + row.status()
                    + " cannot be offboarded; only ACTIVE or SUSPENDED tenants can be offboarded");
        }

        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", TenantStatus.TERMINATED.name());
        fields.put("deleted_at", now);
        fields.put("updated_at", now);
        if (tenants.updateFields(row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("tenant " + row.tenantCode()
                    + " was modified concurrently, please retry");
        }
        auditService.record(row.id(), SecurityScope.actor(scope), "TENANT_OFFBOARDED",
                "tenant offboarded: reason=" + reason);

        TenantRow updated = tenants.findById(row.id()).orElseThrow();
        return TenantView.of(updated,
                (int) orgUnits.countByTenant(updated.id()),
                (int) accounts.countByTenant(updated.id()));
    }
}
