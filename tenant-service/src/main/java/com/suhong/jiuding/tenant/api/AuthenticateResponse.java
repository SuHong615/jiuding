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

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Successful tenant account authentication result (docs/13 section 2.2, docs/16 section 3).
 *
 * <p>When the account has MFA enabled the password step returns
 * {@code {mfaRequired:true, challengeToken, username, tenantCode}} and no principal fields;
 * the full principal (first nine fields) is only returned after the MFA challenge succeeds
 * or when MFA is not enabled. Password material is never part of this DTO.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthenticateResponse(
        String accountId,
        String tenantId,
        String tenantCode,
        String username,
        String displayName,
        String role,
        String orgUnitId,
        String orgUnitPath,
        boolean mustChangePassword,
        Boolean mfaRequired,
        String challengeToken) {

    /** Full principal payload returned after a successful (possibly MFA-completed) login. */
    public static AuthenticateResponse principal(String accountId,
                                                 String tenantId,
                                                 String tenantCode,
                                                 String username,
                                                 String displayName,
                                                 String role,
                                                 String orgUnitId,
                                                 String orgUnitPath,
                                                 boolean mustChangePassword) {
        return new AuthenticateResponse(accountId, tenantId, tenantCode, username, displayName,
                role, orgUnitId, orgUnitPath, mustChangePassword, null, null);
    }

    /** First login step when the account has MFA enabled (docs/16 section 3). */
    public static AuthenticateResponse mfaChallenge(String tenantCode, String username,
                                                    String challengeToken) {
        return new AuthenticateResponse(null, null, tenantCode, username, null, null, null,
                null, false, true, challengeToken);
    }
}
