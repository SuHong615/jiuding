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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Account bulk import: one-time temporary passwords, skipping, role escalation, RBAC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountImportTest {

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
    private String tenantCode;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM onboarding_applications");
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantCode = "t-acc-imp-" + (System.nanoTime() % 100000);
        tenantId = createTenant(tenantCode);
        createOrg("HQ", "Headquarters", "COMPANY");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void importsAccountsAndReturnsOneTimeTemporaryPasswords() throws Exception {
        String csv = """
                username,displayName,email,phone,orgCode,role
                alice,Alice Chen,alice@acme.io,13800000001,HQ,MEMBER
                bob,Bob Li,bob@acme.io,13800000002,HQ,AUDITOR
                """;
        JsonNode data = uploadAccounts(csv);
        assertThat(data.path("total").asInt()).isEqualTo(2);
        assertThat(data.path("imported").asInt()).isEqualTo(2);
        assertThat(data.path("skipped").asInt()).isZero();
        assertThat(data.path("failed").asInt()).isZero();
        assertThat(data.path("tempPasswords").size()).isEqualTo(2);
        for (JsonNode row : data.path("tempPasswords")) {
            assertThat(row.path("username").asText()).isNotBlank();
            assertThat(row.path("tempPassword").asText()).hasSizeGreaterThanOrEqualTo(10);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE tenant_id = ?"
                + " AND must_change_password = TRUE", Long.class, tenantId)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM accounts WHERE tenant_id = ?"
                + " AND username IN ('alice','bob')", Long.class, tenantId)).isEqualTo(2);

        JsonNode second = uploadAccounts(csv);
        assertThat(second.path("imported").asInt()).isZero();
        assertThat(second.path("skipped").asInt()).isEqualTo(2);
        assertThat(second.path("tempPasswords").size()).isZero();
    }

    @Test
    void roleEscalationFailsOnlyTheOffendingRow() throws Exception {
        String tenantAdminToken = "Bearer " + jwtService.issue(new Principal(
                "u-op", "operator", tenantId, Set.of("TENANT_ADMIN"), "JWT"));
        String csv = """
                username,displayName,email,orgCode,role
                carol,Carol Wu,carol@acme.io,HQ,MEMBER
                escalate,Escalation User,escalate@acme.io,HQ,TENANT_ADMIN
                """;
        JsonNode data = read(mockMvc.perform(multipart("/api/tenants/" + tenantId + "/accounts/import")
                        .file(csvFile(csv))
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");

        assertThat(data.path("imported").asInt()).isEqualTo(1);
        assertThat(data.path("failed").asInt()).isEqualTo(1);
        assertThat(data.path("errors").get(0).path("row").asInt()).isEqualTo(2);
        assertThat(data.path("errors").get(0).path("message").asText()).contains("TENANT_ADMIN");
        assertThat(data.path("tempPasswords").size()).isEqualTo(1);
    }

    @Test
    void unknownOrgCodeFailsTheRow() throws Exception {
        String csv = """
                username,displayName,orgCode,role
                dave,Dave,NO-SUCH-ORG,MEMBER
                """;
        JsonNode data = uploadAccounts(csv);
        assertThat(data.path("imported").asInt()).isZero();
        assertThat(data.path("failed").asInt()).isEqualTo(1);
        assertThat(data.path("errors").get(0).path("message").asText()).contains("NO-SUCH-ORG");
    }

    @Test
    void orgAdminCannotBulkImportAccounts() throws Exception {
        String orgId = createOrg("ENG", "Engineering", "DEPARTMENT");
        String orgAdminToken = "Bearer " + jwtService.issue(new Principal(
                "org-admin-account", "orgadmin", tenantId, Set.of("ORG_ADMIN"), "JWT", orgId));

        mockMvc.perform(multipart("/api/tenants/" + tenantId + "/accounts/import")
                        .file(csvFile("username,displayName,role\nx1,Forbidden,MEMBER\n"))
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode uploadAccounts(String csv) throws Exception {
        return read(mockMvc.perform(multipart("/api/tenants/" + tenantId + "/accounts/import")
                        .file(csvFile(csv))
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");
    }

    private String createTenant(String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", "Account Import Tenant");
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createOrg(String code, String name, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private MockMultipartFile csvFile(String csv) {
        return new MockMultipartFile("file", "accounts.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** Kept for symmetry with the API surface: template GET is covered by ImportTemplateSecurityTest. */
    @SuppressWarnings("unused")
    private void templatesAreReadable() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/import-templates/accounts.csv")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
    }
}
