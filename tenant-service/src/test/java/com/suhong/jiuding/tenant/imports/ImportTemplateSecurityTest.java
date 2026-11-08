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
package com.suhong.jiuding.tenant.imports;

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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSV template download + import endpoint permission matrix (docs/15 section 5). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportTemplateSecurityTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String tenantId;
    private String otherTenantId;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM onboarding_applications");
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-tpl-" + (System.nanoTime() % 100000));
        otherTenantId = createTenant("t-tpl-other-" + (System.nanoTime() % 100000));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void platformAdminCanDownloadBothTemplates() throws Exception {
        MvcResult org = mockMvc.perform(get("/api/tenants/" + tenantId
                        + "/import-templates/org-units.csv")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(org.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment");
        String orgCsv = org.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(org.getResponse().getContentType()).contains("text/csv");
        assertThat(orgCsv).contains("parentCode", "code", "name", "type");

        MvcResult accounts = mockMvc.perform(get("/api/tenants/" + tenantId
                        + "/import-templates/accounts.csv")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(accounts.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("username", "displayName", "orgCode", "role");
    }

    @Test
    void unauthenticatedAndOrgAdminTemplateDownloadIsRejected() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/import-templates/org-units.csv"))
                .andExpect(status().isUnauthorized());

        String orgId = createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        String orgAdminToken = "Bearer " + jwtService.issue(new Principal(
                "org-admin-account", "orgadmin", tenantId, Set.of("ORG_ADMIN"), "JWT", orgId));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/import-templates/org-units.csv")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void tenantAdminCannotImportIntoAnotherTenant() throws Exception {
        String tenantAdminToken = "Bearer " + jwtService.issue(new Principal(
                "u-op", "operator", tenantId, Set.of("TENANT_ADMIN"), "JWT"));

        mockMvc.perform(get("/api/tenants/" + tenantId + "/import-templates/org-units.csv")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tenants/" + otherTenantId + "/import-templates/org-units.csv")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ------------------------------------------------------------------ helpers

    private String createTenant(String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", "Template Tenant");
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private String createOrg(String targetTenantId, String code, String name, String type)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + targetTenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }
}
