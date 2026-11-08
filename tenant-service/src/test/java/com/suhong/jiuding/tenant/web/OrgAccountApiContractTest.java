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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgAccountApiContractTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";

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
    private String auditorScopedToken;
    private String auditorNoTenantToken;
    private String acmeId;
    private String betaId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        operatorToken = bearer(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));
        auditorScopedToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
        auditorNoTenantToken = bearer(new Principal("u-aud2", "auditor2", null, Set.of("AUDITOR"), "JWT"));
        acmeId = createTenant("t-acme", "Acme");
        betaId = createTenant("t-beta", "Beta");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void tenantViewExposesRatingCountersAndOrgTreeContract() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + acmeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ratingGrade").doesNotExist())
                .andExpect(jsonPath("$.data.orgUnitCount").value(0))
                .andExpect(jsonPath("$.data.accountCount").value(0))
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());

        String rootId = createOrg(acmeId, null, "HQ", "Headquarters", "COMPANY");
        String childId = createOrg(acmeId, rootId, "SH", "Shanghai Branch", "BRANCH");

        MvcResult root = mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/org-units/" + rootId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.path").value("/" + rootId))
                .andExpect(jsonPath("$.data.depth").value(0))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.childCount").value(1))
                .andExpect(jsonPath("$.data.accountCount").value(0))
                .andReturn();
        assertThat(read(root).path("data").path("code").asText()).isEqualTo("HQ");

        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/org-units?format=tree")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(rootId))
                .andExpect(jsonPath("$.data[0].children[0].id").value(childId))
                .andExpect(jsonPath("$.data[0].children[0].depth").value(1));

        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/org-units?format=flat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));

        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/org-units?format=nope")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        createAccount(acmeId, "alice", childId, "MEMBER");
        mockMvc.perform(authed(get("/api/tenants/" + acmeId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orgUnitCount").value(2))
                .andExpect(jsonPath("$.data.accountCount").value(1));
    }

    @Test
    void accountCrudNeverExposesPasswordMaterial() throws Exception {
        String orgId = createOrg(acmeId, null, "HQ", "Headquarters", "COMPANY");
        MvcResult created = createAccount(acmeId, "alice", orgId, "MEMBER");
        JsonNode data = read(created).path("data");
        String accountId = data.path("account").path("id").asText();
        assertThat(data.path("account").path("username").asText()).isEqualTo("alice");
        assertThat(data.path("account").path("orgUnitName").asText()).isEqualTo("Headquarters");
        assertThat(data.path("tempPassword").asText()).isNotBlank();
        assertThat(created.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase())
                .doesNotContain("passwordhash", "passwordsalt", "password_hash");

        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].role").value("MEMBER"));
        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/accounts?orgUnitId=" + orgId
                        + "&role=MEMBER&status=ACTIVE&keyword=ali")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(authed(put("/api/tenants/" + acmeId + "/accounts/" + accountId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("displayName", "Alice Renamed", "orgUnitId", orgId,
                                "role", "ORG_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Alice Renamed"))
                .andExpect(jsonPath("$.data.role").value("ORG_ADMIN"));

        mockMvc.perform(authed(post("/api/tenants/" + acmeId + "/accounts/" + accountId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "DISABLED", "reason", "leave"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));

        mockMvc.perform(authed(post("/api/tenants/" + acmeId + "/accounts/" + accountId + "/reset-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempPassword").isNotEmpty());

        mockMvc.perform(authed(delete("/api/tenants/" + acmeId + "/accounts/" + accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/accounts/" + accountId)))
                .andExpect(status().isNotFound());
    }

    @Test
    void crossTenantAndAuditorPermissionsAreEnforced() throws Exception {
        String acmeOrgId = createOrg(acmeId, null, "HQ", "Headquarters", "COMPANY");

        // TENANT_ADMIN of t-acme: own tenant ok, another tenant 403 (never 404).
        mockMvc.perform(get("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + betaId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(post("/api/tenants/" + betaId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "BX", "Beta Unit", "COMPANY")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + betaId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + betaId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // TENANT_ADMIN may write inside its own tenant.
        mockMvc.perform(post("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(acmeOrgId, "ENG", "Engineering", "DEPARTMENT")))
                .andExpect(status().isOk());

        // AUDITOR is read only. A tenant scoped auditor reads its own tenant; the global
        // (no tenant id) auditor reads across tenants (docs/11 section 5) but can never write.
        mockMvc.perform(get("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorScopedToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorScopedToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "NO", "Nope", "TEAM")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorNoTenantToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + betaId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorNoTenantToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorNoTenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "NO2", "Nope 2", "TEAM")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void ratingAssessOverrideAndInternalEndpoints() throws Exception {
        String orgId = createOrg(acmeId, null, "HQ", "Headquarters", "COMPANY");
        createAccount(acmeId, "alice", orgId, "MEMBER");

        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"note\":\"onboarding\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("A"))
                .andExpect(jsonPath("$.data.score").value(78))
                .andExpect(jsonPath("$.data.source").value("SKILL"))
                .andExpect(jsonPath("$.data.dims.business").value(77))
                .andExpect(jsonPath("$.data.dims.compliance").isNumber())
                .andExpect(jsonPath("$.data.dims.financial").isNumber())
                .andExpect(jsonPath("$.data.dims.security").isNumber())
                .andExpect(jsonPath("$.data.dims.service").isNumber())
                .andExpect(jsonPath("$.data.assessedBy").value("u-op"));

        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/ratings/current")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("A"));
        mockMvc.perform(authed(get("/api/tenants/" + acmeId + "/ratings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/override")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("grade", "A", "score", 72, "reason", "audit"))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/override")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("grade", "A", "score", 72, "reason", "audit review"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("MANUAL"))
                .andExpect(jsonPath("$.data.grade").value("A"));

        mockMvc.perform(get("/internal/tenants/" + acmeId + "/accounts/count")
                        .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        mockMvc.perform(get("/internal/tenants/" + acmeId + "/rating")
                        .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").value("A"))
                .andExpect(jsonPath("$.data.score").value(72));
        mockMvc.perform(get("/internal/tenants/" + acmeId + "/accounts/count"))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("tenantCode", code, "name", name,
                                "contactEmail", code + "@acme.io", "plan", "PRO"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createOrg(String tenantId, String parentId, String code, String name, String type)
            throws Exception {
        MvcResult result = mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(parentId, code, name, type)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private MvcResult createAccount(String tenantId, String username, String orgUnitId, String role)
            throws Exception {
        return mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "displayName", username,
                                "email", username + "@acme.io", "orgUnitId", orgUnitId, "role", role))))
                .andExpect(status().isOk())
                .andReturn();
    }

    private String orgJson(String parentId, String code, String name, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        body.put("sortOrder", 0);
        return json(body);
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, adminToken);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
