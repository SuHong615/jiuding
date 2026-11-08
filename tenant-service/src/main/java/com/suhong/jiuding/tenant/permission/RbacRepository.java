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

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Pure JdbcTemplate persistence for the RBAC tables (docs/15 section 7.1). */
@Repository
public class RbacRepository {

    private static final RowMapper<RoleRow> ROLE_MAPPER = (rs, rowNum) -> new RoleRow(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("scope"),
            rs.getBoolean("builtin"),
            rs.getString("status"),
            rs.getString("description"));

    private static final RowMapper<PermissionRow> PERMISSION_MAPPER = (rs, rowNum) -> new PermissionRow(
            rs.getString("code"),
            rs.getString("name"),
            rs.getString("group_code"),
            rs.getString("description"));

    private static final String ROLE_COLUMNS = "code, name, scope, builtin, status, description";

    private final JdbcTemplate jdbc;

    public RbacRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ permissions

    public List<PermissionRow> listPermissions() {
        return jdbc.query("SELECT code, name, group_code, description FROM permissions"
                + " ORDER BY group_code ASC, code ASC", PERMISSION_MAPPER);
    }

    public Set<String> existingPermissionCodes(Collection<String> codes) {
        Set<String> existing = new LinkedHashSet<>();
        if (codes == null || codes.isEmpty()) {
            return existing;
        }
        List<Object> args = new ArrayList<>(codes);
        String placeholders = String.join(", ", java.util.Collections.nCopies(codes.size(), "?"));
        jdbc.query("SELECT code FROM permissions WHERE code IN (" + placeholders + ")",
                rs -> {
                    existing.add(rs.getString("code"));
                }, args.toArray());
        return existing;
    }

    // ------------------------------------------------------------------ roles

    public List<RoleRow> listRoles(String scope) {
        if (scope == null || scope.isBlank()) {
            return jdbc.query("SELECT " + ROLE_COLUMNS + " FROM roles ORDER BY builtin DESC, code ASC",
                    ROLE_MAPPER);
        }
        return jdbc.query("SELECT " + ROLE_COLUMNS + " FROM roles WHERE scope = ?"
                + " ORDER BY builtin DESC, code ASC", ROLE_MAPPER, scope);
    }

    public Optional<RoleRow> findRole(String code) {
        List<RoleRow> rows = jdbc.query("SELECT " + ROLE_COLUMNS + " FROM roles WHERE code = ?",
                ROLE_MAPPER, code);
        return rows.stream().findFirst();
    }

    public void insertRole(RoleRow row) {
        jdbc.update("INSERT INTO roles (" + ROLE_COLUMNS + ") VALUES (?,?,?,?,?,?)",
                row.code(), row.name(), row.scope(), row.builtin(), row.status(), row.description());
    }

    public void deleteRole(String code) {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ?", code);
        jdbc.update("DELETE FROM roles WHERE code = ?", code);
    }

    // ------------------------------------------------------------------ role_permissions

    public List<String> permissionCodesForRole(String roleCode) {
        return jdbc.query("SELECT permission_code FROM role_permissions WHERE role_code = ?"
                + " ORDER BY permission_code ASC", (rs, rowNum) -> rs.getString(1), roleCode);
    }

    /** Union of permission codes for every known role the caller carries. */
    public List<String> permissionCodesForRoles(Collection<String> roleCodes) {
        if (roleCodes == null || roleCodes.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>(roleCodes);
        String placeholders = String.join(", ", java.util.Collections.nCopies(roleCodes.size(), "?"));
        return jdbc.query("SELECT DISTINCT permission_code FROM role_permissions"
                + " WHERE role_code IN (" + placeholders + ") ORDER BY permission_code ASC",
                (rs, rowNum) -> rs.getString(1), args.toArray());
    }

    public void replaceRolePermissions(String roleCode, List<String> permissionCodes) {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ?", roleCode);
        for (String permissionCode : permissionCodes) {
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code) VALUES (?, ?)",
                    roleCode, permissionCode);
        }
    }
}
