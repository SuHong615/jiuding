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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/14 sections 2 + 3.1: login events (IP / User-Agent / result) are persisted for every
 * tenant account authentication and exposed through the paginated login-events endpoint
 * with the documented RBAC matrix.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LoginEventAuditTest {

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
    private String tenantAdminToken;
    private String auditorToken;
    private String orgAdminToken;
    private String aliceToken;
    private String tenantId;
    private String hqId;
    private String aliceId;
    private String bobId;
    private String eveId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantId = createTenant("t-acme", "Acme");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        hqId = createOrg(tenantId, null, "HQ", "Headquarters", "COMPANY");
        String eastId = createOrg(tenantId, null, "EAST", "East", "COMPANY");
        aliceId = createAccount(tenantId, "alice", hqId, "MEMBER");
        bobId = createAccount(tenantId, "bob", hqId, "MEMBER");
        eveId = createAccount(tenantId, "eve", eastId, "MEMBER");
        String orgAdminId = createAccount(tenantId, "oa", hqId, "ORG_ADMIN");

        tenantAdminToken = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        auditorToken = bearer(new Principal("u-aud", "auditor", "t-acme", Set.of("AUDITOR"), "JWT"));
        orgAdminToken = bearer(new Principal(orgAdminId, "oa", "t-acme",
                Set.of("ORG_ADMIN"), "JWT", hqId));
        aliceToken = bearer(new Principal(aliceId, "alice", "t-acme", Set.of("MEMBER"), "JWT", hqId));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void loginEventsCaptureIpUserAgentAndOutcome() throws Exception {
        String longUserAgent = "Mozilla/5.0 " + "x".repeat(300);
        authenticate("alice", "WrongPassw0rd", "203.0.113.9, 10.0.0.1", longUserAgent)
                .andExpect(status().isUnauthorized());
        authenticate("alice", PASSWORD, "198.51.100.7", "JUnit-Agent")
                .andExpect(status().isOk());

        MvcResult result = mockMvc.perform(authed(get("/api/tenants/" + tenantId
                        + "/accounts/" + aliceId + "/login-events")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andReturn();
        JsonNode items = read(result).path("data").path("items");
        JsonNode successEvent = items.get(0);
        assertThat(successEvent.path("success").asBoolean()).isTrue();
        assertThat(successEvent.path("ip").asText()).isEqualTo("198.51.100.7");
        assertThat(successEvent.path("userAgent").asText()).isEqualTo("JUnit-Agent");
        assertThat(successEvent.path("at").asText()).isNotBlank();

        JsonNode failedEvent = items.get(1);
        assertThat(failedEvent.path("success").asBoolean()).isFalse();
        // X-Forwarded-For first segment wins over remoteAddr.
        assertThat(failedEvent.path("ip").asText()).isEqualTo("203.0.113.9");
        assertThat(failedEvent.path("userAgent").asText()).hasSize(256);
        assertThat(failedEvent.path("reason").asText()).isEqualTo("INVALID_PASSWORD");

        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8).toLowerCase())
                .doesNotContain("passwordhash", "passwordsalt", PASSWORD.toLowerCase());
    }

    @Test
    void failedAttemptsAndLockProduceLoginEvents() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            authenticate("bob", "WrongPassw0rd", "192.0.2.10", "lock-test")
                    .andExpect(status().isUnauthorized());
        }
        authenticate("bob", PASSWORD, "192.0.2.10", "lock-test")
                .andExpect(status().isUnauthorized());

        MvcResult result = mockMvc.perform(authed(get("/api/tenants/" + tenantId
                        + "/accounts/" + bobId + "/login-events?size=50")))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = read(result).path("data").path("items");
        long invalid = 0;
        long locked = 0;
        for (JsonNode item : items) {
            if ("INVALID_PASSWORD".equals(item.path("reason").asText())) {
                invalid++;
            }
            if ("ACCOUNT_LOCKED".equals(item.path("reason").asText())) {
                locked++;
            }
        }
        assertThat(invalid).isEqualTo(5);
        // One event for the 5th failure that locked the account and one for the locked retry.
        assertThat(locked).isEqualTo(2);
    }

    @Test
    void loginEventVisibilityFollowsTheRbacMatrix() throws Exception {
        authenticate("eve", PASSWORD, "192.0.2.1", "eve-agent").andExpect(status().isOk());
        authenticate("alice", PASSWORD, "192.0.2.2", "alice-agent").andExpect(status().isOk());

        // Owner + tenant admin + auditor + platform admin can read.
        mockMvc.perform(get(eventsUrl(aliceId)).header(HttpHeaders.AUTHORIZATION, aliceToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get(eventsUrl(aliceId)).header(HttpHeaders.AUTHORIZATION, tenantAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(eventsUrl(aliceId)).header(HttpHeaders.AUTHORIZATION, auditorToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(eventsUrl(aliceId)).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());

        // ORG_ADMIN sees its subtree (HQ: alice/bob) but not another root (EAST: eve).
        mockMvc.perform(get(eventsUrl(aliceId)).header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get(eventsUrl(eveId)).header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // MEMBER cannot read another member's events.
        mockMvc.perform(get(eventsUrl(bobId)).header(HttpHeaders.AUTHORIZATION, aliceToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get(eventsUrl(aliceId)))
                .andExpect(status().isUnauthorized());
    }

    // ------------------------------------------------------------------ helpers

    private String eventsUrl(String accountId) {
        return "/api/tenants/" + tenantId + "/accounts/" + accountId + "/login-events";
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, adminToken);
    }

    private org.springframework.test.web.servlet.ResultActions authenticate(
            String username, String password, String forwardedFor, String userAgent) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", "t-acme");
        body.put("username", username);
        body.put("password", password);
        return mockMvc.perform(post("/internal/auth/authenticate")
                .header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN)
                .header("X-Forwarded-For", forwardedFor)
                .header(HttpHeaders.USER_AGENT, userAgent)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
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
        Map<String, Object> body = new LinkedHashMap<>();
        if (parentId != null) {
            body.put("parentId", parentId);
        }
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createAccount(String tenantId, String username, String orgUnitId, String role)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        body.put("email", username + "@acme.io");
        body.put("phone", "13812341234");
        body.put("orgUnitId", orgUnitId);
        body.put("role", role);
        body.put("initialPassword", PASSWORD);
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
