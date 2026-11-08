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

import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.support.PiiMask;
import com.suhong.jiuding.tenant.support.TimeUtil;

/**
 * Public projection of an account (docs/11 section 6.3). Password hash / salt are never
 * part of this DTO, therefore they can never leak through the API. When {@code mask} is
 * requested the email/phone fields are PII masked (docs/14 section 3.2).
 */
public record AccountView(
        String id,
        String tenantId,
        String orgUnitId,
        String orgUnitName,
        String username,
        String displayName,
        String email,
        String phone,
        String role,
        String status,
        boolean mustChangePassword,
        int failedAttempts,
        String lastLoginAt,
        String createdBy,
        String createdAt,
        String updatedAt,
        long version) {

    public static AccountView of(AccountRow row, String orgUnitName) {
        return of(row, orgUnitName, false);
    }

    public static AccountView of(AccountRow row, String orgUnitName, boolean mask) {
        return new AccountView(
                row.id(),
                row.tenantId(),
                row.orgUnitId(),
                orgUnitName,
                row.username(),
                row.displayName(),
                mask ? PiiMask.email(row.email()) : row.email(),
                mask ? PiiMask.phone(row.phone()) : row.phone(),
                row.role(),
                row.status(),
                row.mustChangePassword(),
                row.failedAttempts(),
                TimeUtil.iso(row.lastLoginAt()),
                row.createdBy(),
                TimeUtil.iso(row.createdAt()),
                TimeUtil.iso(row.updatedAt()),
                row.version());
    }
}
