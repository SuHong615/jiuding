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

/**
 * Quota / usage projection (docs/17 section 3).
 *
 * <p>A {@code null} limit means unlimited; {@code remaining}/{@code percent} are then also
 * {@code null}. Storage usage is not collected yet, so its {@code used}/{@code remaining}
 * are null by contract.
 */
public record QuotaView(
        String tenantId,
        String plan,
        Usage seats,
        Usage orgUnits,
        StorageQuota storageGb,
        ApiQuota apiRpm,
        String status) {

    /** Seat / organization unit usage. */
    public record Usage(Integer limit, long used, Long remaining, Integer percent) {
    }

    /** Storage quota (usage collection is not implemented yet). */
    public record StorageQuota(Integer limit, Long used, Long remaining) {
    }

    /** API requests per minute quota (enforced by the gateway). */
    public record ApiQuota(Integer limit) {
    }
}
