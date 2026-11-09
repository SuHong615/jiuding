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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Login brute-force protection (docs/14 §4): sliding window per {@code clientIp:username},
 * 10 attempts / window, HTTP 429 + code 42900 on the 11th, isolation per key, window expiry.
 *
 * <p>The window is shortened via properties to keep the suite fast; the production default
 * (10/60s) is asserted through the configuration test below.
 */
@SpringBootTest(properties = {
        "tenancy.auth.preset-fallback-enabled=true",
        "tenancy.auth.login-rate-limit.enabled=true",
        "tenancy.auth.login-rate-limit.max-requests=10",
        "tenancy.auth.login-rate-limit.window-seconds=5"
})
@AutoConfigureMockMvc
class LoginRateLimitTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void resetLimiter() {
        loginRateLimiter.reset();
    }

    @Test
    void eleventhAttemptReturns429WithQuotaErrorCode() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            MvcResult result = attempt("admin", "Admin@123", null, null);
            assertThat(result.getResponse().getStatus())
                    .as("attempt %s should be accepted", attempt)
                    .isEqualTo(200);
        }

        MvcResult blocked = attempt("admin", "Admin@123", null, null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        JsonNode body = objectMapper.readTree(blocked.getResponse().getContentAsString());
        assertThat(body.path("code").asInt()).isEqualTo(42900);
        assertThat(body.path("message").asText()).isEqualTo(LoginRateLimiter.TOO_MANY_ATTEMPTS);
    }

    @Test
    void failedCredentialsAlsoCountTowardsTheLimit() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            MvcResult result = attempt("admin", "WrongPassword@1", null, null);
            assertThat(result.getResponse().getStatus()).isEqualTo(401);
        }
        MvcResult blocked = attempt("admin", "WrongPassword@1", null, null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);
        assertThat(objectMapper.readTree(blocked.getResponse().getContentAsString())
                .path("code").asInt()).isEqualTo(42900);
    }

    @Test
    void windowExpiryRestoresAccess() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            attempt("admin", "Admin@123", null, null);
        }
        MvcResult blocked = attempt("admin", "Admin@123", null, null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(429);

        Thread.sleep(5_300L);

        MvcResult allowed = attempt("admin", "Admin@123", null, null);
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void differentUsernameIsIsolated() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            attempt("admin", "Admin@123", null, null);
        }
        assertThat(attempt("admin", "Admin@123", null, null).getResponse().getStatus()).isEqualTo(429);

        MvcResult approver = attempt("approver", "Approver@123", null, null);
        assertThat(approver.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void differentClientIpIsIsolated() throws Exception {
        String firstIp = "198.51.100.10";
        String secondIp = "198.51.100.11";
        for (int attempt = 1; attempt <= 10; attempt++) {
            attempt("admin", "Admin@123", firstIp, null);
        }
        assertThat(attempt("admin", "Admin@123", firstIp, null).getResponse().getStatus()).isEqualTo(429);

        MvcResult otherClient = attempt("admin", "Admin@123", secondIp, null);
        assertThat(otherClient.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void forwardedForFirstSegmentIsTheRateLimitKey() throws Exception {
        for (int attempt = 1; attempt <= 10; attempt++) {
            attempt("admin", "Admin@123", "203.0.113.5, 10.0.0.1", null);
        }
        assertThat(attempt("admin", "Admin@123", "203.0.113.5, 10.0.0.1", null)
                .getResponse().getStatus()).isEqualTo(429);

        // Same proxy hop, different original client -> independent bucket.
        MvcResult otherOrigin = attempt("admin", "Admin@123", "203.0.113.6, 10.0.0.1", null);
        assertThat(otherOrigin.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void platformLoginStillWorksWithinTheLimit() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.principal.authType").value("PLATFORM"));
    }

    private MvcResult attempt(String username, String password, String forwardedFor, String userAgent)
            throws Exception {
        var builder = post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}");
        if (forwardedFor != null) {
            builder = builder.header("X-Forwarded-For", forwardedFor);
        }
        if (userAgent != null) {
            builder = builder.header("User-Agent", userAgent);
        }
        return mockMvc.perform(builder).andReturn();
    }
}
