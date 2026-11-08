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
package com.suhong.jiuding.gateway.web;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@code /internal/**} is a service-to-service surface and must never be exposed by the
 * gateway: every request is answered with a uniform 404 and nothing is proxied.
 */
@SpringBootTest
@AutoConfigureMockMvc
class InternalPathBlockedTest {

    private static final StubServer DOWNSTREAM = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{}}"));

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", DOWNSTREAM::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", DOWNSTREAM::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", DOWNSTREAM::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", DOWNSTREAM::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        DOWNSTREAM.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetStub() {
        DOWNSTREAM.reset();
    }

    @Test
    void internalPathsReturn404AndAreNeverProxied() throws Exception {
        mockMvc.perform(get("/internal/tenants/t-1").header("X-Request-Id", "req-internal-1"))
                .andExpect(status().isNotFound())
                .andExpect(header().string("X-Request-Id", "req-internal-1"))
                .andExpect(jsonPath("$.code").value(40400))
                .andExpect(jsonPath("$.message").value("not found"));

        mockMvc.perform(post("/internal/tenants/t-1/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(get("/internal"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // Platform login legitimately calls /internal/platform-users/authenticate; only the\n        // blocked /internal/tenants/** path must never be proxied.\n        assertThat(DOWNSTREAM.requests().stream()\n                .noneMatch(recorded -> recorded.path().startsWith("/internal/tenants")))\n                .as("internal tenant paths must not be proxied").isTrue();
    }

    @Test
    void internalPathsStay404WithAnyCredentials() throws Exception {
        String token = login();

        mockMvc.perform(get("/internal/tenants/t-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(get("/internal/tenants/t-1").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        mockMvc.perform(get("/internal/tenants/t-1").header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // Platform login legitimately calls /internal/platform-users/authenticate; only the\n        // blocked /internal/tenants/** path must never be proxied.\n        assertThat(DOWNSTREAM.requests().stream()\n                .noneMatch(recorded -> recorded.path().startsWith("/internal/tenants")))\n                .as("internal tenant paths must not be proxied").isTrue();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }
}
