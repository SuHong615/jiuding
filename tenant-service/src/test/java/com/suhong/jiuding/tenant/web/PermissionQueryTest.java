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
import com.suhong.jiuding.tenant.support.TenantFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Set;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/15 section 7.2: {@code GET /api/me/permissions} resolves the caller permission codes
 * from the DB role matrix; PLATFORM_ADMIN gets the wildcard; platform permission catalogue
 * is limited to platform administrators.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionQueryTest {

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
    private String orgAdminToken;
    private String auditorToken;
    private String memberToken;
    private String globalAuditorToken;

    @BeforeEach
    void setUp() {
        TenantFixtures.clean(jdbc);
        platformToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        orgAdminToken = bearer(new Principal("u-oadmin", "oadmin", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", "org-1"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
        memberToken = bearer(new Principal("u-mem", "member", "t-acme", Set.of("MEMBER"), "JWT", "org-1"));
        globalAuditorToken = bearer(new Principal("u-aud2", "auditor2", null, Set.of("AUDITOR"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void platformAdminGetsTheWildcardPermission() throws Exception {
        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles", contains("PLATFORM_ADMIN")))
                .andExpect(jsonPath("$.data.permissions", contains("*")));
    }

    @Test
    void tenantRolesResolveTheirSeededPermissionMatrix() throws Exception {
        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("TENANT_ADMIN")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ORG_CREATE")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ACCOUNT_UNLOCK")))
                .andExpect(jsonPath("$.data.permissions", hasItem("RATING_ASSESS")))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("RATING_OVERRIDE"))))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("RBAC_MANAGE"))))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("TENANT_DELETE"))));

        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("ORG_ADMIN")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ORG_CREATE")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ACCOUNT_UNLOCK")))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("ACCOUNT_IMPORT"))))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("RATING_ASSESS"))));

        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.permissions", hasItem("TENANT_AUDIT_EXPORT")))
                .andExpect(jsonPath("$.data.permissions", hasItem("ACCOUNT_LOGIN_EVENTS_READ")))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("ORG_CREATE"))))
                .andExpect(jsonPath("$.data.permissions", not(hasItem("ACCOUNT_CREATE"))));

        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("MEMBER")))
                .andExpect(jsonPath("$.data.permissions", contains(
                        "ACCOUNT_CHANGE_PASSWORD", "ACCOUNT_LOGIN_EVENTS_READ", "ACCOUNT_READ", "ORG_READ")));

        // Global auditor (no tenant) can still resolve its read-only permissions.
        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, globalAuditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.roles", contains("AUDITOR")))
                .andExpect(jsonPath("$.data.permissions", hasItem("TENANT_AUDIT_READ")));
    }

    @Test
    void multipleRolesUnionAndUnknownRolesAreIgnored() throws Exception {
        String unionToken = bearer(new Principal("u-union", "union", "t-acme",
                Set.of("MEMBER", "AUDITOR"), "JWT", "org-1"));
        mockMvc.perform(get("/api/me/permissions").header(HttpHeaders.AUTHORIZATION, unionToken))
                .andExpect(status().isOk())
                // member grants the change-password right, auditor grants audit export
                .andExpect(jsonPath("$.data.permissions", hasItem("ACCOUNT_CHANGE_PASSWORD")))
                .andExpect(jsonPath("$.data.permissions", hasItem("TENANT_AUDIT_EXPORT")));

        String unknownToken = bearer(new Principal("u-x", "x", "t-acme", Set.of("CUSTOM_NOPE"), "JWT"));
        MvcResult result = mockMvc.perform(get("/api/me/permissions")
                        .header(HttpHeaders.AUTHORIZATION, unknownToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        assert data.path("roles").size() == 1;
        org.assertj.core.api.Assertions.assertThat(data.path("permissions").size()).isZero();
    }

    @Test
    void platformPermissionCatalogueRequiresPlatformAdminJwt() throws Exception {
        mockMvc.perform(get("/api/platform/permissions").header(HttpHeaders.AUTHORIZATION, platformToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(43))
                .andExpect(jsonPath("$.data[0].code").isNotEmpty())
                .andExpect(jsonPath("$.data[0].groupCode").isNotEmpty());

        mockMvc.perform(get("/api/platform/permissions").header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/platform/permissions").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/permissions"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/platform/permissions").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/me/permissions"))
                .andExpect(status().isUnauthorized());
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
