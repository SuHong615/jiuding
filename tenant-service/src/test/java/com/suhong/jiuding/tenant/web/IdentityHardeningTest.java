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
import com.suhong.jiuding.common.security.Headers;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Regression tests for the identity-domain hardening round (task-12):
 * API-key cannot delete tenants, internal endpoints are consistent for soft deleted tenants
 * and a soft deleted account is removed as org manager.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class IdentityHardeningTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";
    private static final String MCP_API_KEY = "dev-mcp-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() {
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void apiKeyCannotDeleteTenantsButPlatformAdminJwtCan() throws Exception {
        String id = createTenant("t-apikey-delete", "ApiKey Delete");

        mockMvc.perform(delete("/api/tenants/" + id).header("X-API-Key", MCP_API_KEY))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());
    }

    @Test
    void internalRatingAndAccountCountReturn404ForSoftDeletedTenant() throws Exception {
        String id = createTenant("t-soft-internal", "Soft Deleted Internal");
        mockMvc.perform(post("/api/tenants/" + id + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/internal/tenants/" + id).header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/internal/tenants/" + id + "/rating")
                        .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(get("/internal/tenants/" + id + "/accounts/count")
                        .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void softDeletingManagerAccountClearsOrgManagerReference() throws Exception {
        String tenantId = createTenant("t-manager", "Manager Tenant");
        String accountId = createAccount(tenantId, "manager.user", "ORG_ADMIN");
        String orgId = createOrg(tenantId, "MGR", "Managed Org", accountId);

        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units/" + orgId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.managerAccountId").value(accountId))
                .andExpect(jsonPath("$.data.managerName").value("Manager User"));

        mockMvc.perform(delete("/api/tenants/" + tenantId + "/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units/" + orgId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.managerAccountId").doesNotExist())
                .andExpect(jsonPath("$.data.managerName").doesNotExist());

        assertThat(jdbc.queryForObject(
                "SELECT manager_account_id FROM org_units WHERE id = ?", String.class, orgId)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'ACCOUNT_DELETED' AND detail LIKE '%cleared 1 org manager%'",
                Long.class, tenantId)).isEqualTo(1);
    }

    private String createTenant(String code, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
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

    private String createAccount(String tenantId, String username, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Manager User");
        body.put("email", username + "@acme.io");
        body.put("role", role);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    private String createOrg(String tenantId, String code, String name, String managerAccountId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", "DEPARTMENT");
        body.put("managerAccountId", managerAccountId);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
