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
 * docs/11 section 5: the platform preset AUDITOR (no tenant id) is read only across every
 * tenant. Every read is 200, every write is 403.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditorReadOnlyAccessTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String auditorToken;
    private String acmeId;
    private String betaId;
    private String orgId;
    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-auditor", "auditor", null, Set.of("AUDITOR"), "JWT"));
        acmeId = createTenant("t-acme", "Acme");
        betaId = createTenant("t-beta", "Beta");
        orgId = createOrg(acmeId, "HQ", "Headquarters", "COMPANY");
        accountId = createAccount(acmeId, "alice", orgId, "MEMBER");
        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/assess")
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
    void globalAuditorCanReadEveryTenantDomainResource() throws Exception {
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/tenants/" + acmeId).header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t-acme"));
        mockMvc.perform(get("/api/tenants/" + acmeId + "/audits")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + betaId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].code").value("HQ"));
        mockMvc.perform(get("/api/tenants/" + acmeId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/tenants/" + acmeId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/tenants/" + acmeId + "/ratings/current")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.grade").isNotEmpty());
        mockMvc.perform(get("/api/tenants/overview").header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void globalAuditorCannotWriteAnything() throws Exception {
        // tenant lifecycle
        mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-aud-new", "Auditor Created")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(put("/api/tenants/" + acmeId)
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-acme", "Renamed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/submit")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/tenants/" + acmeId).header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isForbidden());
        // organisation / accounts
        mockMvc.perform(post("/api/tenants/" + acmeId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson("NO", "Forbidden", "TEAM")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson("nope", orgId, "MEMBER")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/accounts/" + accountId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + acmeId + "/accounts/" + accountId + "/reset-password")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isForbidden());
        // ratings
        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(post("/api/tenants/" + acmeId + "/ratings/override")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"grade\":\"AAA\",\"score\":95,\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
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

    private String createOrg(String tenantId, String code, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(orgJson(code, name, type)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createAccount(String tenantId, String username, String orgUnitId, String role)
            throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(accountJson(username, orgUnitId, role)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    private String tenantJson(String code, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        return objectMapper.writeValueAsString(body);
    }

    private String orgJson(String code, String name, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        return objectMapper.writeValueAsString(body);
    }

    private String accountJson(String username, String orgUnitId, String role) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Auditor Test User");
        body.put("email", username + "@acme.io");
        body.put("orgUnitId", orgUnitId);
        body.put("role", role);
        return objectMapper.writeValueAsString(body);
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
