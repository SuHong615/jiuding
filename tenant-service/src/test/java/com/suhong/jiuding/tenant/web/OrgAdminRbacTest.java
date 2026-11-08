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

/**
 * docs/13 section 3 RBAC matrix for ORG_ADMIN: organization and account management is
 * confined to the caller's {@code org_units.path} subtree, root creation and moves outside
 * the subtree are forbidden, only MEMBER/AUDITOR roles may be granted and ratings are read
 * only.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgAdminRbacTest {

    private static final String PASSWORD = "Passw0rd123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String orgAdminToken;
    private String tenantId;
    private String hqId;
    private String shId;
    private String engId;
    private String eastId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        hqId = createOrg(tenantId, null, "HQ", "Headquarters", "COMPANY");
        shId = createOrg(tenantId, hqId, "SH", "Shanghai Branch", "BRANCH");
        engId = createOrg(tenantId, shId, "ENG", "Engineering", "DEPARTMENT");
        eastId = createOrg(tenantId, null, "EAST", "East Region", "COMPANY");
        createAccount(tenantId, "oa", shId, "ORG_ADMIN");
        createAccount(tenantId, "mem", engId, "MEMBER");
        createAccount(tenantId, "rootmember", hqId, "MEMBER");
        String outId = createAccount(tenantId, "out", eastId, "MEMBER");

        String orgAdminId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'oa'", String.class, tenantId);
        orgAdminToken = bearer(new Principal(orgAdminId, "oa", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", shId));

        mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());

        // Sanity: the outside account is a real row; tests assert 403, never 404.
        assertThat(outId).isNotBlank();
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void orgTreeReadAndWritesStayInsideTheSubtree() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units?format=flat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].id").value(shId));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units?format=tree")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(shId))
                .andExpect(jsonPath("$.data[0].children[0].id").value(engId));

        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units/" + shId)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units/" + hqId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        String createdId = createOrgAsOrgAdmin(engId, "TEAM1", "Team One", "TEAM");
        assertThat(createdId).isNotBlank();

        // Outside the subtree: ancestor, sibling root and (implicitly) root creation.
        postOrg(null, "ROOT2", "Root Two", "COMPANY").andExpect(status().isForbidden());
        postOrg(hqId, "HQSUB", "HQ Sub", "DEPARTMENT").andExpect(status().isForbidden());
        postOrg(eastId, "EASTSUB", "East Sub", "TEAM").andExpect(status().isForbidden());

        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/org-units/" + shId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "SH", "Shanghai Renamed", "BRANCH")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Shanghai Renamed"));
        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/org-units/" + hqId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "HQ", "HQ Renamed", "COMPANY")))
                .andExpect(status().isForbidden());

        // Deleting inside the subtree is allowed, deleting an outside node is 403.
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/org-units/" + createdId)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/org-units/" + hqId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    @Test
    void moveCannotEscapeTheSubtree() throws Exception {
        String tempId = createOrgAsOrgAdmin(shId, "TMP", "Temporary", "TEAM");

        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units/" + tempId + "/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + engId + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.parentId").value(engId))
                .andExpect(jsonPath("$.data.depth").value(3));

        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units/" + tempId + "/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + hqId + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units/" + tempId + "/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());

        // The caller cannot even address nodes outside its subtree.
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units/" + hqId + "/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + shId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void accountListAndCrudAreScopedToTheSubtree() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items[?(@.username=='oa')]").exists())
                .andExpect(jsonPath("$.data.items[?(@.username=='mem')]").exists());
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts?orgUnitId=" + eastId)))
                .andExpect(status().isForbidden());

        // Inside the subtree, MEMBER/AUDITOR grants are fine.
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("eng.member", engId, "MEMBER", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.account.role").value("MEMBER"));
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("eng.auditor", engId, "AUDITOR", null)))
                .andExpect(status().isOk());

        // No org unit (tenant wide) or outside the subtree is forbidden.
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("no.org", null, "MEMBER", PASSWORD)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("east.member", eastId, "MEMBER", PASSWORD)))
                .andExpect(status().isForbidden());

        // Privilege escalation via role grants is forbidden.
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("esc.org", engId, "ORG_ADMIN", PASSWORD)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("esc.tenant", engId, "TENANT_ADMIN", PASSWORD)))
                .andExpect(status().isForbidden());

        // Mutations of accounts outside the subtree are 403, never 404.
        String outId = accountId("out");
        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/accounts/" + outId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Out Renamed", eastId, "MEMBER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + outId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + outId + "/reset-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/accounts/" + outId)))
                .andExpect(status().isForbidden());

        // Mutations inside the subtree are allowed.
        String memId = accountId("mem");
        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/accounts/" + memId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updateJson("Member Renamed", engId, "MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Member Renamed"));
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + memId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISABLED"));
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + memId + "/reset-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tempPassword").isNotEmpty());

        String disposable = createAccount(tenantId, "disposable", engId, "MEMBER");
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/accounts/" + disposable)))
                .andExpect(status().isOk());
    }

    @Test
    void ratingsAreReadOnlyAndTenantLevelWritesAreForbidden() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/ratings")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/ratings/current")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").isNotEmpty());

        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/ratings/assess"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/ratings/override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"AAA\",\"score\":95,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(get("/api/tenants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(put("/api/tenants/" + tenantId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-acme", "Renamed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/submit")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId)))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, orgAdminToken);
    }

    private org.springframework.test.web.servlet.ResultActions postOrg(
            String parentId, String code, String name, String type) throws Exception {
        return mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(orgJson(parentId, code, name, type)));
    }

    private String createOrgAsOrgAdmin(String parentId, String code, String name, String type)
            throws Exception {
        MvcResult result = postOrg(parentId, code, name, type)
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String accountId(String username) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE tenant_id = ? AND username = ?",
                String.class, tenantId, username);
    }

    private String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson(code, name)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createOrg(String tenantId, String parentId, String code, String name, String type)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(parentId, code, name, type)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createAccount(String tenantId, String username, String orgUnitId, String role)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(username, orgUnitId, role, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    private String tenantJson(String code, String name) throws Exception {
        return json(Map.of("tenantCode", code, "name", name,
                "contactEmail", code + "@acme.io", "plan", "PRO"));
    }

    private String orgJson(String parentId, String code, String name, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        return json(body);
    }

    private String accountJson(String username, String orgUnitId, String role, String password)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Test " + username);
        body.put("email", username + "@acme.io");
        if (orgUnitId != null) {
            body.put("orgUnitId", orgUnitId);
        }
        body.put("role", role);
        if (password != null) {
            body.put("initialPassword", password);
        }
        return json(body);
    }

    private String updateJson(String displayName, String orgUnitId, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", displayName);
        body.put("email", displayName.toLowerCase().replace(' ', '.') + "@acme.io");
        if (orgUnitId != null) {
            body.put("orgUnitId", orgUnitId);
        }
        body.put("role", role);
        return json(body);
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
