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
package com.suhong.jiuding.tenant.web;

import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.tenant.api.AuthenticateRequest;
import com.suhong.jiuding.tenant.api.AuthenticateResponse;
import com.suhong.jiuding.tenant.api.SwitchTenantRequest;
import com.suhong.jiuding.tenant.api.TenantChoiceView;
import com.suhong.jiuding.tenant.mfa.MfaAuthenticateRequest;
import com.suhong.jiuding.tenant.service.AccountAuthService;
import com.suhong.jiuding.tenant.support.Texts;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Internal authentication endpoints used by the gateway login chain (docs/13 section 2.2,
 * docs/16 section 3):
 * <ul>
 *   <li>{@code /authenticate}: password step; if MFA is enabled returns
 *       {@code {mfaRequired:true, challengeToken}};</li>
 *   <li>{@code /authenticate-mfa}: second step verifying the challenge token + TOTP or
 *       one-time recovery code.</li>
 * </ul>
 * Authentication of the caller itself is the shared {@code X-Internal-Token} check performed
 * by tenancy-common's AuthFilter for every {@code /internal/**} path.
 */
@RestController
@RequestMapping("/internal/auth")
public class InternalAuthController {

    private static final int IP_MAX = 64;
    private static final int UA_MAX = 256;

    private final AccountAuthService accountAuthService;

    public InternalAuthController(AccountAuthService accountAuthService) {
        this.accountAuthService = accountAuthService;
    }

    @PostMapping("/authenticate")
    public ApiResponse<AuthenticateResponse> authenticate(@RequestBody AuthenticateRequest request,
                                                          HttpServletRequest httpRequest) {
        return ApiResponses.ok(accountAuthService.authenticate(request,
                clientIp(httpRequest), userAgent(httpRequest)));
    }

    @PostMapping("/authenticate-mfa")
    public ApiResponse<AuthenticateResponse> authenticateMfa(@Valid @RequestBody MfaAuthenticateRequest request,
                                                             HttpServletRequest httpRequest) {
        return ApiResponses.ok(accountAuthService.authenticateMfa(request,
                clientIp(httpRequest), userAgent(httpRequest)));
    }

    /**
     * Switchable tenants of the signed-in account (docs/20 section 3): same username + same
     * password hash in ACTIVE tenants, excluding the current tenant. Platform accounts and
     * unknown sessions return an empty array.
     */
    @GetMapping("/tenants")
    public ApiResponse<List<TenantChoiceView>> tenants(
            @RequestParam(required = false) String accountId,
            @RequestParam(required = false) String username,
            @RequestParam(required = false) String currentTenantId) {
        return ApiResponses.ok(accountAuthService.switchableTenants(accountId, username,
                currentTenantId));
    }

    /**
     * Tenant switch for a signed-in account (docs/20 section 3): validates that the target
     * tenant has the same username + password hash and returns the target tenant principal
     * (or the MFA challenge when the target account has MFA enabled).
     */
    @PostMapping("/switch-tenant")
    public ApiResponse<AuthenticateResponse> switchTenant(@RequestBody SwitchTenantRequest request,
                                                          HttpServletRequest httpRequest) {
        return ApiResponses.ok(accountAuthService.switchTenant(request,
                clientIp(httpRequest), userAgent(httpRequest)));
    }

    static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (!first.isBlank()) {
                return Texts.truncate(first, IP_MAX);
            }
        }
        return Texts.truncate(request.getRemoteAddr(), IP_MAX);
    }

    static String userAgent(HttpServletRequest request) {
        return Texts.truncate(request.getHeader("User-Agent"), UA_MAX);
    }
}
