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

/** {@code /api/platform/**} aggregation with all four services reachable. */
@SpringBootTest(properties = "tenancy.auth.preset-fallback-enabled=true")
@AutoConfigureMockMvc
class PlatformAggregationTest {

    private static final StubServer DOWNSTREAM = new StubServer((request, exchange) -> {
        switch (request.path()) {
            case "/api/tenants/overview" -> StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"total\":12,\"active\":8,\"pending\":3,"
                            + "\"byStatus\":{},\"byPlan\":{},\"byRisk\":{}}}");
            case "/api/approvals/overview" -> StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"pending\":2,\"autoApproved\":5,\"approved\":7,"
                            + "\"rejected\":1,\"slaBreached\":0}}");
            case "/api/agents/overview" -> StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"runs\":6,\"todayRuns\":2,\"skills\":4,"
                            + "\"models\":[\"scripted\"]}}");
            case "/api/skills" -> StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":\"a\"},{\"id\":\"b\"},{\"id\":\"c\"},{\"id\":\"d\"}]}");
            case "/actuator/health" -> StubServer.respond(exchange, 200, "application/json",
                    "{\"status\":\"UP\"}");
            default -> StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":null}");
        }
    });

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

    @Test
    void overviewAggregatesEveryService() throws Exception {
        String token = login();
        mockMvc.perform(get("/api/platform/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.tenants.total").value(12))
                .andExpect(jsonPath("$.data.tenants.active").value(8))
                .andExpect(jsonPath("$.data.tenants.pending").value(3))
                .andExpect(jsonPath("$.data.approvals.pending").value(2))
                .andExpect(jsonPath("$.data.approvals.autoApproved").value(5))
                .andExpect(jsonPath("$.data.approvals.rejected").value(1))
                .andExpect(jsonPath("$.data.skills.total").value(4))
                .andExpect(jsonPath("$.data.agents.runs").value(6))
                .andExpect(jsonPath("$.data.agents.todayRuns").value(2))
                .andExpect(jsonPath("$.data.services.length()").value(4));
    }

    @Test
    void healthProbesAllServices() throws Exception {
        String token = login();
        MvcResult result = mockMvc.perform(get("/api/platform/health").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString()).path("data");
        assertThat(data.isArray()).isTrue();
        assertThat(data.size()).isEqualTo(4);
        Map<String, String> statuses = new LinkedHashMap<>();
        data.forEach(item -> statuses.put(item.path("name").asText(), item.path("status").asText()));
        assertThat(statuses).hasSize(4).allSatisfy((name, status) -> assertThat(status).isEqualTo("UP"));
        assertThat(statuses).containsKeys("tenant-service", "approval-service", "agent-service", "mcp-service");
    }

    @Test
    void overviewRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/platform/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
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
