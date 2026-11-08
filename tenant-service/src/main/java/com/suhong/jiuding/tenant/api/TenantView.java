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
package com.suhong.jiuding.tenant.api;

import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.TimeUtil;

/**
 * Public projection of a tenant row (docs/02 section 2 + docs/11 section 6.1).
 * Timestamps are ISO-8601 strings per the contract; password material is never present.
 */
public record TenantView(
        String id,
        String tenantCode,
        String name,
        String contactName,
        String contactEmail,
        String contactPhone,
        String plan,
        String region,
        String isolationMode,
        String status,
        String riskLevel,
        Integer riskScore,
        Integer quotaSeats,
        Integer quotaStorageGb,
        Integer quotaApiRpm,
        Integer quotaOrgUnits,
        String source,
        String rejectReason,
        String ratingGrade,
        Integer ratingScore,
        Integer orgUnitCount,
        Integer accountCount,
        String deletedAt,
        String createdAt,
        String updatedAt,
        String submittedAt,
        String decidedAt,
        String activatedAt,
        long version) {

    public static TenantView of(TenantRow row) {
        return of(row, 0, 0);
    }

    public static TenantView of(TenantRow row, int orgUnitCount, int accountCount) {
        return new TenantView(
                row.id(),
                row.tenantCode(),
                row.name(),
                row.contactName(),
                row.contactEmail(),
                row.contactPhone(),
                row.plan(),
                row.region(),
                row.isolationMode(),
                row.status(),
                row.riskLevel(),
                row.riskScore(),
                row.quotaSeats(),
                row.quotaStorageGb(),
                row.quotaApiRpm(),
                row.quotaOrgUnits(),
                row.source(),
                row.rejectReason(),
                row.ratingGrade(),
                row.ratingScore(),
                orgUnitCount,
                accountCount,
                TimeUtil.iso(row.deletedAt()),
                TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.updatedAt()),
                TimeUtil.iso(row.submittedAt()),
                TimeUtil.iso(row.decidedAt()),
                TimeUtil.iso(row.activatedAt()),
                row.version());
    }
}
