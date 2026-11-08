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

import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.api.AccountCreateRequest;
import com.suhong.jiuding.tenant.api.AccountCreateResult;
import com.suhong.jiuding.tenant.api.AccountStatusRequest;
import com.suhong.jiuding.tenant.api.AccountUpdateRequest;
import com.suhong.jiuding.tenant.api.AccountView;
import com.suhong.jiuding.tenant.api.ChangePasswordRequest;
import com.suhong.jiuding.tenant.api.LoginEventView;
import com.suhong.jiuding.tenant.api.ResetPasswordResult;
import com.suhong.jiuding.tenant.config.TenantProperties;
import com.suhong.jiuding.tenant.domain.AccountRole;
import com.suhong.jiuding.tenant.domain.AccountStatus;
import com.suhong.jiuding.tenant.quota.QuotaGuard;
import com.suhong.jiuding.tenant.repository.AccountRepository;
import com.suhong.jiuding.tenant.repository.AccountRow;
import com.suhong.jiuding.tenant.repository.LoginEventRepository;
import com.suhong.jiuding.tenant.repository.LoginEventRow;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.support.Paging;
import com.suhong.jiuding.tenant.support.TenantAccess;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tenant account directory (docs/11 section 6.3).
 *
 * <p>Security invariants enforced here:
 * <ul>
 *   <li>{@code username} is unique per tenant and stored lower case;</li>
 *   <li>passwords are PBKDF2-HMAC-SHA256 210k hashed, never returned and never logged;</li>
 *   <li>a generated temporary password is returned exactly once (create / reset);</li>
 *   <li>a TENANT_ADMIN cannot grant the TENANT_ADMIN role (no privilege escalation);</li>
 *   <li>every write is audited in {@code tenant_audits}.</li>
 * </ul>
 */
@Service
public class AccountService {

    private final AccountRepository accounts;
    private final OrgUnitRepository orgUnits;
    private final TenantRepository tenants;
    private final LoginEventRepository loginEvents;
    private final PasswordHasher passwordHasher;
    private final TenantAccess access;
    private final AuditService auditService;
    private final TenantProperties properties;
    private final QuotaGuard quotaGuard;

    public AccountService(AccountRepository accounts,
                          OrgUnitRepository orgUnits,
                          TenantRepository tenants,
                          LoginEventRepository loginEvents,
                          PasswordHasher passwordHasher,
                          TenantAccess access,
                          AuditService auditService,
                          TenantProperties properties,
                          QuotaGuard quotaGuard) {
        this.accounts = accounts;
        this.orgUnits = orgUnits;
        this.tenants = tenants;
        this.loginEvents = loginEvents;
        this.passwordHasher = passwordHasher;
        this.access = access;
        this.auditService = auditService;
        this.properties = properties;
        this.quotaGuard = quotaGuard;
    }

    // ------------------------------------------------------------------ queries

    public PageResult<AccountView> list(String tenantId, String orgUnitId, String role, String status,
                                        String keyword, int page, int size) {
        return list(tenantId, orgUnitId, role, status, keyword, page, size, false);
    }

    public PageResult<AccountView> list(String tenantId, String orgUnitId, String role, String status,
                                        String keyword, int page, int size, boolean mask) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        Paging.validate(page, size, properties.getMaxPageSize());
        boolean masked = mask || isAuditor(scope);

        String resolvedRole = role == null || role.isBlank() ? null : AccountRole.parse(role).name();
        String resolvedStatus = status == null || status.isBlank() ? null : AccountStatus.parse(status).name();
        String resolvedOrgUnit = Texts.blankToNull(orgUnitId);
        String resolvedKeyword = keyword == null || keyword.isBlank() ? null : keyword.trim();

        // MEMBER may only ever see its own account row (docs/13 section 3).
        if (access.isMember(scope)) {
            return memberList(tenant, scope, resolvedOrgUnit, resolvedRole, resolvedStatus,
                    resolvedKeyword, page, size, masked);
        }

