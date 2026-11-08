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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.tenant.api.AuthenticateRequest;
import com.suhong.jiuding.tenant.api.AuthenticateResponse;
import com.suhong.jiuding.tenant.domain.AccountStatus;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.mfa.MfaAuthenticateRequest;
import com.suhong.jiuding.tenant.mfa.MfaService;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.LoginEventRepository;
import com.suhong.jiuding.tenant.repository.LoginEventRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant account authentication for the gateway login chain (docs/13 section 2.2,
 * docs/14 section 2).
 *
 * <p>Security invariants:
 * <ul>
 *   <li>tenant must exist, not be soft deleted and be ACTIVE; account must be ACTIVE;</li>
 *   <li>PBKDF2-HMAC-SHA256 (210k) verification uses a constant time comparison;</li>
 *   <li>every failure is reported as the same 401 {@code invalid credentials};</li>
 *   <li>failed attempts are counted and the 5th failure locks the account;</li>
 *   <li>login events (IP/User-Agent/result) are persisted, never any password material.</li>
 * </ul>
 */
@Service
public class AccountAuthService {

    private static final Logger log = LoggerFactory.getLogger(AccountAuthService.class);

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final String INVALID_CREDENTIALS = "invalid credentials";
    /** Dummy PBKDF2 material so unknown accounts cost the same as wrong passwords. */
    private static final String DUMMY_HASH = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DUMMY_SALT = Base64.getEncoder().encodeToString(new byte[16]);

    private final TenantRepository tenants;
    private final AccountRepository accounts;
    private final OrgUnitRepository orgUnits;
    private final LoginEventRepository loginEvents;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;
    private final MfaService mfaService;

    public AccountAuthService(TenantRepository tenants,
                              AccountRepository accounts,
                              OrgUnitRepository orgUnits,
                              LoginEventRepository loginEvents,
                              PasswordHasher passwordHasher,
                              AuditService auditService,
                              MfaService mfaService) {
        this.tenants = tenants;
        this.accounts = accounts;
        this.orgUnits = orgUnits;
        this.loginEvents = loginEvents;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
        this.mfaService = mfaService;
    }

    /** Backwards compatible entry point (no request metadata available). */
    public AuthenticateResponse authenticate(AuthenticateRequest request) {
        return authenticate(request, null, null);
    }

    public AuthenticateResponse authenticate(AuthenticateRequest request, String ip, String userAgent) {
        String tenantCode = Texts.blankToNull(request == null ? null : request.tenantCode());
        String username = passwordHasher.normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        if (tenantCode == null || username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw invalidCredentials();
        }
        String safeIp = Texts.truncate(Texts.blankToNull(ip), 64);
        String safeUa = Texts.truncate(Texts.blankToNull(userAgent), 256);

        TenantRow tenant = tenants.findByCode(tenantCode).orElse(null);
        if (tenant == null) {
            // Unknown tenant: there is no tenant id to attach the security event to.
            log.warn("tenant authentication rejected: unknown tenantCode={}", tenantCode);
            throw invalidCredentials();
        }
        AccountRow account = accounts.findByUsername(tenant.id(), username).orElse(null);

        if (tenant.deletedAt() != null || !TenantStatus.ACTIVE.name().equals(tenant.status())) {
            recordEvent(tenant.id(), account, username, safeIp, safeUa, false, "TENANT_NOT_ACTIVE");
            auditService.record(tenant.id(), username, "LOGIN_FAILED",
                    "tenant is not ACTIVE (status=" + tenant.status() + ")");
            throw invalidCredentials();
        }
        if (account == null || account.deletedAt() != null) {
            // Burn the same PBKDF2 cost to avoid a username enumeration timing side channel.
            passwordHasher.matches(password, DUMMY_HASH, DUMMY_SALT);
            recordEvent(tenant.id(), null, username, safeIp, safeUa, false, "UNKNOWN_ACCOUNT");
            auditService.record(tenant.id(), username, "LOGIN_FAILED", "unknown account");
            throw invalidCredentials();
        }
        if (!AccountStatus.ACTIVE.name().equals(account.status())) {
            String reason = AccountStatus.LOCKED.name().equals(account.status())
                    ? "ACCOUNT_LOCKED" : "ACCOUNT_NOT_ACTIVE";
            recordEvent(tenant.id(), account, username, safeIp, safeUa, false, reason);
            auditService.record(tenant.id(), account.username(), "LOGIN_FAILED",
                    "account is not ACTIVE (status=" + account.status() + ")");
            throw invalidCredentials();
        }
        if (!passwordHasher.matches(password, account.passwordHash(), account.passwordSalt())) {
            recordFailure(tenant, account, safeIp, safeUa);
            throw invalidCredentials();
        }

        // docs/16 section 3: a correct password with MFA enabled only yields a short lived
        // challenge token; the full principal is released after the second factor.
        if (mfaService.isEnabled(account.id())) {
            String challengeToken = mfaService.createChallenge(tenant, account);
            return AuthenticateResponse.mfaChallenge(tenant.tenantCode(), account.username(), challengeToken);
        }

        recordSuccess(tenant, account, safeIp, safeUa);
        return principalResponse(tenant, account);
    }

