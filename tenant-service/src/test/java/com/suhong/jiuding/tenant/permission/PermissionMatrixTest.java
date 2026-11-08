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
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.role.RolePermissionsRequest;
import com.suhong.jiuding.tenant.support.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

/**
 * docs/15 section 7.1: the seeded permission catalogue and built-in role matrix, plus proof
 * that the DB matrix (not a hard-coded mapping) drives {@code /api/me/permissions}.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class PermissionMatrixTest {

    private static final Set<String> EXPECTED_GROUPS = Set.of(
            "TENANT", "ORG", "ACCOUNT", "RATING", "APPROVAL", "SKILL", "AGENT", "MCP",
            "PLATFORM", "RBAC", "ONBOARDING");

    @Autowired
    private RbacService rbacService;
    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void tearDown() {
        TestSecurity.clear();
    }

    @Test
    void permissionCatalogueCoversEveryDocumentedGroup() {
        List<String> groups = jdbc.queryForList(
                "SELECT DISTINCT group_code FROM permissions ORDER BY group_code", String.class);
        assertThat(Set.copyOf(groups)).isEqualTo(EXPECTED_GROUPS);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM permissions", Long.class)).isEqualTo(43L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles", Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM roles WHERE builtin = TRUE", Long.class))
                .isEqualTo(5L);
    }

    @Test
    void builtInRoleMatricesMatchTheDocumentedPolicy() {
        assertThat(permissionsOf("PLATFORM_ADMIN")).isEqualTo(43);
        assertThat(permissionsOf("TENANT_ADMIN")).isEqualTo(24);
        assertThat(permissionsOf("ORG_ADMIN")).isEqualTo(14);
        assertThat(permissionsOf("AUDITOR")).isEqualTo(14);
        assertThat(permissionsOf("MEMBER")).isEqualTo(4);

        // PLATFORM_ADMIN owns every catalogue row.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permissions rp JOIN permissions p"
                        + " ON p.code = rp.permission_code WHERE rp.role_code = 'PLATFORM_ADMIN'",
                Long.class)).isEqualTo(jdbc.queryForObject("SELECT COUNT(*) FROM permissions", Long.class));

        // AUDITOR is read only: no create/update/delete/manage/assess/override/unlock codes.
        Long auditorWrites = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permissions WHERE role_code = 'AUDITOR'"
                        + " AND permission_code IN ('ORG_CREATE','ORG_UPDATE','ORG_DELETE','ACCOUNT_CREATE',"
                        + "'ACCOUNT_UPDATE','ACCOUNT_DELETE','ACCOUNT_UNLOCK','RATING_ASSESS',"
                        + "'RATING_OVERRIDE','RBAC_MANAGE','TENANT_DELETE','PLATFORM_PURGE')",
                Long.class);
        assertThat(auditorWrites).isZero();

        // MEMBER can only read its own account/tree and change its own password.
        List<String> memberPermissions = jdbc.queryForList(
                "SELECT permission_code FROM role_permissions WHERE role_code = 'MEMBER'"
                        + " ORDER BY permission_code", String.class);
        assertThat(memberPermissions).containsExactly(
                "ACCOUNT_CHANGE_PASSWORD", "ACCOUNT_LOGIN_EVENTS_READ", "ACCOUNT_READ", "ORG_READ");
    }

    @Test
    void databaseMatrixDrivesEffectivePermissions() {
        TestSecurity.platformAdmin();
        MePermissionsView admin = rbacService.mePermissions();
        assertThat(admin.roles()).containsExactly("PLATFORM_ADMIN");
        assertThat(admin.permissions()).containsExactly("*");

        rbacService.updatePermissions("MEMBER", new RolePermissionsRequest(List.of("ORG_READ")));

        TestSecurity.apply(new Principal("u-mem", "member", "t-acme",
                Set.of("MEMBER"), "JWT", "org-1"));
        MePermissionsView member = rbacService.mePermissions();
        assertThat(member.permissions()).containsExactly("ORG_READ");

        // A tenant administrator cannot manage the platform matrix.
        TestSecurity.tenantAdmin("t-acme");
        BizException forbidden = catchThrowableOfType(
                () -> rbacService.updatePermissions("MEMBER",
                        new RolePermissionsRequest(List.of("ORG_READ", "ORG_CREATE"))),
                BizException.class);
        assertThat(forbidden).isNotNull();
        assertThat(forbidden.errorCode()).isEqualTo(ErrorCode.FORBIDDEN);
    }

    private long permissionsOf(String roleCode) {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM role_permissions WHERE role_code = ?", Long.class, roleCode);
        return count == null ? 0L : count;
    }
}