        long total;
        List<AccountRow> rows;
        if (access.isOrgAdmin(scope)) {
            // ORG_ADMIN is confined to its org_units.path subtree.
            OrgUnitRow scopeUnit = access.requireScopeOrgUnit(scope, tenant.id());
            if (resolvedOrgUnit != null) {
                access.assertOrgReadable(scope, tenant.id(), loadOrgUnit(tenant.id(), resolvedOrgUnit));
            }
            total = accounts.countInOrgSubtree(tenant.id(), scopeUnit.path(), resolvedRole,
                    resolvedStatus, resolvedKeyword);
            rows = total == 0 ? List.of() : accounts.searchInOrgSubtree(tenant.id(), scopeUnit.path(),
                    resolvedRole, resolvedStatus, resolvedKeyword, page, size);
        } else {
            if (resolvedOrgUnit != null) {
                loadOrgUnit(tenant.id(), resolvedOrgUnit);
            }
            total = accounts.count(tenant.id(), resolvedOrgUnit, resolvedRole, resolvedStatus, resolvedKeyword);
            rows = total == 0
                    ? List.of()
                    : accounts.search(tenant.id(), resolvedOrgUnit, resolvedRole, resolvedStatus,
                            resolvedKeyword, page, size);
        }
        Map<String, String> orgNames = orgUnitNames(tenant.id(), rows);
        List<AccountView> items = new ArrayList<>(rows.size());
        for (AccountRow row : rows) {
            items.add(AccountView.of(row,
                    row.orgUnitId() == null ? null : orgNames.get(row.orgUnitId()), masked));
        }
        return PageResult.of(items, total, page, size);
    }

    public AccountView get(String tenantId, String id) {
        return get(tenantId, id, false);
    }

    public AccountView get(String tenantId, String id, boolean mask) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        AccountRow row = load(tenant.id(), id);
        access.assertAccountReadable(scope, tenant.id(), row);
        return view(tenant.id(), row, mask || isAuditor(scope));
    }

    /**
     * Login security trail for one account (docs/14 section 3.1). Visible to platform
     * administrators, the tenant administrator, auditors, the account's ORG_ADMIN subtree
     * and the account owner itself.
     */
    public PageResult<LoginEventView> loginEvents(String tenantId, String accountId, int page, int size) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        Paging.validate(page, size, properties.getMaxPageSize());
        AccountRow account = load(tenant.id(), accountId);
        assertCanReadLoginEvents(scope, tenant, account);

        long total = loginEvents.count(tenant.id(), account.id());
        List<LoginEventRow> rows = total == 0
                ? List.of()
                : loginEvents.page(tenant.id(), account.id(), page, size);
        return PageResult.of(rows.stream().map(LoginEventView::of).toList(), total, page, size);
    }

    // ------------------------------------------------------------------ commands

    public AccountCreateResult create(String tenantId, AccountCreateRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        String username = passwordHasher.normalizeUsername(request.username());
        validateUsername(username);
        if (accounts.findByUsername(tenant.id(), username).isPresent()) {
            throw BizException.conflict("username already exists in tenant: " + username);
        }
        String orgUnitId = resolveOrgUnitForWrite(scope, tenant.id(), request.orgUnitId());
        AccountRole role = AccountRole.parseOrDefault(request.role());
        assertCanGrant(scope, role);
        // docs/17 section 4: seats quota is enforced over non deleted accounts.
        quotaGuard.assertSeatsAvailable(tenant, accounts.countByTenant(tenant.id()));

        String initialPassword = Texts.blankToNull(request.initialPassword());
        String tempPassword = null;
        boolean mustChange;
        if (initialPassword == null) {
            tempPassword = passwordHasher.generateTemporary();
            initialPassword = tempPassword;
            mustChange = true;
        } else {
            passwordHasher.validate(initialPassword, username);
            mustChange = false;
        }
        PasswordHasher.Credential credential = passwordHasher.hash(initialPassword);
        var now = TimeUtil.now();
        AccountRow row = new AccountRow(
                UUID.randomUUID().toString(),
                tenant.id(),
                orgUnitId,
                username,
                request.displayName().trim(),
                Texts.blankToNull(request.email()),
                Texts.blankToNull(request.phone()),
                role.name(),
                AccountStatus.ACTIVE.name(),
                credential.passwordHash(),
                credential.passwordSalt(),
                passwordHasher.fingerprint(initialPassword),
                mustChange,
                0,
                null,
                access.actor(scope),
                now,
                now,
                null,
                0L);
        try {
            accounts.insert(row);
        } catch (DuplicateKeyException ex) {
            throw BizException.conflict("username already exists in tenant: " + username);
        }
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_CREATED",
                "account " + username + " created with role " + role
                        + (tempPassword == null ? "" : " (one-time temporary password issued)"));
        return new AccountCreateResult(view(tenant.id(), row), tempPassword);
    }

    public AccountView update(String tenantId, String id, AccountUpdateRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        AccountRow row = load(tenant.id(), id);
        access.assertAccountWritable(scope, tenant.id(), row);
        if (access.isOrgAdmin(scope) && AccountRole.TENANT_ADMIN.name().equals(row.role())) {
            throw BizException.forbidden("ORG_ADMIN cannot modify a TENANT_ADMIN account");
        }
        AccountRole role = request.role() == null || request.role().isBlank()
                ? AccountRole.parse(row.role())
                : AccountRole.parse(request.role());
        if (role != AccountRole.parse(row.role())) {
            assertCanGrant(scope, role);
        }
        String orgUnitId;
        if (request.orgUnitId() == null) {
            // ORG_ADMIN cannot move an account out of its subtree; an omitted org unit
            // therefore keeps the current (already subtree checked) assignment.
            orgUnitId = access.isOrgAdmin(scope) ? row.orgUnitId() : null;
        } else {
            orgUnitId = resolveOrgUnitForWrite(scope, tenant.id(), request.orgUnitId());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("display_name", request.displayName().trim());
        fields.put("email", Texts.blankToNull(request.email()));
        fields.put("phone", Texts.blankToNull(request.phone()));
        fields.put("org_unit_id", orgUnitId);
        fields.put("role", role.name());
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_UPDATED",
                "account " + row.username() + " updated (role " + row.role() + " -> " + role + ")");
        return view(tenant.id(), load(tenant.id(), id));
    }

    public AccountView changeStatus(String tenantId, String id, AccountStatusRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        AccountRow row = load(tenant.id(), id);
        access.assertAccountWritable(scope, tenant.id(), row);
        AccountStatus target = AccountStatus.parseOperable(request.status());
        if (row.status().equals(target.name())) {
            return view(tenant.id(), row);
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", target.name());
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        String reason = Texts.blankToNull(request.reason());
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_STATUS_CHANGED",
                "account " + row.username() + " " + row.status() + " -> " + target
                        + (reason == null ? "" : ": " + reason));
        return view(tenant.id(), load(tenant.id(), id));
    }

    public ResetPasswordResult resetPassword(String tenantId, String id, String newPassword) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        AccountRow row = load(tenant.id(), id);
        access.assertAccountWritable(scope, tenant.id(), row);
        String password = Texts.blankToNull(newPassword);
        String tempPassword = null;
        boolean mustChange;
        if (password == null) {
            tempPassword = passwordHasher.generateTemporary();
            password = tempPassword;
            mustChange = true;
        } else {
            passwordHasher.validate(password, row.username());
            mustChange = false;
        }
        PasswordHasher.Credential credential = passwordHasher.hash(password);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("password_hash", credential.passwordHash());
        fields.put("password_salt", credential.passwordSalt());
        fields.put("password_fingerprint", passwordHasher.fingerprint(password));
        fields.put("must_change_password", mustChange);
        fields.put("failed_attempts", 0);
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_PASSWORD_RESET",
                "account " + row.username() + " password reset"
                        + (tempPassword == null ? "" : " with a one-time temporary password"));
        return new ResetPasswordResult(tempPassword);
    }

    /** Unlock a LOCKED account (docs/14 section 3.1): status ACTIVE, attempts reset, audited. */
    public AccountView unlock(String tenantId, String id) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        AccountRow row = load(tenant.id(), id);
        access.assertAccountWritable(scope, tenant.id(), row);
        if (AccountStatus.ACTIVE.name().equals(row.status())) {
            if (row.failedAttempts() == 0) {
                return view(tenant.id(), row);
            }
            Map<String, Object> resetFields = new LinkedHashMap<>();
            resetFields.put("failed_attempts", 0);
            resetFields.put("updated_at", TimeUtil.now());
            if (accounts.updateFields(tenant.id(), row.id(), row.version(), resetFields) == 0) {
                throw BizException.conflict("account " + row.username()
                        + " was modified concurrently, please retry");
            }
            return view(tenant.id(), load(tenant.id(), id));
        }
        if (!AccountStatus.LOCKED.name().equals(row.status())) {
            throw BizException.conflict("account " + row.username() + " is not LOCKED (status="
                    + row.status() + ")");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("status", AccountStatus.ACTIVE.name());
        fields.put("failed_attempts", 0);
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_UNLOCKED",
                "account " + row.username() + " unlocked (LOCKED -> ACTIVE, failed attempts reset)");
        return view(tenant.id(), load(tenant.id(), id));
    }

    /**
     * Self-service password change (docs/14 section 3.1): only the account owner (JWT sub ==
     * account id) may call it; the old password and the password policy are enforced and
     * {@code must_change_password} is cleared afterwards.
     */
    public AccountView changePassword(String tenantId, String id, ChangePasswordRequest request) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        AccountRow row = load(tenant.id(), id);
        if (scope == null || !row.id().equals(scope.userId())) {
            throw BizException.forbidden("only the account owner can change its password");
        }
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        if (!passwordHasher.matches(request.oldPassword(), row.passwordHash(), row.passwordSalt())) {
            throw BizException.invalid("old password is incorrect");
        }
        passwordHasher.validate(request.newPassword(), row.username());
        if (request.newPassword().equals(request.oldPassword())) {
            throw BizException.invalid("new password must differ from the old password");
        }
        PasswordHasher.Credential credential = passwordHasher.hash(request.newPassword());
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("password_hash", credential.passwordHash());
        fields.put("password_salt", credential.passwordSalt());
        fields.put("password_fingerprint", passwordHasher.fingerprint(request.newPassword()));
        fields.put("must_change_password", false);
        fields.put("failed_attempts", 0);
        fields.put("updated_at", TimeUtil.now());
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        auditService.record(tenant.id(), access.actor(scope), "PASSWORD_CHANGED",
                "account " + row.username() + " changed its password");
        return view(tenant.id(), load(tenant.id(), id));
    }

    public void delete(String tenantId, String id) {
        TenantScope scope = access.requireScope();
        TenantRow tenant = scopedTenant(scope, tenantId);
        access.requireOrgWrite(scope);
        AccountRow row = load(tenant.id(), id);
        access.assertAccountWritable(scope, tenant.id(), row);
        var now = TimeUtil.now();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("deleted_at", now);
        fields.put("status", AccountStatus.DELETED.name());
        fields.put("updated_at", now);
        if (accounts.updateFields(tenant.id(), row.id(), row.version(), fields) == 0) {
            throw BizException.conflict("account " + row.username() + " was modified concurrently, please retry");
        }
        // Keep the org tree consistent: a soft deleted account must not remain an org manager.
        int clearedManagerRefs = orgUnits.clearManagerReference(tenant.id(), row.id(), TimeUtil.now());
        auditService.record(tenant.id(), access.actor(scope), "ACCOUNT_DELETED",
                "account " + row.username() + " soft deleted"
                        + (clearedManagerRefs == 0 ? "" : " (cleared " + clearedManagerRefs + " org manager reference(s))"));
    }

    /** Internal (service-to-service) account count, unaffected by the caller tenant scope. */
    public long countInternal(String tenantId) {
        TenantRow tenant = tenants.findById(tenantId)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + tenantId));
        if (tenant.deletedAt() != null) {
            // Keep the internal endpoints consistent with GET /internal/tenants/{id}.
            throw BizException.notFound("tenant not found: " + tenantId);
        }
        return accounts.countByTenant(tenantId);
    }

    // ------------------------------------------------------------------ helpers

    private TenantRow scopedTenant(TenantScope scope, String tenantId) {
        TenantRow tenant = access.requireTenant(tenantId);
        access.assertSameTenant(scope, tenant);
        return tenant;
    }

    private AccountRow load(String tenantId, String id) {
        return accounts.findById(tenantId, id)
                .orElseThrow(() -> BizException.notFound("account not found: " + id));
    }

    private AccountView view(String tenantId, AccountRow row) {
        return view(tenantId, row, false);
    }

    private AccountView view(String tenantId, AccountRow row, boolean mask) {
        String orgName = null;
        if (row.orgUnitId() != null) {
            orgName = orgUnits.namesByIds(tenantId, List.of(row.orgUnitId())).get(row.orgUnitId());
        }
        return AccountView.of(row, orgName, mask);
    }

    private boolean isAuditor(TenantScope scope) {
        return scope != null && scope.hasRole("AUDITOR");
    }

    /** Login-event visibility matrix (docs/14 section 3.1). */
    private void assertCanReadLoginEvents(TenantScope scope, TenantRow tenant, AccountRow account) {
        if (access.isPlatform(scope)
                || (scope != null && (scope.hasRole("TENANT_ADMIN") || scope.hasRole("AUDITOR")))) {
            return;
        }
        if (access.isOrgAdmin(scope)) {
            access.assertAccountReadable(scope, tenant.id(), account);
            return;
        }
        if (scope != null && account.id().equals(scope.userId())) {
            return;
        }
        throw BizException.forbidden("not allowed to read the login events of this account");
    }

    private Map<String, String> orgUnitNames(String tenantId, List<AccountRow> rows) {
        List<String> ids = new ArrayList<>();
        for (AccountRow row : rows) {
            if (row.orgUnitId() != null) {
                ids.add(row.orgUnitId());
            }
        }
        Map<String, String> names = new HashMap<>();
        if (!ids.isEmpty()) {
            names.putAll(orgUnits.namesByIds(tenantId, ids));
        }
        return names;
    }

    private String resolveOrgUnitForWrite(TenantScope scope, String tenantId, String orgUnitId) {
        String resolved = Texts.blankToNull(orgUnitId);
        if (resolved == null) {
            if (access.isOrgAdmin(scope)) {
                throw BizException.forbidden(
                        "ORG_ADMIN cannot create or move an account outside of its organization subtree");
            }
            return null;
        }
        OrgUnitRow target = loadOrgUnit(tenantId, resolved);
        if (access.isOrgAdmin(scope)) {
            access.assertOrgWritable(scope, tenantId, target);
        }
        return resolved;
    }

    private OrgUnitRow loadOrgUnit(String tenantId, String orgUnitId) {
        return orgUnits.findById(tenantId, orgUnitId)
                .orElseThrow(() -> BizException.notFound("org unit not found: " + orgUnitId));
    }

    /**
     * MEMBER account list scope: exactly the caller's own account row. Filters are applied
     * in memory so a MEMBER can never enumerate colleagues (docs/13 section 3).
     */
    private PageResult<AccountView> memberList(TenantRow tenant, TenantScope scope, String orgUnitId,
                                               String role, String status, String keyword,
                                               int page, int size, boolean mask) {
        AccountRow self = selfAccount(tenant.id(), scope);
        boolean matches = self != null
                && (orgUnitId == null || orgUnitId.equals(self.orgUnitId()))
                && (role == null || role.equals(self.role()))
                && (status == null || status.equals(self.status()))
                && (keyword == null || keywordMatches(self, keyword));
        long total = matches ? 1L : 0L;
        List<AccountView> items = matches && page == 0
                ? List.of(view(tenant.id(), self, mask))
                : List.of();
        return PageResult.of(items, total, page, size);
    }

    private AccountRow selfAccount(String tenantId, TenantScope scope) {
        if (scope == null || scope.userId() == null || scope.userId().isBlank()) {
            return null;
        }
        return accounts.findById(tenantId, scope.userId())
                .or(() -> accounts.findByUsername(tenantId, passwordHasher.normalizeUsername(scope.userId())))
                .filter(row -> row.deletedAt() == null)
                .orElse(null);
    }

    private boolean keywordMatches(AccountRow row, String keyword) {
        String needle = keyword.toLowerCase(java.util.Locale.ROOT);
        return containsIgnoreCase(row.username(), needle)
                || containsIgnoreCase(row.displayName(), needle)
                || containsIgnoreCase(row.email(), needle);
    }

    private boolean containsIgnoreCase(String value, String needle) {
        return value != null && value.toLowerCase(java.util.Locale.ROOT).contains(needle);
    }

    private void assertCanGrant(TenantScope scope, AccountRole role) {
        if (access.isPlatform(scope)) {
            return;
        }
        if (access.isOrgAdmin(scope)) {
            if (role != AccountRole.MEMBER && role != AccountRole.AUDITOR) {
                throw BizException.forbidden("ORG_ADMIN can only grant the MEMBER or AUDITOR role");
            }
            return;
        }
        if (role == AccountRole.TENANT_ADMIN) {
            throw BizException.forbidden("tenant administrator cannot grant the TENANT_ADMIN role");
        }
    }

    private void validateUsername(String username) {
        if (username == null || username.isBlank()) {
            throw BizException.invalid("username is required");
        }
        if (username.length() > 64) {
            throw BizException.invalid("username must be at most 64 characters");
        }
        if (username.chars().anyMatch(Character::isWhitespace)) {
            throw BizException.invalid("username must not contain whitespace");
        }
    }
}
