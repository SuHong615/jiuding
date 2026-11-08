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
 * docs/14 section 3.1: account unlock (LOCKED-&gt;ACTIVE, attempts reset, audited, scoped to
 * platform / tenant admin / ORG_ADMIN subtree) and self-service password change (owner only,
 * old password + policy checked, must_change_password cleared, audited).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountSecurityOperationsTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";
    private static final String OLD_PASSWORD = "OldPassw0rd1";

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
    private String orgAdminToken;
    private String tenantId;
    private String hqId;
    private String eastId;
    private String aliceId;
    private String bobId;
    private String eveId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        hqId = createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        eastId = createOrg(tenantId, "EAST", "East", "COMPANY");
        aliceId = createAccount(tenantId, "alice", hqId, "MEMBER", OLD_PASSWORD).path("account").path("id").asText();
        bobId = createAccount(tenantId, "bob", hqId, "MEMBER", OLD_PASSWORD).path("account").path("id").asText();
        eveId = createAccount(tenantId, "eve", eastId, "MEMBER", OLD_PASSWORD).path("account").path("id").asText();
        JsonNode orgAdmin = createAccount(tenantId, "oa", hqId, "ORG_ADMIN", OLD_PASSWORD);
        String orgAdminId = orgAdmin.path("account").path("id").asText();

        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        orgAdminToken = bearer(new Principal(orgAdminId, "oa", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", hqId));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void lockedAccountCanBeUnlockedWithAuditAndAttemptsReset() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            authenticate("alice", "WrongPassw0rd").andExpect(status().isUnauthorized());
        }
        assertThat(accountColumn("alice", "status")).isEqualTo("LOCKED");
        assertThat(accountColumn("alice", "failed_attempts")).isEqualTo("5");

        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + aliceId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.failedAttempts").value(0));
        assertThat(auditCount("ACCOUNT_UNLOCKED")).isEqualTo(1);

        authenticate("alice", OLD_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(aliceId));

        // Idempotent: unlocking an already ACTIVE account is a no-op 200.
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + aliceId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
    }

    @Test
    void unlockRequiresPrivilegeAndOnlyWorksForLockedAccounts() throws Exception {
        jdbc.update("UPDATE accounts SET status = 'LOCKED', failed_attempts = 5 WHERE id = ?", bobId);
        jdbc.update("UPDATE accounts SET status = 'LOCKED', failed_attempts = 5 WHERE id = ?", eveId);

        String aliceToken = bearer(new Principal(aliceId, "alice", "t-acme", Set.of("MEMBER"), "JWT", hqId));
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + bobId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, aliceToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // ORG_ADMIN may unlock inside its subtree (HQ) but not another root (EAST).
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + bobId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + eveId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden());

        // Non LOCKED states are a conflict; platform admin can unlock any account.
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + bobId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", bobId);
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + bobId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + eveId + "/unlock")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/" + eveId + "/unlock"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePasswordIsOwnerOnlyAndEnforcesOldPasswordAndPolicy() throws Exception {
        String aliceToken = bearer(new Principal(aliceId, "alice", "t-acme", Set.of("MEMBER"), "JWT", hqId));
        String bobToken = bearer(new Principal(bobId, "bob", "t-acme", Set.of("MEMBER"), "JWT", hqId));

        mockMvc.perform(post(changePasswordUrl(aliceId))
                        .header(HttpHeaders.AUTHORIZATION, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody("WrongPassw0rd", "NewPassw0rd2")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(post(changePasswordUrl(aliceId))
                        .header(HttpHeaders.AUTHORIZATION, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "short1")))
                .andExpect(status().isBadRequest());

        // Another member and even a tenant admin cannot change someone else's password.
        mockMvc.perform(post(changePasswordUrl(aliceId))
                        .header(HttpHeaders.AUTHORIZATION, bobToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "NewPassw0rd2")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(changePasswordUrl(aliceId))
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "NewPassw0rd2")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post(changePasswordUrl(aliceId))
                        .header(HttpHeaders.AUTHORIZATION, aliceToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(OLD_PASSWORD, "NewPassw0rd2")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
        assertThat(auditCount("PASSWORD_CHANGED")).isEqualTo(1);

        authenticate("alice", OLD_PASSWORD).andExpect(status().isUnauthorized());
        authenticate("alice", "NewPassw0rd2")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(aliceId));
    }

    @Test
    void changePasswordClearsMustChangePasswordFlag() throws Exception {
        JsonNode created = createAccount(tenantId, "temp", hqId, "MEMBER", null);
        String tempId = created.path("account").path("id").asText();
        String tempPassword = created.path("tempPassword").asText();
        assertThat(tempPassword).isNotBlank();
        assertThat(created.path("account").path("mustChangePassword").asBoolean()).isTrue();

        authenticate("temp", tempPassword)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        String tempToken = bearer(new Principal(tempId, "temp", "t-acme", Set.of("MEMBER"), "JWT", hqId));
        mockMvc.perform(post(changePasswordUrl(tempId))
                        .header(HttpHeaders.AUTHORIZATION, tempToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(changeBody(tempPassword, "FreshPassw0rd9")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));

        authenticate("temp", tempPassword).andExpect(status().isUnauthorized());
        authenticate("temp", "FreshPassw0rd9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mustChangePassword").value(false));
    }

    // ------------------------------------------------------------------ helpers

    private String changePasswordUrl(String accountId) {
        return "/api/tenants/" + tenantId + "/accounts/" + accountId + "/change-password";
    }

    private String changeBody(String oldPassword, String newPassword) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("oldPassword", oldPassword);
        body.put("newPassword", newPassword);
        return objectMapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.ResultActions authenticate(String username, String password)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "t-acme");
        body.put("username", username);
        body.put("password", password);
        return mockMvc.perform(post("/internal/auth/authenticate")
                .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN)
                .header(HttpHeaders.USER_AGENT, "security-test")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String accountColumn(String username, String column) {
        return String.valueOf(jdbc.queryForObject("SELECT " + column + " FROM accounts"
                + " WHERE tenant_id = ? AND username = ?", Object.class, tenantId, username));
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = ?", Long.class, tenantId, action);
        return count == null ? 0L : count;
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

    private String createOrg(String tenantId, String code, String name, String type) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", code, "name", name, "type", type))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private JsonNode createAccount(String tenantId, String username, String orgUnitId, String role,
                                   String password) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        body.put("email", username + "@acme.io");
        body.put("phone", "13812341234");
        body.put("orgUnitId", orgUnitId);
        body.put("role", role);
        if (password != null) {
            body.put("initialPassword", password);
        }
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data");
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
