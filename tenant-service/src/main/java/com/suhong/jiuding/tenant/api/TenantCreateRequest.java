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

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.Map;

/** Request payload for creating / updating a tenant (docs/02-api-contracts.md section 2). */
public record TenantCreateRequest(
        @NotBlank
        @Size(max = 64)
        @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,63}$",
                message = "must start with a letter/digit and contain only letters, digits, '-' or '_'")
        String tenantCode,
        @NotBlank
        @Size(max = 128)
        String name,
        @Size(max = 64)
        String contactName,
        @Email
        @Size(max = 128)
        String contactEmail,
        @Size(max = 32)
        String contactPhone,
        @Size(max = 16)
        String plan,
        @Size(max = 32)
        String region,
        @Size(max = 24)
        String isolationMode,
        @Min(1)
        @Max(1_000_000)
        Integer quotaSeats,
        @Min(1)
        @Max(1_000_000)
        Integer quotaStorageGb,
        @Min(1)
        @Max(10_000_000)
        Integer quotaApiRpm,
        Integer quotaOrgUnits,
        @Size(max = 16)
        String source,
        Map<String, Object> attributes,
        Boolean autoSubmit) {

    /** Backwards compatible constructor without {@code quotaOrgUnits} (v6 callers/tests). */
    public TenantCreateRequest(String tenantCode,
                               String name,
                               String contactName,
                               String contactEmail,
                               String contactPhone,
                               String plan,
                               String region,
                               String isolationMode,
                               Integer quotaSeats,
                               Integer quotaStorageGb,
                               Integer quotaApiRpm,
                               String source,
                               Map<String, Object> attributes,
                               Boolean autoSubmit) {
        this(tenantCode, name, contactName, contactEmail, contactPhone, plan, region,
                isolationMode, quotaSeats, quotaStorageGb, quotaApiRpm, null, source,
                attributes, autoSubmit);
    }
}
