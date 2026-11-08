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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/13 section 2.2: tenant account authentication used by the gateway login chain.
 * Covers success, uniform 401 failures, 5-attempt lock, disabled accounts and non ACTIVE
 * tenants, plus the login audit trail (never any password material).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountAuthenticationInternalTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";
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
    private String acmeId;
    private String acmeOrgId;
    private String aliceId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        acmeId = createTenant("t-acme", "Acme");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", acmeId);
        acmeOrgId = createOrg(acmeId, "HQ", "Headquarters", "COMPANY");
        aliceId = createAccount(acmeId, "alice", acmeOrgId, "MEMBER", PASSWORD);
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void successfulLoginReturnsPrincipalAndUpdatesLoginState() throws Exception {
        MvcResult result = authenticate("t-acme", "alice", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.accountId").value(aliceId))
                .andExpect(jsonPath("$.data.tenantId").value(acmeId))
                .andExpect(jsonPath("$.data.tenantCode").value("t-acme"))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.displayName").value("Alice Member"))
                .andExpect(jsonPath("$.data.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.orgUnitId").value(acmeOrgId))
                .andExpect(jsonPath("$.data.orgUnitPath").value("/" + acmeOrgId))
                .andExpect(jsonPath("$.data.mustChangePassword").value(false))
                .andReturn();

        String body = result.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase();
        assertThat(body).doesNotContain("passwordhash", "passwordsalt", "password_hash", PASSWORD.toLowerCase());

        Map<String, Object> state = jdbc.queryForMap(
                "SELECT failed_attempts, last_login_at FROM accounts WHERE id = ?", aliceId);
        assertThat(((Number) state.get("failed_attempts")).intValue()).isZero();
        assertThat(state.get("last_login_at")).isNotNull();
        assertThat(auditCount(acmeId, "LOGIN_SUCCESS")).isEqualTo(1);
    }

    @Test
    void wrongPasswordCountsFailuresAndLocksAfterFiveAttempts() throws Exception {
        for (int attempt = 1; attempt <= 4; attempt++) {
            authenticate("t-acme", "alice", "WrongPassw0rd")
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.code").value(40100))
                    .andExpect(jsonPath("$.message").value("invalid credentials"));
        }
        assertThat(accountColumn("failed_attempts")).isEqualTo(4);
        assertThat(accountStatus()).isEqualTo("ACTIVE");

        authenticate("t-acme", "alice", "WrongPassw0rd")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        assertThat(accountColumn("failed_attempts")).isEqualTo(5);
        assertThat(accountStatus()).isEqualTo("LOCKED");
        assertThat(auditCount(acmeId, "ACCOUNT_LOCKED")).isEqualTo(1);
        assertThat(auditCount(acmeId, "LOGIN_FAILED")).isEqualTo(5);

        // Even the correct password no longer works while the account is LOCKED.
        authenticate("t-acme", "alice", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));

        mockMvc.perform(post("/api/tenants/" + acmeId + "/accounts/" + aliceId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        authenticate("t-acme", "alice", PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(aliceId));
        assertThat(accountColumn("failed_attempts")).isZero();
    }

    @Test
    void disabledAccountAndNonActiveTenantFailWithTheSame401() throws Exception {
        mockMvc.perform(post("/api/tenants/" + acmeId + "/accounts/" + aliceId + "/status")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        authenticate("t-acme", "alice", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("invalid credentials"));

        String suspendedId = createTenant("t-suspended", "Suspended");
        jdbc.update("UPDATE tenants SET status = 'SUSPENDED' WHERE id = ?", suspendedId);
        createAccount(suspendedId, "bob", null, "MEMBER", PASSWORD);
        authenticate("t-suspended", "bob", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));

        String deletedId = createTenant("t-deleted", "Deleted");
        createAccount(deletedId, "carol", null, "MEMBER", PASSWORD);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/tenants/" + deletedId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        authenticate("t-deleted", "carol", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));
    }

    @Test
    void unknownTenantAndUnknownAccountAreIndistinguishableAndAudited() throws Exception {
        authenticate("t-nope", "alice", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("invalid credentials"));

        authenticate("t-acme", "nobody", PASSWORD)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));
        assertThat(auditCount(acmeId, "LOGIN_FAILED")).isEqualTo(1);
    }

    @Test
    void internalTokenIsMandatory() throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "t-acme");
        body.put("username", "alice");
        body.put("password", PASSWORD);
        String json = objectMapper.writeValueAsString(body);

        mockMvc.perform(post("/internal/auth/authenticate")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/authenticate")
                        .header(Headers.INTERNAL_TOKEN, "wrong-token")
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private org.springframework.test.web.servlet.ResultActions authenticate(
            String tenantCode, String username, String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("username", username);
        body.put("password", password);
        return mockMvc.perform(post("/internal/auth/authenticate")
                .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long auditCount(String tenantId, String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = ?", Long.class, tenantId, action);
        return count == null ? 0L : count;
    }

    private long accountColumn(String column) {
        Number value = jdbc.queryForObject("SELECT " + column + " FROM accounts WHERE id = ?",
                Number.class, aliceId);
        return value == null ? 0L : value.longValue();
    }

    private String accountStatus() {
        return jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, aliceId);
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

    private String createOrg(String tenantId, String code, String name, String type) throws Exception {
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

    private String createAccount(String tenantId, String username, String orgUnitId, String role,
                                 String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "Alice Member");
        body.put("email", username + "@acme.io");
        if (orgUnitId != null) {
            body.put("orgUnitId", orgUnitId);
        }
        body.put("role", role);
        body.put("initialPassword", password);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
