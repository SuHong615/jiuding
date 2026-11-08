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

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.springframework.stereotype.Service;

import java.util.Optional;
import java.util.Set;

/**
 * Login / token issuance logic (docs/13 §2.1).
 *
 * <p>Resolution order:
 * <ol>
 *   <li>{@code tenantCode} empty → gateway preset platform users first (constant-time
 *       password check); if the username is not a platform user, the request is
 *       forwarded to tenant-service internal authentication anyway (a tenant username
 *       may be unique); an outage in that fallback is reported as 401 rather than 503 so
 *       a platform typo never exposes infrastructure state.</li>
 *   <li>{@code tenantCode} present → tenant-service internal authentication only.</li>
 * </ol>
 */
@Service
public class AuthService {

    /**
     * Fixed dummy hash compared when the username is unknown, so a failed login
     * costs roughly the same work as a successful one (no user enumeration by timing).
     */
    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-not-a-real-user");

    private final UserStore userStore;
    private final JwtService jwtService;
    private final TenantAuthClient tenantAuthClient;

    public AuthService(UserStore userStore, JwtService jwtService, TenantAuthClient tenantAuthClient) {
        this.userStore = userStore;
        this.jwtService = jwtService;
        this.tenantAuthClient = tenantAuthClient;
    }

    public LoginResponse login(String username, String password, String tenantCode) {
        return login(username, password, tenantCode, null);
    }

    /**
     * @param client resolved client IP / User-Agent (docs/14 §4), forwarded to
     *               tenant-service so it can record {@code account_login_events};
     *               {@code null} for callers without an HTTP request (tests)
     */
    public LoginResponse login(String username, String password, String tenantCode, ClientRequestInfo client) {
        boolean explicitTenant = tenantCode != null && !tenantCode.isBlank();
        String clientIp = client == null ? null : client.clientIp();
        String userAgent = client == null ? null : client.userAgent();

        if (!explicitTenant) {
            Optional<TenantUser> found = userStore.find(username);
            if (found.isPresent()) {
                TenantUser user = found.get();
                if (!PasswordHasher.matches(password, user.passwordHash())) {
                    throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
                }
                return issue(user.toPrincipal(), PrincipalView.AUTH_PLATFORM, null);
            }
            // Username-only fallback: tenant-service resolves the account across tenants.
            try {
                return issueTenant(tenantAuthClient.authenticate(null, username, password, clientIp, userAgent));
            } catch (TenantAuthUnavailableException ex) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
            }
        }

        return issueTenant(tenantAuthClient.authenticate(
                tenantCode.trim(), username, password, clientIp, userAgent));
    }

    /** {@code GET /api/auth/me}: project the JWT principal, restoring the login channel. */
    public PrincipalView currentPrincipal() {
        Principal principal = AuthContext.require();
        // Platform preset users are known locally; OIDC/SSO principals have no tenant scope.
        boolean platformChannel = userStore.findByUserId(principal.userId()).isPresent()
                || principal.tenantId() == null;
        String authType = platformChannel ? PrincipalView.AUTH_PLATFORM : PrincipalView.AUTH_TENANT;
        return PrincipalView.from(principal, authType);
    }

    private LoginResponse issueTenant(TenantAuthResult result) {
        if (Boolean.TRUE.equals(result.mfaRequired())) {
            // Password accepted, second factor still required (docs/16 §3): return a
            // challenge instead of a JWT so the client can complete /login/mfa.
            if (isBlank(result.challengeToken())) {
                throw new TenantAuthUnavailableException("tenant authentication returned no MFA challenge");
            }
            return new LoginResponse(null, null, 0, null, Boolean.TRUE,
                    result.challengeToken(), result.username());
        }
        if (isBlank(result.accountId()) || isBlank(result.tenantId())) {
            throw new TenantAuthUnavailableException("tenant authentication returned an incomplete payload");
        }
        String username = isBlank(result.displayName()) ? result.username() : result.displayName();
        Set<String> roles = isBlank(result.role()) ? Set.of() : Set.of(result.role());
        Principal principal = new Principal(
                result.accountId(), username, result.tenantId(), roles, "JWT", result.orgUnitId());
        return issue(principal, PrincipalView.AUTH_TENANT, Boolean.TRUE.equals(result.mustChangePassword()));
    }

    /** {@code POST /api/auth/login/mfa}: completes the MFA login and issues the JWT. */
    public LoginResponse loginMfa(String challengeToken, String code) {
        return issueTenant(tenantAuthClient.authenticateMfa(challengeToken, code));
    }

    private LoginResponse issue(Principal principal, String authType, Boolean mustChangePassword) {
        String token = jwtService.issue(principal);
        return new LoginResponse(token, "Bearer", jwtService.ttlSeconds(),
                PrincipalView.from(principal, authType, mustChangePassword));
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
