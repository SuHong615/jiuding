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
package com.suhong.jiuding.gateway.proxy;

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

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Reverse proxy behaviour against an embedded downstream stub. */
@SpringBootTest
@AutoConfigureMockMvc
class ProxyForwardingTest {

    private static final StubServer STUB = new StubServer((request, exchange) -> {
        if ("GET".equals(request.method()) && "/api/tenants".equals(request.path())) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{\"id\":\"t-1\",\"tenantCode\":\"acme\"}],"
                            + "\"total\":1,\"page\":0,\"size\":20}}");
        } else if ("POST".equals(request.method()) && "/api/tenants".equals(request.path())) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"t-new\",\"tenantCode\":\"globex\","
                            + "\"status\":\"DRAFT\"}}");
        } else if ("/api/tenants/missing-1".equals(request.path())) {
            StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                    "{\"code\":40400,\"message\":\"tenant not found\",\"data\":null}");
        } else if ("GET".equals(request.method()) && "/mcp".equals(request.path())) {
            exchange.getResponseHeaders().set("X-Accel-Buffering", "no");
            StubServer.stream(exchange, 200, "text/event-stream",
                    "data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}\n\n", ": ping\n\n");
        } else {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{}}");
        }
    });

    static {
        // The stub is started by its constructor; kept for clarity of lifecycle.
    }

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", STUB::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", STUB::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", STUB::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", STUB::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        STUB.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void resetStub() {
        STUB.reset();
    }

    @Test
    void forwardsMethodPathQueryAndIdentityHeaders() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/tenants?page=0&size=5")
                        .header("Authorization", "Bearer " + token)
                        .header("X-Tenant-Id", "t-acme")
                        .header("X-Request-Id", "req-42"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-42"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("acme"));

        StubServer.Recorded recorded = STUB.lastRequest();
        assertThat(recorded).isNotNull();
        assertThat(recorded.method()).isEqualTo("GET");
        assertThat(recorded.path()).isEqualTo("/api/tenants");
        assertThat(recorded.query()).isEqualTo("page=0&size=5");
        assertThat(recorded.header("authorization")).isEqualTo("Bearer " + token);
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-acme");
        assertThat(recorded.header("x-request-id")).isEqualTo("req-42");
    }

    @Test
    void forwardsJsonPostBody() throws Exception {
        String token = login();
        mockMvc.perform(post("/api/tenants")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Authorization", "Bearer " + token)
                        .content("{\"tenantCode\":\"globex\",\"name\":\"Globex\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));

        StubServer.Recorded recorded = STUB.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.body()).contains("\"tenantCode\":\"globex\"");
        assertThat(recorded.header("content-type")).startsWith("application/json");
    }

    @Test
    void relaysDownstreamErrorStatusAndBody() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/tenants/missing-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void rejectsUnauthenticatedProxyCall() throws Exception {
        mockMvc.perform(get("/api/tenants"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void streamsSseChunksWithoutBuffering() throws Exception {
        mockMvc.perform(get("/mcp")
                        .header("X-API-Key", "dev-mcp-key")
                        .header("Accept", "text/event-stream"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.containsString("text/event-stream")))
                .andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("data: {\"jsonrpc\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString(": ping")));
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
