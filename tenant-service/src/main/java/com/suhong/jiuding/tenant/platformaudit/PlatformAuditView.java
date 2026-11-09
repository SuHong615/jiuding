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
package com.suhong.jiuding.tenant.platformaudit;

import com.suhong.jiuding.tenant.support.TimeUtil;

import java.time.LocalDateTime;

/** Cross-tenant audit row of the platform audit center (docs/21 section 2). */
public record PlatformAuditView(
        String id,
        String tenantId,
        String tenantCode,
        String tenantName,
        String at,
        String actor,
        String action,
        String detail) {

    static PlatformAuditView of(String id, String tenantId, String tenantCode, String tenantName,
                                LocalDateTime at, String actor, String action, String detail) {
        return new PlatformAuditView(id, tenantId, tenantCode, tenantName, TimeUtil.iso(at),
                actor, action, detail);
    }
}
