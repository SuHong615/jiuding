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

/** Account lifecycle status (docs/11 section 3): INVITED|ACTIVE|DISABLED|LOCKED|DELETED. */
public enum AccountStatus {
    INVITED,
    ACTIVE,
    DISABLED,
    LOCKED,
    DELETED;

    public static AccountStatus parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw BizException.invalid("account status is required"
                    + " (INVITED|ACTIVE|DISABLED|LOCKED|DELETED)");
        }
        try {
            return AccountStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("unknown account status: " + raw
                    + " (expected INVITED|ACTIVE|DISABLED|LOCKED|DELETED)");
        }
    }

    /** Statuses a caller may set through {@code POST /accounts/{id}/status}. */
    public static AccountStatus parseOperable(String raw) {
        AccountStatus status = parse(raw);
        if (status == DELETED || status == INVITED) {
            throw BizException.invalid("account status can only be set to ACTIVE, DISABLED or LOCKED");
        }
        return status;
    }
}
