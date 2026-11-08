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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/14 section 3.2: PII masking on the account list/detail ({@code mask=true}) and the
 * mandatory masking for auditors.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountMaskingTest {

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
    private String tenantId;
    private String accountId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        String orgId = createOrg(tenantId, "HQ", "Headquarters");
        accountId = createAccount(tenantId, "alice", orgId);
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void maskQueryParameterMasksEmailAndPhone() throws Exception {
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("alice@acme.io"))
                .andExpect(jsonPath("$.data.items[0].phone").value("13812341234"));

        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts?mask=true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("a***@acme.io"))
                .andExpect(jsonPath("$.data.items[0].phone").value("138****1234"));

        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts/" + accountId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("alice@acme.io"))
                .andExpect(jsonPath("$.data.phone").value("13812341234"));
        mockMvc.perform(authed(get("/api/tenants/" + tenantId + "/accounts/" + accountId + "?mask=true")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("a***@acme.io"))
                .andExpect(jsonPath("$.data.phone").value("138****1234"));
    }

    @Test
    void auditorIsAlwaysMaskedEvenWithoutTheFlag() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("a***@acme.io"))
                .andExpect(jsonPath("$.data.items[0].phone").value("138****1234"));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts/" + accountId)
                        .header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("a***@acme.io"))
                .andExpect(jsonPath("$.data.phone").value("138****1234"));

        // Tenant administrator keeps the unmasked view unless mask=true is requested.
        String tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("alice@acme.io"));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts?mask=true")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].email").value("a***@acme.io"));
    }

    // ------------------------------------------------------------------ helpers

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, adminToken);
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

    private String createOrg(String tenantId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code, "name", name, "type", "COMPANY"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createAccount(String tenantId, String username, String orgUnitId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Alice");
        body.put("email", "alice@acme.io");
        body.put("phone", "13812341234");
        body.put("orgUnitId", orgUnitId);
        body.put("role", "MEMBER");
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
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
