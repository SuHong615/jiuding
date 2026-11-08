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
package com.suhong.jiuding.tenant.permission;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.common.tenant.TenantContext;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.role.RoleCreateRequest;
import com.suhong.jiuding.tenant.role.RolePermissionsRequest;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;

/**
 * Role based access control management (docs/15 section 7).
 *
 * <p>Read endpoints expose the current caller's effective permission codes; management
 * endpoints are strictly platform-administrator JWT only. Role/permission mutations are
 * audited in the shared audit trail under the pseudo tenant {@code platform}
 * ({@code ROLE_CREATED} / {@code ROLE_UPDATED} / {@code ROLE_DELETED}).
 */
@Service
public class RbacService {

    /** Pseudo tenant id used for platform-level audit entries. */
    public static final String PLATFORM_AUDIT_TENANT = "platform";

    private static final String WILDCARD = "*";
    private static final Set<String> SCOPES = Set.of("PLATFORM", "TENANT");

    private final RbacRepository repository;
    private final AuditService auditService;
    private final PermissionCache permissionCache;

    public RbacService(RbacRepository repository, AuditService auditService,
                       PermissionCache permissionCache) {
        this.repository = repository;
        this.auditService = auditService;
        this.permissionCache = permissionCache;
    }

    // ------------------------------------------------------------------ caller permissions

    /** GET /api/me/permissions: the caller's roles and effective permission codes. */
    public MePermissionsView mePermissions() {
        TenantScope scope = requireJwtScope();
        List<String> roles = sortedRoles(scope);
        if (scope.isPlatformAdmin()) {
            return new MePermissionsView(roles, List.of(WILDCARD));
        }
        return new MePermissionsView(roles, repository.permissionCodesForRoles(roles));
    }

    // ------------------------------------------------------------------ catalogue / roles

    /** GET /api/platform/permissions: the full permission catalogue. */
    public List<PermissionView> permissions() {
        requirePlatformAdminJwt();
        return repository.listPermissions().stream().map(PermissionView::of).toList();
    }

    /** GET /api/platform/roles?scope=: roles with their permission matrix row. */
    public List<RoleView> roles(String scope) {
        requirePlatformAdminJwt();
        String resolvedScope = Texts.blankToNull(scope) == null ? null : normalizeScope(scope);
        List<RoleView> views = new ArrayList<>();
        for (RoleRow row : repository.listRoles(resolvedScope)) {
            views.add(RoleView.of(row, repository.permissionCodesForRole(row.code())));
        }
        return views;
    }

    /** POST /api/platform/roles: create a custom (non built-in) role. */
    public RoleView createRole(RoleCreateRequest request) {
        requirePlatformAdminJwt();
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        String code = normalizeRoleCode(request.code());
        String scope = normalizeScope(request.scope());
        String name = Texts.blankToNull(request.name());
        if (name == null) {
            throw BizException.invalid("role name is required");
        }
        if (repository.findRole(code).isPresent()) {
            throw BizException.conflict("role already exists: " + code);
        }
        RoleRow row = new RoleRow(code, name, scope, false, "ACTIVE",
                Texts.truncate(Texts.blankToNull(request.description()), 256));
        repository.insertRole(row);
        permissionCache.evictAll();
        audit("ROLE_CREATED", "role " + code + " created (scope=" + scope + ")");
        return RoleView.of(row, List.of());
    }

    /** PUT /api/platform/roles/{code}/permissions: replace the role permission matrix. */
    @Transactional
    public RoleView updatePermissions(String code, RolePermissionsRequest request) {
        requirePlatformAdminJwt();
        RoleRow role = requireRole(code);
        if (request == null || request.permissionCodes() == null) {
            throw BizException.invalid("permissionCodes is required");
        }
        List<String> permissionCodes = normalizePermissionCodes(request.permissionCodes());
        Set<String> existing = repository.existingPermissionCodes(permissionCodes);
        List<String> unknown = permissionCodes.stream().filter(value -> !existing.contains(value)).toList();
        if (!unknown.isEmpty()) {
            throw BizException.invalid("unknown permission codes: " + String.join(", ", unknown));
        }
        repository.replaceRolePermissions(role.code(), permissionCodes);
        permissionCache.evictAll();
        audit("ROLE_UPDATED", "role " + role.code() + " permissions updated ("
                + permissionCodes.size() + " codes)");
        return RoleView.of(role, permissionCodes);
    }

    /** DELETE /api/platform/roles/{code}: built-in roles are immutable. */
    public void deleteRole(String code) {
        requirePlatformAdminJwt();
        RoleRow role = requireRole(code);
        if (role.builtin()) {
            throw BizException.conflict("built-in role cannot be deleted: " + role.code());
        }
        repository.deleteRole(role.code());
        permissionCache.evictAll();
        audit("ROLE_DELETED", "role " + role.code() + " deleted");
    }

    // ------------------------------------------------------------------ helpers

    private RoleRow requireRole(String code) {
        String normalized = Texts.blankToNull(code) == null ? "" : code.trim().toUpperCase(Locale.ROOT);
        return repository.findRole(normalized)
                .orElseThrow(() -> BizException.notFound("role not found: " + code));
    }

    private String normalizeRoleCode(String raw) {
        String code = Texts.blankToNull(raw);
        if (code == null) {
            throw BizException.invalid("role code is required");
        }
        String normalized = code.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("^[A-Z][A-Z0-9_]{1,63}$")) {
            throw BizException.invalid("role code must match ^[A-Z][A-Z0-9_]{1,63}$");
        }
        return normalized;
    }

    private String normalizeScope(String raw) {
        String scope = Texts.blankToNull(raw);
        if (scope == null) {
            throw BizException.invalid("role scope is required (PLATFORM|TENANT)");
        }
        String normalized = scope.trim().toUpperCase(Locale.ROOT);
        if (!SCOPES.contains(normalized)) {
            throw BizException.invalid("role scope must be PLATFORM or TENANT");
        }
        return normalized;
    }

    private List<String> normalizePermissionCodes(List<String> rawCodes) {
        Set<String> codes = new LinkedHashSet<>();
        for (String raw : rawCodes) {
            String code = Texts.blankToNull(raw);
            if (code != null) {
                codes.add(code.toUpperCase(Locale.ROOT));
            }
        }
        List<String> result = new ArrayList<>(codes);
        result.sort(String::compareTo);
        return result;
    }

    private List<String> sortedRoles(TenantScope scope) {
        Set<String> roles = scope == null || scope.roles() == null
                ? new TreeSet<>()
                : new TreeSet<>(scope.roles());
        return new ArrayList<>(roles);
    }

    private TenantScope requireJwtScope() {
        Principal principal = AuthContext.get();
        if (principal == null || !"JWT".equals(principal.authType())) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "JWT authentication required");
        }
        TenantScope scope = TenantContext.get();
        if (scope == null) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "tenant scope is missing");
        }
        return scope;
    }

    private void requirePlatformAdminJwt() {
        TenantScope scope = requireJwtScope();
        if (!scope.isPlatformAdmin()) {
            throw BizException.forbidden("platform administrator role required");
        }
    }

    private void audit(String action, String detail) {
        TenantScope scope = TenantContext.get();
        auditService.record(PLATFORM_AUDIT_TENANT, SecurityScope.actor(scope), action, detail);
    }
}
