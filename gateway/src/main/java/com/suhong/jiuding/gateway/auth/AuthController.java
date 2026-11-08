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

import com.suhong.jiuding.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Authentication endpoints handled locally by the gateway (docs/13 §2, docs/14 §4). */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final LoginRateLimiter loginRateLimiter;

    public AuthController(AuthService authService, LoginRateLimiter loginRateLimiter) {
        this.authService = authService;
        this.loginRateLimiter = loginRateLimiter;
    }

    /**
     * Whitelisted in {@code tenancy.security.whitelist}; issues the JWT.
     * {@code tenantCode} is optional: omit it for platform users, supply it for a tenant account.
     *
     * <p>Brute-force protection (docs/14 §4) is enforced here — the only login endpoint —
     * before any credential check; the resolved client IP is also forwarded to
     * tenant-service for its login audit trail.
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        ClientRequestInfo client = ClientRequestInfo.resolve(httpRequest);
        loginRateLimiter.check(client.clientIp(), request.username());
        return ApiResponse.ok(authService.login(
                request.username(), request.password(), request.tenantCode(), client));
    }

    /**
     * Second MFA login step (docs/16 搂3): the password step returned
     * {@code mfaRequired + challengeToken}; this exchanges the challenge plus a TOTP or
     * recovery code for the JWT.
     */
    @PostMapping("/login/mfa")
    public ApiResponse<LoginResponse> loginMfa(@Valid @RequestBody LoginMfaRequest request,
                                               HttpServletRequest httpRequest) {
        ClientRequestInfo client = ClientRequestInfo.resolve(httpRequest);
        loginRateLimiter.check(client.clientIp(), "mfa");
        return ApiResponse.ok(authService.loginMfa(request.challengeToken(), request.code()));
    }

    /** Requires a valid bearer token (enforced by the shared {@code AuthFilter}). */
    @GetMapping("/me")
    public ApiResponse<PrincipalView> me() {
        return ApiResponse.ok(authService.currentPrincipal());
    }

    /** Tenants the current identity can switch to (docs/20 §3). Requires a bearer token. */
    @GetMapping("/tenants")
    public ApiResponse<java.util.List<TenantSummary>> tenants() {
        return ApiResponse.ok(authService.switchableTenants());
    }

    /**
     * Re-issues a JWT for another tenant membership of the same identity (docs/20 §3).
     * Requires a bearer token; the membership is validated by tenant-service.
     */
    @PostMapping("/switch-tenant")
    public ApiResponse<LoginResponse> switchTenant(@Valid @RequestBody SwitchTenantRequest request) {
        return ApiResponse.ok(authService.switchTenant(request.tenantCode(), request.password()));
    }
}
