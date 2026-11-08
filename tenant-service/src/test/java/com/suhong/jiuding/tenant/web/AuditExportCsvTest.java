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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/14 section 3.2: compliance audit CSV export (range filter, RFC4180 escaping, 10000 row
 * cap, attachment header) restricted to platform administrators, tenant administrators and
 * auditors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditExportCsvTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String tenantAdminToken;
    private String auditorToken;
    private String globalAuditorToken;
    private String orgAdminToken;
    private String memberToken;
    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        String orgId = createOrg(tenantId, "HQ", "Headquarters");
        String orgAdminId = createAccount(tenantId, "oa", orgId, "ORG_ADMIN");
        String memberId = createAccount(tenantId, "mem", orgId, "MEMBER");

        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
        globalAuditorToken = bearer(new Principal("u-aud2", "auditor2", null, Set.of("AUDITOR"), "JWT"));
        orgAdminToken = bearer(new Principal(orgAdminId, "oa", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", orgId));
        memberToken = bearer(new Principal(memberId, "mem", "t-acme", Set.of("MEMBER"), "JWT", orgId));

        // Known audit trail: replace the creation audit with deterministic fixtures.
        jdbc.update("DELETE FROM tenant_audits WHERE tenant_id = ?", tenantId);
        Instant now = Instant.now();
        insertAudit(now.minus(2, ChronoUnit.HOURS), "admin", "CREATED", "plain");
        insertAudit(now.minus(1, ChronoUnit.HOURS), "ops", "UPDATED", "note, with comma");
        insertAudit(now.minus(30, ChronoUnit.MINUTES), "ops", "UPDATED", "quote \"inside\"");
        insertAudit(now.minus(40, ChronoUnit.DAYS), "legacy", "ARCHIVED", "old-row");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void exportProducesEscapedAttachmentCsvWithDefaultWindow() throws Exception {
        MvcResult result = mockMvc.perform(get(exportUrl())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();

        assertThat(result.getResponse().getContentType()).startsWith("text/csv");
        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment").contains(".csv");

        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).startsWith("at,actor,action,detail\n");
        assertThat(csv).contains("\"note, with comma\"");
        assertThat(csv).contains("\"quote \"\"inside\"\"\"");
        assertThat(csv).contains("plain");
        // Default window is the last 30 days, so the 40 day old row is excluded.
        assertThat(csv).doesNotContain("old-row");
    }

    @Test
    void exportHonoursIsoRangeFilters() throws Exception {
        Instant now = Instant.now();
        String from = now.minus(90, ChronoUnit.MINUTES).toString();
        String to = now.minus(45, ChronoUnit.MINUTES).toString();

        MvcResult result = mockMvc.perform(get(exportUrl())
                        .param("from", from)
                        .param("to", to)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();
        String csv = result.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("note, with comma");
        assertThat(csv).doesNotContain("plain");
        assertThat(csv).doesNotContain("quote \"\"inside\"\"");
    }

    @Test
    void exportRequiresComplianceRoleAndCsvFormat() throws Exception {
        mockMvc.perform(get(exportUrl()).header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(exportUrl()).header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(exportUrl()).header(HttpHeaders.AUTHORIZATION, globalAuditorToken))
                .andExpect(status().isOk());

        mockMvc.perform(get(exportUrl()).header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get(exportUrl()).header(HttpHeaders.AUTHORIZATION, memberToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(exportUrl()))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get(exportUrl()).param("format", "xlsx")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(get(exportUrl()).param("from", "not-a-timestamp")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String exportUrl() {
        return "/api/tenants/" + tenantId + "/audits/export";
    }

    private void insertAudit(Instant at, String actor, String action, String detail) {
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail) VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId, LocalDateTime.ofInstant(at, ZoneOffset.UTC),
                actor, action, detail);
    }

    private String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantCode", code, "name", name,
                                "contactEmail", code + "@acme.io", "plan", "PRO"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private String createOrg(String tenantId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code, "name", name, "type", "COMPANY"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private String createAccount(String tenantId, String username, String orgUnitId, String role)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        body.put("email", username + "@acme.io");
        body.put("orgUnitId", orgUnitId);
        body.put("role", role);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("account").path("id").asText();
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
