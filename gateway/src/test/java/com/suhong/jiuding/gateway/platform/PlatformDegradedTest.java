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
package com.suhong.jiuding.gateway.platform;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.gateway.support.FreePorts;
import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Aggregation must degrade per service instead of failing the whole overview. */
@SpringBootTest(properties = "tenancy.auth.preset-fallback-enabled=true")
@AutoConfigureMockMvc
class PlatformDegradedTest {

    private static final int CLOSED_APPROVAL_PORT = FreePorts.closedPort();
    private static final int CLOSED_AGENT_PORT = FreePorts.closedPort();
    private static final int CLOSED_MCP_PORT = FreePorts.closedPort();

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        if ("/api/tenants/overview".equals(request.path())) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"total\":5,\"active\":2,\"pending\":1}}");
        } else if ("/actuator/health".equals(request.path())) {
            StubServer.respond(exchange, 200, "application/json", "{\"status\":\"UP\"}");
        } else {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":null}");
        }
    });

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url",
                () -> "http://127.0.0.1:" + CLOSED_APPROVAL_PORT);
        registry.add("tenancy.gateway.agent-service-url",
                () -> "http://127.0.0.1:" + CLOSED_AGENT_PORT);
        registry.add("tenancy.gateway.mcp-service-url",
                () -> "http://127.0.0.1:" + CLOSED_MCP_PORT);
    }

    @AfterAll
    static void stopStub() {
        TENANT.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void overviewStillReturns200WhenThreeServicesAreDown() throws Exception {
        String token = login();
        MvcResult result = mockMvc.perform(get("/api/platform/overview")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tenants.total").value(5))
                .andExpect(jsonPath("$.data.approvals.pending").value(0))
                .andExpect(jsonPath("$.data.agents.runs").value(0))
                .andExpect(jsonPath("$.data.skills.total").value(0))
                .andReturn();

        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        Map<String, String> statuses = new LinkedHashMap<>();
        data.path("services").forEach(item -> statuses.put(item.path("name").asText(), item.path("status").asText()));
        assertThat(statuses).containsEntry("tenant-service", "UP")
                .containsEntry("approval-service", "DOWN")
                .containsEntry("agent-service", "DOWN")
                .containsEntry("mcp-service", "DOWN");
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
