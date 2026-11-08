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
package com.suhong.jiuding.tenant.domain;

import com.suhong.jiuding.common.error.BizException;

import java.util.Locale;

/** Tenant lifecycle states from docs/02-api-contracts.md section 2. */
public enum TenantStatus {
    DRAFT,
    SUBMITTED,
    RISK_REVIEW,
    PENDING_APPROVAL,
    APPROVED,
    PROVISIONING,
    ACTIVE,
    SUSPENDED,
    REJECTED,
    TERMINATED;

    public static TenantStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("tenant status is required");
        }
        try {
            return TenantStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown tenant status: " + raw);
        }
    }

    public boolean isTerminal() {
        return this == TERMINATED;
    }
}
