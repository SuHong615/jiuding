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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/13 section 3 RBAC matrix for MEMBER: read only its own account and its own org
 * subtree, every write is 403 and ratings / tenant level resources are inaccessible.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MemberReadOnlyAccessTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String memberToken;
    private String tenantId;
    private String hqId;
    private String shId;
    private String engId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        hqId = createOrg(tenantId, null, "HQ", "Headquarters", "COMPANY");
        shId = createOrg(tenantId, hqId, "SH", "Shanghai", "BRANCH");
        engId = createOrg(tenantId, shId, "ENG", "Engineering", "DEPARTMENT");
        createAccount("mem", engId, "MEMBER");
        createAccount("other", shId, "MEMBER");

        String memberAccountId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'mem'", String.class, tenantId);
        memberToken = bearer(new Principal(memberAccountId, "mem", "t-acme",
                Set.of("MEMBER"), "JWT", engId));

        mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void memberOnlyReadsItsOwnOrgSubtree() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units?format=flat")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(engId));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units?format=tree")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(engId))
                .andExpect(jsonPath("$.data[0].children.length()").value(0));

        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units/" + engId)))
                .andExpect(status().isOk());
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units/" + shId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/org-units/" + hqId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberWritesAreAllForbidden() throws Exception {
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(engId, "NO", "Nope", "TEAM")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/org-units/" + engId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(null, "ENG", "Renamed", "DEPARTMENT")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/org-units/" + engId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/org-units/" + engId + "/move"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"parentId\":\"" + shId + "\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberAccountListAndDetailOnlyExposeItself() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("mem"));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts?keyword=other")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts?role=MEMBER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        String memId = accountId("mem");
        String otherId = accountId("other");
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts/" + memId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("mem"));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts/" + otherId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("newbie", engId, "MEMBER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(put("/api/tenants/" + tenantId + "/accounts/" + memId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Nope\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + memId + "/status"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/accounts/" + memId + "/reset-password"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(delete("/api/tenants/" + tenantId + "/accounts/" + otherId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberCannotReadRatingsOrTenantLevelResources() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/ratings")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/ratings/current")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/ratings/assess"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(post("/api/tenants/" + tenantId + "/ratings/override"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"AAA\",\"score\":95,\"reason\":\"x\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(authed(get("/api/tenants")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/tenants/" + tenantId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/tenants/overview")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/audits")))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, memberToken);
    }

    private String accountId(String username) {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE tenant_id = ? AND username = ?",
                String.class, tenantId, username);
    }

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
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(parentId, code, name, type)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createAccount(String username, String orgUnitId, String role) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(username, orgUnitId, role)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
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

    private String accountJson(String username, String orgUnitId, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Member " + username);
        body.put("email", username + "@acme.io");
        body.put("orgUnitId", orgUnitId);
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
