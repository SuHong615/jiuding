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
package com.suhong.jiuding.tenant.repository;

import java.time.LocalDateTime;

/**
 * Row projection of {@code tenants} (docs/03-data-model.md + docs/11 V2 columns +
 * docs/17 V8 {@code quota_org_units}).
 * Times are UTC wall-clock {@link LocalDateTime} values and are rendered as ISO
 * instants by the API layer.
 */
public record TenantRow(
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
        String attributesJson,
        String rejectReason,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        LocalDateTime submittedAt,
        LocalDateTime decidedAt,
        LocalDateTime activatedAt,
        String ratingGrade,
        Integer ratingScore,
        LocalDateTime ratingAssessedAt,
        LocalDateTime deletedAt,
        long version) {

    /** Backwards compatible constructor (v6 row without {@code quota_org_units}). */
    public TenantRow(String id,
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
                     String source,
                     String attributesJson,
                     String rejectReason,
                     LocalDateTime createdAt,
                     LocalDateTime updatedAt,
                     LocalDateTime submittedAt,
                     LocalDateTime decidedAt,
                     LocalDateTime activatedAt,
                     String ratingGrade,
                     Integer ratingScore,
                     LocalDateTime ratingAssessedAt,
                     LocalDateTime deletedAt,
                     long version) {
        this(id, tenantCode, name, contactName, contactEmail, contactPhone, plan, region,
                isolationMode, status, riskLevel, riskScore, quotaSeats, quotaStorageGb,
                quotaApiRpm, null, source, attributesJson, rejectReason, createdAt, updatedAt,
                submittedAt, decidedAt, activatedAt, ratingGrade, ratingScore, ratingAssessedAt,
                deletedAt, version);
    }
}
