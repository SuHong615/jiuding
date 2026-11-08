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
package com.suhong.jiuding.gateway.auth;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Tenant-account authentication result returned by tenant-service
 * ({@code POST /internal/auth/authenticate}, docs/13 搂2.2).
 *
 * <p>{@code mfaRequired} / {@code challengeToken} are present when the account has MFA
 * enabled (docs/16 搂3); in that case the password step did not authenticate the account
 * yet and the gateway must answer with a challenge instead of a JWT.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TenantAuthResult(String accountId,
                               String tenantId,
                               String tenantCode,
                               String username,
                               String displayName,
                               String role,
                               String orgUnitId,
                               String orgUnitPath,
                               Boolean mustChangePassword,
                               Boolean mfaRequired,
                               String challengeToken) {

    /** Backwards-compatible constructor (non-MFA payload). */
    public TenantAuthResult(String accountId, String tenantId, String tenantCode, String username,
                            String displayName, String role, String orgUnitId, String orgUnitPath,
                            Boolean mustChangePassword) {
        this(accountId, tenantId, tenantCode, username, displayName, role, orgUnitId, orgUnitPath,
                mustChangePassword, null, null);
    }
}
