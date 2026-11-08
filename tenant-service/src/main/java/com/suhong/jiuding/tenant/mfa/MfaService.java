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
package com.suhong.jiuding.tenant.mfa;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.domain.AccountStatus;
import com.suhong.jiuding.tenant.domain.TenantStatus;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.LoginEventRepository;
import com.suhong.jiuding.tenant.repository.LoginEventRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * MFA (TOTP) domain service (docs/16 section 3).
 *
 * <p>Self-service endpoints are only available to the authenticated tenant account itself.
 * The login flow issues a short challenge token after a correct password and verifies the
 * TOTP / one-time recovery code afterwards; challenges are single use, expire after 5
 * minutes and are invalidated after 5 failed attempts.
 */
@Service
public class MfaService {

    private static final Logger log = LoggerFactory.getLogger(MfaService.class);
    static final int MAX_ATTEMPTS = 5;
    static final String ISSUER = "TenancyCenter";
    private static final String INVALID_CREDENTIALS = "invalid credentials";

    private final MfaRepository repository;
    private final TotpService totpService;
    private final ChallengeTokenService challengeTokenService;
    private final AccountRepository accounts;
    private final TenantRepository tenants;
    private final LoginEventRepository loginEvents;
    private final AuditService auditService;

    public MfaService(MfaRepository repository,
                      TotpService totpService,
                      ChallengeTokenService challengeTokenService,
                      AccountRepository accounts,
                      TenantRepository tenants,
                      LoginEventRepository loginEvents,
                      AuditService auditService) {
        this.repository = repository;
        this.totpService = totpService;
        this.challengeTokenService = challengeTokenService;
        this.accounts = accounts;
        this.tenants = tenants;
        this.loginEvents = loginEvents;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ self-service API

    public MfaStatusView status() {
        Self self = requireSelf();
        return MfaStatusView.of(repository.findMfa(self.account().id()).orElse(null));
    }

    public MfaSetupResult setup() {
        Self self = requireSelf();
        MfaRow existing = repository.findMfa(self.account().id()).orElse(null);
        if (existing != null && existing.enabled()) {
            throw BizException.conflict("MFA is already enabled");
        }
        String secret = totpService.generateSecret();
        List<String> recoveryCodes = RecoveryCodes.generate();
        repository.upsertSetup(self.account().id(), self.tenant().id(), secret,
                RecoveryCodes.joinHashes(recoveryCodes), TimeUtil.now());
        String accountName = self.account().username() + "@" + self.tenant().tenantCode();
        return new MfaSetupResult(secret,
                totpService.otpauthUrl(ISSUER, accountName, secret), recoveryCodes);
    }

    public MfaVerifyResult verify(MfaCodeRequest request) {
        Self self = requireSelf();
        MfaRow row = repository.findMfa(self.account().id())
                .orElseThrow(() -> BizException.invalid("MFA setup has not been initialized"));
        if (row.enabled()) {
            return new MfaVerifyResult(true);
        }
        String code = request == null ? null : request.code();
        if (!totpService.verify(row.secretBase32(), code, System.currentTimeMillis())) {
            throw BizException.invalid("invalid verification code");
        }
        LocalDateTime now = TimeUtil.now();
        repository.enable(self.account().id(), now);
        auditService.record(self.tenant().id(), self.account().username(), "MFA_ENROLLED",
                "MFA (TOTP) enabled for account " + self.account().username());
        return new MfaVerifyResult(true);
    }

    public MfaStatusView disable(MfaCodeRequest request) {
        Self self = requireSelf();
        MfaRow row = repository.findMfa(self.account().id()).orElse(null);
        if (row == null || !row.enabled()) {
            throw BizException.invalid("MFA is not enabled");
        }
        String code = request == null ? null : request.code();
        boolean valid = totpService.verify(row.secretBase32(), code, System.currentTimeMillis());
        if (!valid) {
            valid = RecoveryCodes.consume(row.recoveryCodesHash(), code) != null;
        }
        if (!valid) {
            throw BizException.invalid("invalid verification code");
        }
        repository.disable(self.account().id());
        auditService.record(self.tenant().id(), self.account().username(), "MFA_DISABLED",
                "MFA disabled for account " + self.account().username());
        return new MfaStatusView(false, null);
    }

    // ------------------------------------------------------------------ login challenge

    public boolean isEnabled(String accountId) {
        return repository.findMfa(accountId).map(MfaRow::enabled).orElse(false);
    }

    /** Creates the DB challenge row and returns the signed 5 minute challenge token. */
    public String createChallenge(TenantRow tenant, AccountRow account) {
        String challengeId = UUID.randomUUID().toString();
        LocalDateTime now = TimeUtil.now();
        repository.insertChallenge(new MfaChallengeRow(
                challengeId,
                account.id(),
                tenant.id(),
                sha256(challengeId + ":" + account.id()),
                now.plusSeconds(ChallengeTokenService.TTL_SECONDS),
                0,
                null));
        return challengeTokenService.issue(account.id(), tenant.id(), account.username(), challengeId);
    }

    /**
     * Verifies a TOTP or recovery code for a challenge token.
     *
     * @return the tenant/account rows so the auth service can finish the login response
     */
    public MfaLoginResult verifyLoginChallenge(MfaAuthenticateRequest request, String ip, String userAgent) {
        String safeIp = Texts.truncate(Texts.blankToNull(ip), 64);
        String safeUa = Texts.truncate(Texts.blankToNull(userAgent), 256);
        ChallengeTokenService.ChallengeClaims claims = challengeTokenService.parse(
                request == null ? null : request.challengeToken());
        MfaChallengeRow challenge = repository.findChallenge(claims.challengeId())
                .orElseThrow(this::invalidCredentials);
        if (!challenge.accountId().equals(claims.accountId())
                || !challenge.tenantId().equals(claims.tenantId())) {
            throw invalidCredentials();
        }
        TenantRow tenant = tenants.findById(challenge.tenantId()).orElse(null);
        AccountRow account = accounts.findById(challenge.tenantId(), challenge.accountId()).orElse(null);
        if (tenant == null || account == null
                || tenant.deletedAt() != null || !TenantStatus.ACTIVE.name().equals(tenant.status())
                || account.deletedAt() != null || !AccountStatus.ACTIVE.name().equals(account.status())) {
            throw invalidCredentials();
        }

        LocalDateTime now = TimeUtil.now();
        if (challenge.consumedAt() != null) {
            challengeFailed(tenant, account, safeIp, safeUa, "challenge already used");
            throw invalidCredentials();
        }
        if (challenge.expiresAt().isBefore(now)) {
            challengeFailed(tenant, account, safeIp, safeUa, "challenge expired");
            throw invalidCredentials();
        }
        if (challenge.attempts() >= MAX_ATTEMPTS) {
            challengeFailed(tenant, account, safeIp, safeUa, "challenge invalidated");
            throw invalidCredentials();
        }
        MfaRow mfa = repository.findMfa(account.id()).orElse(null);
        if (mfa == null || !mfa.enabled()) {
            throw invalidCredentials();
        }

        String code = request == null ? null : request.code();
        boolean verified = totpService.verify(mfa.secretBase32(), code, System.currentTimeMillis());
        String remainingRecoveryHashes = null;
        boolean recoveryUsed = false;
        if (!verified) {
            remainingRecoveryHashes = RecoveryCodes.consume(mfa.recoveryCodesHash(), code);
            verified = remainingRecoveryHashes != null;
            recoveryUsed = verified;
        }
        if (!verified) {
            int attempts = challenge.attempts() + 1;
            LocalDateTime consumedAt = attempts >= MAX_ATTEMPTS ? now : null;
            repository.recordAttempt(challenge.id(), attempts, consumedAt);
            auditService.record(tenant.id(), account.username(), "MFA_CHALLENGE_FAILED",
                    "invalid MFA code, attempts=" + attempts
                            + (consumedAt != null ? "; challenge invalidated after " + MAX_ATTEMPTS + " failures" : ""));
            recordLoginEvent(tenant.id(), account, safeIp, safeUa, false, "MFA_CHALLENGE_FAILED");
            throw invalidCredentials();
        }

        repository.recordAttempt(challenge.id(), challenge.attempts() + 1, now);
        if (recoveryUsed) {
            repository.updateRecoveryCodes(account.id(), remainingRecoveryHashes);
        }
        repository.updateLastUsed(account.id(), now);
        auditService.record(tenant.id(), account.username(), "MFA_VERIFIED",
                recoveryUsed
                        ? "MFA verified with a one-time recovery code"
                        : "MFA verified with a TOTP code");
        return new MfaLoginResult(tenant, account, recoveryUsed);
    }

    // ------------------------------------------------------------------ helpers

    private void challengeFailed(TenantRow tenant, AccountRow account, String ip, String userAgent,
                                 String reason) {
        auditService.record(tenant.id(), account.username(), "MFA_CHALLENGE_FAILED", reason);
        recordLoginEvent(tenant.id(), account, ip, userAgent, false, "MFA_CHALLENGE_FAILED");
    }

    private void recordLoginEvent(String tenantId, AccountRow account, String ip, String userAgent,
                                  boolean success, String reason) {
        try {
            loginEvents.insert(new LoginEventRow(
                    UUID.randomUUID().toString(),
                    tenantId,
                    account.id(),
                    Texts.truncate(account.username(), 64),
                    TimeUtil.now(),
                    ip,
                    userAgent,
                    success,
                    Texts.truncate(reason, 64)));
        } catch (RuntimeException ex) {
            log.warn("could not persist MFA login event for tenant {}: {}", tenantId, ex.toString());
        }
    }

    private Self requireSelf() {
        Principal principal = AuthContext.get();
        if (principal == null || !"JWT".equals(principal.authType())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "JWT authentication required");
        }
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        if (scope.isPlatformAdmin()) {
            throw BizException.forbidden("MFA is only available for tenant accounts");
        }
        if (scope.tenantId() == null || scope.tenantId().isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        TenantRow tenant = tenants.findById(scope.tenantId())
                .or(() -> tenants.findByCode(scope.tenantId()))
                .filter(row -> row.deletedAt() == null)
                .orElseThrow(() -> BizException.notFound("tenant not found"));
        String userId = scope.userId();
        AccountRow account = accounts.findById(tenant.id(), userId)
                .or(() -> accounts.findByUsername(tenant.id(),
                        userId == null ? "" : userId.toLowerCase(java.util.Locale.ROOT)))
                .filter(row -> row.deletedAt() == null)
                .orElseThrow(() -> BizException.notFound("account not found"));
        return new Self(tenant, account);
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private BizException invalidCredentials() {
        return new BizException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
    }

    /** Authenticated tenant account performing self-service MFA operations. */
    private record Self(TenantRow tenant, AccountRow account) {
    }

    /** Result of a successful MFA login verification. */
    public record MfaLoginResult(TenantRow tenant, AccountRow account, boolean recoveryCodeUsed) {
    }
}
