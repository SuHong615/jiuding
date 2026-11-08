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

import com.suhong.jiuding.tenant.repository.TenantRow;
import org.springframework.stereotype.Component;

/**
 * Quota enforcement guard (docs/17 section 4).
 *
 * <p>{@code NULL} or {@code <= 0} limits mean "unlimited" for backward compatibility.
 * Callers pass the current usage counted over non deleted rows.
 */
@Component
public class QuotaGuard {

    public void assertSeatsAvailable(TenantRow tenant, long usedSeats) {
        assertAvailable("seats", tenant.quotaSeats(), usedSeats);
    }

    public void assertOrgUnitsAvailable(TenantRow tenant, long usedOrgUnits) {
        assertAvailable("orgUnits", tenant.quotaOrgUnits(), usedOrgUnits);
    }

    private void assertAvailable(String resource, Integer limit, long used) {
        if (limit == null || limit <= 0) {
            return;
        }
        if (used >= limit) {
            throw new QuotaExceededException(resource, used, limit);
        }
    }
}
