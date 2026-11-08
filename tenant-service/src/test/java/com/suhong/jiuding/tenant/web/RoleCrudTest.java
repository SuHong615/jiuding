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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/15 section 7.2: platform role management CRUD + permission matrix updates, RBAC
 * enforcement and the ROLE_CREATED/ROLE_UPDATED/ROLE_DELETED audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class RoleCrudTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String platformToken;
    private String tenantAdminToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM tenant_audits WHERE tenant_id = 'platform'");
        platformToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void listsBuiltInRolesWithTheirPermissionMatrices() throws Exception {
        MvcResult result = mockMvc.perform(authed(get("/api/platform/roles")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode roles = read(result).path("data");
        assertThat(roles.size()).isEqualTo(5);

        JsonNode platformAdmin = findRole(roles, "PLATFORM_ADMIN");
        assertThat(platformAdmin.path("builtin").asBoolean()).isTrue();
        assertThat(platformAdmin.path("scope").asText()).isEqualTo("PLATFORM");
        assertThat(platformAdmin.path("permissionCodes").size()).isEqualTo(43);

        JsonNode tenantAdmin = findRole(roles, "TENANT_ADMIN");
        assertThat(tenantAdmin.path("permissionCodes").size()).isEqualTo(24);
        JsonNode member = findRole(roles, "MEMBER");
        assertThat(member.path("permissionCodes").size()).isEqualTo(4);

        mockMvc.perform(authed(get("/api/platform/roles?scope=TENANT")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(4));
        mockMvc.perform(authed(get("/api/platform/roles?scope=PLATFORM")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
        mockMvc.perform(authed(get("/api/platform/roles?scope=WRONG")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void createsCustomRoleAndValidatesInput() throws Exception {
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("custom_ops", "自定义运营", "tenant", "ops team"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("CUSTOM_OPS"))
                .andExpect(jsonPath("$.data.builtin").value(false))
                .andExpect(jsonPath("$.data.scope").value("TENANT"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.permissionCodes.length()").value(0));

        // Codes are case-insensitively unique.
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_OPS", "重复", "TENANT", null))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("1bad", "非法", "TENANT", null))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_X", "非法域", "GLOBAL", null))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_Y", "", "TENANT", null))))
                .andExpect(status().isBadRequest());

        assertThat(auditCount("ROLE_CREATED")).isEqualTo(1);
    }

    @Test
    void permissionMatrixUpdateIsPersistedAndEffective() throws Exception {
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_READER", "只读角色", "TENANT", null))))
                .andExpect(status().isOk());

        mockMvc.perform(authed(put("/api/platform/roles/CUSTOM_READER/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("ORG_READ", "ACCOUNT_READ")))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissionCodes.length()").value(2))
                .andExpect(jsonPath("$.data.permissionCodes[0]").value("ACCOUNT_READ"))
                .andExpect(jsonPath("$.data.permissionCodes[1]").value("ORG_READ"));

        MvcResult roles = mockMvc.perform(authed(get("/api/platform/roles?scope=TENANT")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode custom = findRole(read(roles).path("data"), "CUSTOM_READER");
        assertThat(custom.path("permissionCodes").size()).isEqualTo(2);

        // Unknown permission codes are rejected without touching the matrix.
        mockMvc.perform(authed(put("/api/platform/roles/CUSTOM_READER/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("ORG_READ", "NOPE_CODE")))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(authed(put("/api/platform/roles/NOT_A_ROLE/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("ORG_READ")))))
                .andExpect(status().isNotFound());

        // Built-in roles may be adjusted by a platform administrator and the change is
        // immediately visible through /api/me/permissions for that role.
        mockMvc.perform(authed(put("/api/platform/roles/TENANT_ADMIN/permissions"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("TENANT_READ")))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions.length()").value(1))
                .andExpect(jsonPath("$.data.permissions[0]").value("TENANT_READ"));
        assertThat(auditCount("ROLE_UPDATED")).isEqualTo(2);
    }

    @Test
    void deletesOnlyCustomRolesAndAuditsTheChange() throws Exception {
        mockMvc.perform(authed(post("/api/platform/roles"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_TMP", "临时角色", "TENANT", null))))
                .andExpect(status().isOk());

        mockMvc.perform(authed(delete("/api/platform/roles/CUSTOM_TMP")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        MvcResult roles = mockMvc.perform(authed(get("/api/platform/roles")))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(findRoleOrNull(read(roles).path("data"), "CUSTOM_TMP")).isNull();
        assertThat(auditCount("ROLE_DELETED")).isEqualTo(1);

        mockMvc.perform(authed(delete("/api/platform/roles/PLATFORM_ADMIN")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
        mockMvc.perform(authed(delete("/api/platform/roles/NOT_A_ROLE")))
                .andExpect(status().isNotFound());
    }

    @Test
    void nonPlatformCallersCannotManageRoles() throws Exception {
        mockMvc.perform(get("/api/platform/roles").header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(post("/api/platform/roles")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(roleBody("CUSTOM_HACK", "越权", "TENANT", null))))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/platform/roles/MEMBER/permissions")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("permissionCodes", List.of("RBAC_MANAGE")))))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/platform/roles/MEMBER").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/roles"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, platformToken);
    }

    private Map<String, Object> roleBody(String code, String name, String scope, String description) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("scope", scope);
        if (description != null) {
            body.put("description", description);
        }
        return body;
    }

    private JsonNode findRole(JsonNode roles, String code) {
        JsonNode role = findRoleOrNull(roles, code);
        assertThat(role).as("role %s", code).isNotNull();
        return role;
    }

    private JsonNode findRoleOrNull(JsonNode roles, String code) {
        for (JsonNode role : roles) {
            if (code.equals(role.path("code").asText())) {
                return role;
            }
        }
        return null;
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = 'platform' AND action = ?", Long.class, action);
        return count == null ? 0L : count;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
