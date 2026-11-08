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
package com.suhong.jiuding.tenant.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/** Shared fixtures for the MFA (v6) web tests. */
abstract class AbstractMfaWebTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected TotpService totpService;

    protected String adminToken;
    protected String tenantId;

    protected void cleanMfaDatabase() {
        TenantFixtures.clean(jdbc);
        jdbc.update("DELETE FROM account_mfa");
        jdbc.update("DELETE FROM mfa_challenges");
    }

    protected void initAdminToken() {
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    protected String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", code, "name", name,
                                "contactEmail", code + "@acme.io", "plan", "PRO"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    protected String createOrg(String tenantId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", name, "type", "COMPANY"))))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    protected String createAccount(String tenantId, String username, String password, String orgUnitId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "MFA User " + username);
        body.put("email", username + "@acme.io");
        if (orgUnitId != null) {
            body.put("orgUnitId", orgUnitId);
        }
        body.put("role", "MEMBER");
        body.put("initialPassword", password);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    protected String accountToken(String accountId, String username, String tenantCode) {
        return bearer(new Principal(accountId, username, tenantCode, Set.of("MEMBER"), "JWT",
                "org-1"));
    }

    protected String platformToken() {
        return bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    protected ResultActions internalAuthenticate(String tenantCode, String username, String password)
            throws Exception {
        return internalAuthenticate(tenantCode, username, password, null, null);
    }

    protected ResultActions internalAuthenticate(String tenantCode, String username, String password,
                                                 String forwardedFor, String userAgent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", tenantCode);
        body.put("username", username);
        body.put("password", password);
        org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request =
                post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body));
        if (forwardedFor != null) {
            request.header("X-Forwarded-For", forwardedFor);
        }
        if (userAgent != null) {
            request.header(HttpHeaders.USER_AGENT, userAgent);
        }
        return mockMvc.perform(request);
    }

    protected ResultActions authenticateMfa(String challengeToken, String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("challengeToken", challengeToken == null ? "" : challengeToken);
        body.put("code", code == null ? "" : code);
        return mockMvc.perform(post("/internal/auth/authenticate-mfa")
                .header("X-Internal-Token", "dev-internal-token")
                .header("X-Forwarded-For", "198.51.100.42")
                .header(HttpHeaders.USER_AGENT, "mfa-test-agent")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    protected String latestChallengeId(String accountId) {
        return jdbc.queryForObject("SELECT id FROM mfa_challenges WHERE account_id = ?"
                + " ORDER BY expires_at DESC LIMIT 1", String.class, accountId);
    }

    protected long auditCount(String tenantId, String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = ?", Long.class, tenantId, action);
        return count == null ? 0L : count;
    }

    protected long loginEventCount(String tenantId, boolean success, String reason) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM account_login_events"
                        + " WHERE tenant_id = ? AND success = ? AND COALESCE(reason, '') = ?",
                Long.class, tenantId, success, reason == null ? "" : reason);
        return count == null ? 0L : count;
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
