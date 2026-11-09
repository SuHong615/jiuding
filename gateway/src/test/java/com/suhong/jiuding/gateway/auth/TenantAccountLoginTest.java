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
package com.suhong.jiuding.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tenant-account login channel (docs/13 §2): the gateway proxies credentials to
 * tenant-service {@code POST /internal/auth/authenticate} and issues a JWT carrying
 * {@code tid} and {@code oid}.
 */
@SpringBootTest(properties = "tenancy.auth.preset-fallback-enabled=true")
@AutoConfigureMockMvc
class TenantAccountLoginTest {

    private static final String SUCCESS_PAYLOAD = "{\"code\":0,\"message\":\"ok\",\"data\":{"
            + "\"accountId\":\"acc-1\",\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\","
            + "\"username\":\"alice\",\"displayName\":\"Alice Chen\",\"role\":\"ORG_ADMIN\","
            + "\"orgUnitId\":\"org-9\",\"orgUnitPath\":\"/org-1/org-9\",\"mustChangePassword\":true}}";

    /** Controls what the tenant-service stub answers: 200 success, 401 invalid, >=500 outage. */
    private static volatile int stubStatus = 200;

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        if (stubStatus == 401) {
            StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                    "{\"code\":40100,\"message\":\"invalid credentials\"}");
        } else if (stubStatus >= 500) {
            StubServer.respond(exchange, stubStatus, "application/json;charset=UTF-8",
                    "{\"code\":50000,\"message\":\"boom\"}");
        } else {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8", SUCCESS_PAYLOAD);
        }
    });

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        TENANT.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @BeforeEach
    void reset() {
        TENANT.reset();
        stubStatus = 200;
    }

    @Test
    void tenantAccountLoginForwardsCredentialsAndIssuesJwtWithTidAndOid() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.principal.userId").value("acc-1"))
                .andExpect(jsonPath("$.data.principal.username").value("Alice Chen"))
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.principal.roles[0]").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.data.principal.orgUnitId").value("org-9"))
                .andExpect(jsonPath("$.data.principal.mustChangePassword").value(true))
                .andExpect(jsonPath("$.data.principal.authType").value("TENANT"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.path("data").path("token").asText();
        assertThat(token).isNotBlank();

        Principal parsed = jwtService.parse(token);
        assertThat(parsed.userId()).isEqualTo("acc-1");
        assertThat(parsed.username()).isEqualTo("Alice Chen");
        assertThat(parsed.tenantId()).isEqualTo("t-acme");
        assertThat(parsed.orgUnitId()).isEqualTo("org-9");
        assertThat(parsed.roles()).contains("ORG_ADMIN");

        StubServer.Recorded recorded = TENANT.lastRequest();
        assertThat(recorded).isNotNull();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/internal/auth/authenticate");
        assertThat(recorded.header("x-internal-token")).isEqualTo("dev-internal-token");
        JsonNode forwarded = objectMapper.readTree(recorded.body());
        assertThat(forwarded.path("tenantCode").asText()).isEqualTo("acme");
        assertThat(forwarded.path("username").asText()).isEqualTo("alice");
        assertThat(forwarded.path("password").asText()).isEqualTo("Secret@123");
    }

    @Test
    void meReturnsTenantScopeFromJwt() throws Exception {
        String token = login("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}");
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value("acc-1"))
                .andExpect(jsonPath("$.data.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.orgUnitId").value("org-9"))
                .andExpect(jsonPath("$.data.roles[0]").value("ORG_ADMIN"))
                .andExpect(jsonPath("$.data.authType").value("TENANT"))
                .andExpect(jsonPath("$.data.mustChangePassword").doesNotExist());
    }

    @Test
    void explicitTenantCodeBypassesPlatformUsers() throws Exception {
        // "admin" exists as a platform preset user, but an explicit tenantCode must route
        // through tenant-service and produce a TENANT principal instead.
        String token = login("{\"username\":\"admin\",\"password\":\"Admin@123\",\"tenantCode\":\"acme\"}");
        assertThat(TENANT.lastRequest()).isNotNull();
        Principal parsed = jwtService.parse(token);
        assertThat(parsed.userId()).isEqualTo("acc-1");
        assertThat(parsed.tenantId()).isEqualTo("t-acme");
        assertThat(parsed.orgUnitId()).isEqualTo("org-9");
    }

    @Test
    void invalidTenantCredentialsReturnUnified401() throws Exception {
        stubStatus = 401;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"wrong\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100))
                .andExpect(jsonPath("$.message").value("invalid credentials"));
    }

    @Test
    void tenantServiceServerErrorReturns503() throws Exception {
        stubStatus = 500;
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value(50200));
    }

    @Test
    void forwardsResolvedClientIpAndUserAgentToTenantService() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .header("X-Forwarded-For", "203.0.113.9, 10.0.0.1")
                        .header("User-Agent", "tenancy-e2e/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk());

        StubServer.Recorded recorded = TENANT.lastRequest();
        assertThat(recorded).isNotNull();
        assertThat(recorded.header("x-forwarded-for")).isEqualTo("203.0.113.9");
        assertThat(recorded.header("user-agent")).isEqualTo("tenancy-e2e/1.0");
    }

    @Test
    void truncatesLongUserAgentTo256Characters() throws Exception {
        String userAgent = "u".repeat(300);
        mockMvc.perform(post("/api/auth/login")
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk());

        String forwarded = TENANT.lastRequest().header("user-agent");
        assertThat(forwarded).hasSize(ClientRequestInfo.MAX_USER_AGENT_LENGTH);
        assertThat(forwarded).isEqualTo(userAgent.substring(0, ClientRequestInfo.MAX_USER_AGENT_LENGTH));
    }

    private String login(String body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
