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

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantIsolationTest {

    private static final String API_KEY = "dev-mcp-key";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String operatorToken;
    private String auditorToken;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM tenant_audits");
        jdbc.update("DELETE FROM collect_jobs");
        jdbc.update("DELETE FROM tenants");
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        operatorToken = bearer(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-auditor", "auditor", null, Set.of("AUDITOR"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void tenantUserOnlySeesOwnTenant() throws Exception {
        String acmeId = createTenant("t-acme", "Acme");
        createTenant("t-beta", "Beta");

        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        MvcResult own = mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("t-acme"))
                .andReturn();
        assertThat(read(own).path("data").path("items").size()).isEqualTo(1);

        mockMvc.perform(get("/api/tenants/" + acmeId).header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(acmeId));

        mockMvc.perform(get("/api/tenants/not-a-real-id").header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void tenantUserCannotReadOrWriteAnotherTenant() throws Exception {
        String acmeId = createTenant("t-acme", "Acme");
        String betaId = createTenant("t-beta", "Beta");

        mockMvc.perform(get("/api/tenants/" + betaId).header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(put("/api/tenants/" + betaId)
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-beta", "Beta Renamed")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(put("/api/tenants/" + acmeId)
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-acme", "Acme Renamed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Acme Renamed"));
    }

    @Test
    void tenantUserCannotCreateTenants() throws Exception {
        mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-new", "New")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(post("/api/tenants/collect")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceName\":\"x\",\"records\":[{\"tenantCode\":\"t-new\",\"name\":\"New\"}]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void machineApiKeyHasPlatformWideAccess() throws Exception {
        createTenant("t-acme", "Acme");
        createTenant("t-beta", "Beta");

        mockMvc.perform(get("/api/tenants").header("X-API-Key", API_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void missingOrInsufficientCredentialsFailClosed() throws Exception {
        createTenant("t-acme", "Acme");

        mockMvc.perform(get("/api/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // AUDITOR without a tenant scope is read only across tenants (docs/11 section 5).
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-auditor-created", "Forbidden")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // X-Tenant-Id must match the JWT tenant for non platform admins.
        mockMvc.perform(get("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .header("X-Tenant-Id", "t-beta"))
                .andExpect(status().isForbidden());
    }

    @Test
    void overviewIsScopedToCaller() throws Exception {
        createTenant("t-acme", "Acme");
        createTenant("t-beta", "Beta");

        mockMvc.perform(get("/api/tenants/overview").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        mockMvc.perform(get("/api/tenants/overview").header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.byStatus.DRAFT").value(1));
    }

    private String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson(code, name)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String tenantJson(String code, String name) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "tenantCode", code,
                "name", name,
                "contactEmail", code + "@acme.io",
                "plan", "PRO"));
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