    /**
     * Second login step for MFA enabled accounts (docs/16 section 3): verifies the challenge
     * token plus TOTP / one-time recovery code and returns the full principal.
     */
    public AuthenticateResponse authenticateMfa(MfaAuthenticateRequest request, String ip, String userAgent) {
        MfaService.MfaLoginResult result = mfaService.verifyLoginChallenge(request, ip, userAgent);
        recordSuccess(result.tenant(), result.account(), ip, userAgent);
        return principalResponse(result.tenant(), result.account());
    }

    private AuthenticateResponse principalResponse(TenantRow tenant, AccountRow account) {
        String orgUnitPath = account.orgUnitId() == null ? null
                : orgUnits.findById(tenant.id(), account.orgUnitId())
                        .map(OrgUnitRow::path)
                        .orElse(null);
        return AuthenticateResponse.principal(
                account.id(),
                tenant.id(),
                tenant.tenantCode(),
                account.username(),
                account.displayName(),
                account.role(),
                account.orgUnitId(),
                orgUnitPath,
                account.mustChangePassword());
    }

    private void recordFailure(TenantRow tenant, AccountRow account, String ip, String userAgent) {
        int attempts = account.failedAttempts() + 1;
        boolean lock = attempts >= MAX_FAILED_ATTEMPTS;
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("failed_attempts", attempts);
        fields.put("updated_at", TimeUtil.now());
        if (lock) {
            fields.put("status", AccountStatus.LOCKED.name());
        }
        if (accounts.updateFields(tenant.id(), account.id(), account.version(), fields) == 0) {
            log.warn("failed attempt counter lost a concurrent update for account {}", account.username());
        }
        auditService.record(tenant.id(), account.username(), "LOGIN_FAILED",
                "invalid password, failedAttempts=" + attempts);
        recordEvent(tenant.id(), account, account.username(), ip, userAgent, false, "INVALID_PASSWORD");
        if (lock) {
            auditService.record(tenant.id(), account.username(), "ACCOUNT_LOCKED",
                    "account locked after " + attempts + " failed login attempts");
            recordEvent(tenant.id(), account, account.username(), ip, userAgent, false, "ACCOUNT_LOCKED");
        }
    }

    private void recordSuccess(TenantRow tenant, AccountRow account, String ip, String userAgent) {
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("failed_attempts", 0);
        fields.put("last_login_at", now);
        fields.put("updated_at", now);
        if (accounts.updateFields(tenant.id(), account.id(), account.version(), fields) == 0) {
            log.warn("last login timestamp lost a concurrent update for account {}", account.username());
        }
        auditService.record(tenant.id(), account.username(), "LOGIN_SUCCESS",
                "tenant account login succeeded (tenant=" + tenant.tenantCode() + ")");
        recordEvent(tenant.id(), account, account.username(), ip, userAgent, true, null);
    }

    private void recordEvent(String tenantId, AccountRow account, String username,
                             String ip, String userAgent, boolean success, String reason) {
        try {
            loginEvents.insert(new LoginEventRow(
                    UUID.randomUUID().toString(),
                    tenantId,
                    account == null ? null : account.id(),
                    Texts.truncate(Texts.defaultIfBlank(username, "unknown"), 64),
                    TimeUtil.now(),
                    Texts.truncate(ip, 64),
                    Texts.truncate(userAgent, 256),
                    success,
                    Texts.truncate(reason, 64)));
        } catch (RuntimeException ex) {
            // The security trail must never break the authentication response itself.
            log.warn("could not persist login event for tenant {}: {}", tenantId, ex.toString());
        }
    }

    private BizException invalidCredentials() {
        return new BizException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}
