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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * MFA two-step login (docs/16 搂3): the password step returns a challenge, and
 * {@code POST /api/auth/login/mfa} exchanges it plus a TOTP code for the JWT.
 */
@SpringBootTest(properties = "tenancy.auth.preset-fallback-enabled=true")
@AutoConfigureMockMvc
class MfaLoginTest {

    private static final String CHALLENGE = "mfa-challenge-1";

    private static final String MFA_REQUIRED_PAYLOAD = "{\"code\":0,\"message\":\"ok\",\"data\":{"
            + "\"mfaRequired\":true,\"challengeToken\":\"" + CHALLENGE + "\",\"username\":\"alice\"}}";

    private static final String SUCCESS_PAYLOAD = "{\"code\":0,\"message\":\"ok\",\"data\":{"
            + "\"accountId\":\"acc-1\",\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\","
            + "\"username\":\"alice\",\"displayName\":\"Alice Chen\",\"role\":\"TENANT_ADMIN\","
            + "\"orgUnitId\":\"org-9\",\"orgUnitPath\":\"/org-1/org-9\",\"mustChangePassword\":false}}";

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        if (request.path().endsWith("/internal/auth/authenticate-mfa")) {
            if (request.body().contains("\"123456\"")) {
                StubServer.respond(exchange, 200, "application/json;charset=UTF-8", SUCCESS_PAYLOAD);
            } else {
                StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                        "{\"code\":40100,\"message\":\"invalid mfa code\"}");
            }
            return;
        }
        if (request.body().contains("\"Secret@123\"")) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8", MFA_REQUIRED_PAYLOAD);
        } else {
            StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                    "{\"code\":40100,\"message\":\"invalid credentials\"}");
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
    }

    @Test
    void passwordStepReturnsMfaChallengeInsteadOfToken() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mfaRequired").value(true))
                .andExpect(jsonPath("$.data.challengeToken").value(CHALLENGE))
                .andExpect(jsonPath("$.data.username").value("alice"))
                .andExpect(jsonPath("$.data.token").doesNotExist());

        assertThat(TENANT.requests()).anySatisfy(request ->
                assertThat(request.path()).isEqualTo("/internal/auth/authenticate"));
    }

    @Test
    void mfaStepIssuesJwtWithTenantAndOrgClaims() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + CHALLENGE + "\",\"code\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.principal.orgUnitId").value("org-9"))
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        String token = body.path("data").path("token").asText();
        Principal principal = jwtService.parse(token);
        assertThat(principal.tenantId()).isEqualTo("t-acme");
        assertThat(principal.orgUnitId()).isEqualTo("org-9");
        assertThat(principal.roles()).containsExactly("TENANT_ADMIN");

        assertThat(TENANT.requests()).anySatisfy(request -> {
            assertThat(request.path()).isEqualTo("/internal/auth/authenticate-mfa");
            assertThat(request.body()).contains(CHALLENGE).contains("123456");
        });
    }

    @Test
    void wrongMfaCodeIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/auth/login/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"challengeToken\":\"" + CHALLENGE + "\",\"code\":\"000000\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void missingChallengeTokenIsRejected() throws Exception {
        mockMvc.perform(post("/api/auth/login/mfa")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }
}
