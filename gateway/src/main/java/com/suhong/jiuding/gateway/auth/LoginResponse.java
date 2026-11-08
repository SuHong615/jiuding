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
 * {@code POST /api/auth/login} response (docs/02 搂1).
 *
 * <p>When the tenant account has MFA enabled the password step returns
 * {@code mfaRequired=true} plus a short-lived {@code challengeToken} instead of a JWT
 * (docs/16 搂3); the client then calls {@code POST /api/auth/login/mfa}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponse(String token,
                            String tokenType,
                            long expiresIn,
                            PrincipalView principal,
                            Boolean mfaRequired,
                            String challengeToken,
                            String username) {

    /** Backwards-compatible constructor for the ordinary (non-MFA) login path. */
    public LoginResponse(String token, String tokenType, long expiresIn, PrincipalView principal) {
        this(token, tokenType, expiresIn, principal, null, null, null);
    }
}
