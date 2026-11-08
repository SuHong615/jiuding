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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Login / token issuance logic (docs/13 §2.1, docs/20 §3).
 *
 * <p>{@code tenantCode} empty resolution order:
 * <ol>
 *   <li>tenant-service platform account ({@code /internal/platform-users/authenticate});</li>
 *   <li>legacy in-memory preset directory (compatibility during the DB migration — the
 *       preset list is never exposed by any API);</li>
 *   <li>tenant membership resolution by username+password; multiple matches produce a
 *       {@code tenantSelectionRequired} response instead of a JWT.</li>
 * </ol>
 * With an explicit {@code tenantCode} the tenant-service account authentication is used
 * directly. Reserved errors: 401 unified invalid credentials; downstream outages are 503
 * (via {@link TenantAuthUnavailableException}).
 */
@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    /**
     * Fixed dummy hash compared when the username is unknown, so a failed login
     * costs roughly the same work as a successful one (no user enumeration by timing).
     */
    private static final String DUMMY_HASH = PasswordHasher.hash("dummy-password-not-a-real-user");

    private final UserStore userStore;
    private final JwtService jwtService;
    private final TenantAuthClient tenantAuthClient;
    private final PlatformAuthClient platformAuthClient;
    private final TenantIdentityCache identityCache;

    public AuthService(UserStore userStore, JwtService jwtService, TenantAuthClient tenantAuthClient,
                       PlatformAuthClient platformAuthClient, TenantIdentityCache identityCache) {
        this.userStore = userStore;
        this.jwtService = jwtService;
        this.tenantAuthClient = tenantAuthClient;
        this.platformAuthClient = platformAuthClient;
        this.identityCache = identityCache;
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

        if (explicitTenant) {
            return loginFromTenantResult(tenantAuthClient.authenticate(
                    tenantCode.trim(), username, password, clientIp, userAgent));
        }

        // 1) Platform account stored in tenant_db (docs/20 §2/§3).
        try {
            Optional<PlatformUserResult> platformUser =
                    platformAuthClient.authenticate(username, password, clientIp, userAgent);
            if (platformUser.isPresent() && !platformUser.get().roles().isEmpty()) {
                return issuePlatform(platformUser.get());
            }
        } catch (TenantAuthUnavailableException ex) {
            // Fall through to the legacy preset directory; a platform-login outage must not
            // take the compatibility path down with it.
            log.debug("platform account login unavailable, trying legacy presets: {}", ex.getMessage());
        }

        // 2) Legacy preset users (kept for tests / migration; never exposed as a list).
        Optional<TenantUser> found = userStore.find(username);
        if (found.isPresent()) {
            TenantUser user = found.get();
            if (!PasswordHasher.matches(password, user.passwordHash())) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
            }
            return issue(user.toPrincipal(), PrincipalView.AUTH_PLATFORM, null);
        }

        // 3) Tenant membership resolution by username+password (0/1 → account, 2+ → selection).
        try {
            return loginFromTenantResult(
                    tenantAuthClient.authenticate(null, username, password, clientIp, userAgent));
        } catch (TenantAuthUnavailableException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
    }

    /** {@code POST /api/auth/login/mfa}: completes the MFA login and issues the JWT. */
    public LoginResponse loginMfa(String challengeToken, String code) {
        return loginFromTenantResult(tenantAuthClient.authenticateMfa(challengeToken, code));
    }

    /**
     * {@code GET /api/auth/tenants}: tenants the current identity can switch to (docs/20 §3).
     *
     * <p>Platform users / SSO principals have no tenant membership and get an empty list.
     * Tenant accounts query tenant-service; when the candidate endpoint is not available
     * (tenant-service v10 exposes no listing endpoint yet) the gateway degrades to the
     * current tenant so the switcher stays usable instead of surfacing a 502.
     */
    public List<TenantSummary> switchableTenants() {
        Principal principal = AuthContext.require();
        if (isBlank(principal.tenantId())) {
            return List.of();
        }
        String username = identityCache.usernameFor(principal.userId()).orElse(principal.username());
        try {
            return tenantAuthClient.listSwitchableTenants(principal.userId(), username, principal.tenantId());
        } catch (TenantAuthUnavailableException | BizException ex) {
            // v10.1: a missing/erroring candidate endpoint must not break the switcher.
            log.warn("switchable tenant lookup failed ({}); returning empty list", ex.getMessage());
            return List.of();
        }
    }

    /** {@code POST /api/auth/switch-tenant}: re-issues a JWT for another tenant membership. */
    public LoginResponse switchTenant(String tenantCode, String password) {
        Principal principal = AuthContext.require();
        if (tenantCode == null || tenantCode.isBlank()) {
            throw BizException.invalid("tenantCode is required");
        }
        String username = identityCache.usernameFor(principal.userId()).orElse(principal.username());
        try {
            TenantAuthResult result = tenantAuthClient.switchTenant(principal.userId(), username,
                    tenantCode.trim(), principal.tenantId(), password);
            if (result.isTenantSelection()) {
                throw BizException.invalid("tenantCode is required");
            }
            return loginFromTenantResult(result);
        } catch (TenantAuthUnavailableException ex) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
        }
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

    private LoginResponse issuePlatform(PlatformUserResult user) {
        String username = isBlank(user.displayName()) ? user.username() : user.displayName();
        Principal principal = new Principal(user.userId(), username, null,
                user.roles(), "JWT", null);
        return issue(principal, PrincipalView.AUTH_PLATFORM, null);
    }

    private LoginResponse loginFromTenantResult(TenantAuthResult result) {
        if (result.isTenantSelection()) {
            List<TenantSummary> tenants = result.tenants() == null ? List.of() : result.tenants();
            if (tenants.isEmpty()) {
                throw new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
            }
            return LoginResponse.tenantSelection(tenants);
        }
        return issueTenant(result);
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
        // Keep the real account username (not the display name) for tenant switch/candidate
        // calls, which tenant-service resolves by username (docs/20 §3).
        identityCache.remember(result.accountId(), result.username());
        String username = isBlank(result.displayName()) ? result.username() : result.displayName();
        Set<String> roles = isBlank(result.role()) ? Set.of() : Set.of(result.role());
        Principal principal = new Principal(
                result.accountId(), username, result.tenantId(), roles, "JWT", result.orgUnitId());
        return issue(principal, PrincipalView.AUTH_TENANT, Boolean.TRUE.equals(result.mustChangePassword()));
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
