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
package com.suhong.jiuding.tenant.platformuser;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.permission.RbacService;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.service.PasswordHasher;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Platform user management and authentication (docs/20 section 3).
 *
 * <p>CRUD is restricted to platform administrator JWTs; passwords use the same PBKDF2 policy
 * as tenant accounts and are stored as {@code saltBase64:hashBase64} inside
 * {@code password_hash}. Disabled users can never authenticate. Every mutation is audited
 * under the pseudo tenant {@code platform}.
 */
@Service
public class PlatformUserService {

    private static final Logger log = LoggerFactory.getLogger(PlatformUserService.class);

    private static final Set<String> STATUSES = Set.of("ACTIVE", "DISABLED");
    private static final String DEFAULT_STATUS = "ACTIVE";
    private static final List<String> DEFAULT_ROLES = List.of("PLATFORM_ADMIN");
    private static final String INVALID_CREDENTIALS = "invalid credentials";
    private static final String DUMMY_HASH = Base64.getEncoder().encodeToString(new byte[32]);
    private static final String DUMMY_SALT = Base64.getEncoder().encodeToString(new byte[16]);

    private final PlatformUserRepository repository;
    private final PasswordHasher passwordHasher;
    private final AuditService auditService;

    public PlatformUserService(PlatformUserRepository repository,
                               PasswordHasher passwordHasher,
                               AuditService auditService) {
        this.repository = repository;
        this.passwordHasher = passwordHasher;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ CRUD

    public List<PlatformUserView> list(String status, String keyword) {
        requirePlatformAdmin();
        String resolvedStatus = Texts.blankToNull(status) == null
                ? null : parseStatus(status);
        return repository.list(resolvedStatus, Texts.blankToNull(keyword)).stream()
                .map(PlatformUserView::of)
                .toList();
    }

    public PlatformUserView get(String id) {
        requirePlatformAdmin();
        return PlatformUserView.of(requireUser(id));
    }

    public PlatformUserView create(PlatformUserCreateRequest request) {
        TenantScope scope = requirePlatformAdmin();
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        String username = normalizeUsername(request.username());
        if (repository.findByUsername(username).isPresent()) {
            throw BizException.conflict("platform username already exists: " + username);
        }
        List<String> roles = normalizeRoles(request.roles());
        String status = request.status() == null || request.status().isBlank()
                ? DEFAULT_STATUS : parseStatus(request.status());
        String temporaryPassword = null;
        String password = Texts.blankToNull(request.password());
        if (password == null) {
            temporaryPassword = passwordHasher.generateTemporary();
            password = temporaryPassword;
        }
        passwordHasher.validate(password, username);
        String stored = encode(password);
        var now = TimeUtil.now();
        PlatformUserRow row = new PlatformUserRow(
                UUID.randomUUID().toString(),
                username,
                Texts.blankToNull(request.displayName()),
                stored,
                PlatformUserView.joinRoles(roles),
                status,
                Texts.blankToNull(request.email()),
                now,
                now,
                null);
        repository.insert(row);
        audit(SecurityScope.actor(scope), "PLATFORM_USER_CREATED",
                "platform user " + username + " created (roles=" + row.roles()
                        + ", status=" + status + ")"
                        + (temporaryPassword == null ? "" : " (temporary password issued)"));
        return PlatformUserView.of(row, temporaryPassword);
    }

    public PlatformUserView update(String id, PlatformUserUpdateRequest request) {
        TenantScope scope = requirePlatformAdmin();
        PlatformUserRow existing = requireUser(id);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        if (request.displayName() != null) {
            fields.put("display_name", Texts.blankToNull(request.displayName()));
        }
        if (request.email() != null) {
            fields.put("email", Texts.blankToNull(request.email()));
        }
        if (request.roles() != null) {
            fields.put("roles", PlatformUserView.joinRoles(normalizeRoles(request.roles())));
        }
        if (request.status() != null) {
            fields.put("status", parseStatus(request.status()));
        }
        if (Texts.blankToNull(request.password()) != null) {
            passwordHasher.validate(request.password(), existing.username());
            fields.put("password_hash", encode(request.password()));
        }
        fields.put("updated_at", TimeUtil.now());
        if (repository.updateFields(id, fields) == 0) {
            throw BizException.notFound("platform user not found: " + id);
        }
        audit(SecurityScope.actor(scope), "PLATFORM_USER_UPDATED",
                "platform user " + existing.username() + " updated");
        return PlatformUserView.of(requireUser(id));
    }

    public PlatformPasswordResetResult resetPassword(String id, PlatformUserResetRequest request) {
        TenantScope scope = requirePlatformAdmin();
        PlatformUserRow existing = requireUser(id);
        String password = Texts.blankToNull(request == null ? null : request.newPassword());
        boolean generated = password == null;
        if (generated) {
            password = passwordHasher.generateTemporary();
        } else {
            passwordHasher.validate(password, existing.username());
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("password_hash", encode(password));
        fields.put("updated_at", TimeUtil.now());
        repository.updateFields(id, fields);
        audit(SecurityScope.actor(scope), "PLATFORM_USER_PASSWORD_RESET",
                "platform user " + existing.username() + " password reset");
        return new PlatformPasswordResetResult(existing.username(), password);
    }

    public void delete(String id) {
        TenantScope scope = requirePlatformAdmin();
        PlatformUserRow existing = requireUser(id);
        repository.delete(id);
        audit(SecurityScope.actor(scope), "PLATFORM_USER_DELETED",
                "platform user " + existing.username() + " deleted");
    }

    // ------------------------------------------------------------------ internal authentication

    public PlatformUserAuthResponse authenticate(PlatformUserAuthRequest request) {
        String username = passwordHasher.normalizeUsername(request == null ? null : request.username());
        String password = request == null ? null : request.password();
        if (username == null || username.isBlank() || password == null || password.isEmpty()) {
            throw invalidCredentials();
        }
        PlatformUserRow row = repository.findByUsername(username).orElse(null);
        if (row == null) {
            passwordHasher.matches(password, DUMMY_HASH, DUMMY_SALT);
            throw invalidCredentials();
        }
        if (!"ACTIVE".equals(row.status())) {
            passwordHasher.matches(password, DUMMY_HASH, DUMMY_SALT);
            throw invalidCredentials();
        }
        if (!matches(password, row.passwordHash())) {
            throw invalidCredentials();
        }
        var now = TimeUtil.now();
        repository.touchLastLogin(row.id(), now);
        PlatformUserRow updated = repository.findById(row.id()).orElse(row);
        audit("platform:" + row.username(), "PLATFORM_USER_LOGIN",
                "platform user " + row.username() + " authenticated");
        return PlatformUserAuthResponse.of(updated);
    }

    // ------------------------------------------------------------------ helpers

    private TenantScope requirePlatformAdmin() {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        return scope;
    }

    private PlatformUserRow requireUser(String id) {
        String value = Texts.blankToNull(id);
        return repository.findById(value == null ? "" : value)
                .orElseThrow(() -> BizException.notFound("platform user not found: " + id));
    }

    private String normalizeUsername(String raw) {
        String username = passwordHasher.normalizeUsername(raw);
        if (username == null || username.isBlank()) {
            throw BizException.invalid("username is required");
        }
        if (username.length() < 3 || username.length() > 64) {
            throw BizException.invalid("username must be between 3 and 64 characters");
        }
        if (!username.matches("^[a-z0-9][a-z0-9._-]*$")) {
            throw BizException.invalid("username may only contain letters, digits, '.', '_' and '-'");
        }
        return username;
    }

    private List<String> normalizeRoles(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            return DEFAULT_ROLES;
        }
        List<String> roles = new ArrayList<>();
        for (String value : raw) {
            String role = Texts.blankToNull(value);
            if (role == null) {
                continue;
            }
            String normalized = role.toUpperCase(Locale.ROOT);
            if (!normalized.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
                throw BizException.invalid("invalid role code: " + value);
            }
            if (!roles.contains(normalized)) {
                roles.add(normalized);
            }
        }
        if (roles.isEmpty()) {
            throw BizException.invalid("roles must contain at least one role code");
        }
        return roles;
    }

    private String parseStatus(String raw) {
        String status = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
        if (!STATUSES.contains(status)) {
            throw BizException.invalid("status must be ACTIVE or DISABLED");
        }
        return status;
    }

    private String encode(String password) {
        PasswordHasher.Credential credential = passwordHasher.hash(password);
        String value = credential.passwordSalt() + ":" + credential.passwordHash();
        if (value.length() > 256) {
            throw new IllegalStateException("platform password hash exceeds column length");
        }
        return value;
    }

    private boolean matches(String password, String stored) {
        if (stored == null) {
            return false;
        }
        int separator = stored.indexOf(':');
        if (separator <= 0) {
            return false;
        }
        return passwordHasher.matches(password, stored.substring(separator + 1),
                stored.substring(0, separator));
    }

    private void audit(String actor, String action, String detail) {
        try {
            auditService.record(RbacService.PLATFORM_AUDIT_TENANT, actor, action, detail);
        } catch (RuntimeException ex) {
            log.warn("could not persist platform user audit {}: {}", action, ex.toString());
        }
    }

    private BizException invalidCredentials() {
        return new BizException(ErrorCode.UNAUTHORIZED, INVALID_CREDENTIALS);
    }
}
