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
package com.suhong.jiuding.mcp.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.mcp.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** External MCP server registry + connectivity check (docs/15 §4.2). */
@SpringBootTest
@AutoConfigureMockMvc
class McpExternalServersTest {

    private static final StubServer TARGET = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json", "{\"status\":\"ok\"}"));

    @AfterAll
    static void stopTarget() {
        TARGET.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void externalServerLifecycleAndPermission() throws Exception {
        String adminToken = token(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
        String name = "external-" + System.nanoTime();

        MvcResult created = mockMvc.perform(post("/api/mcp/external-servers")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\",\"transport\":\"STREAMABLE_HTTP\","
                                + "\"url\":\"" + TARGET.baseUrl() + "/mcp\","
                                + "\"headers\":{\"Authorization\":\"Bearer test\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.transport").value("STREAMABLE_HTTP"))
                .andReturn();
        String id = objectMapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asText();

        MvcResult listed = mockMvc.perform(get("/api/mcp/external-servers")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode list = objectMapper.readTree(listed.getResponse().getContentAsString()).path("data");
        boolean found = false;
        for (JsonNode item : list) {
            if (id.equals(item.path("id").asText())) {
                found = true;
            }
        }
        assertThat(found).isTrue();

        mockMvc.perform(post("/api/mcp/external-servers/" + id + "/check")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.ok").value(true))
                .andExpect(jsonPath("$.data.statusCode").value(200))
                .andExpect(jsonPath("$.data.checkedAt").isNotEmpty());

        // Tenant accounts cannot register external servers.
        String tenantToken = token(new Principal("acc-1", "Alice", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
        mockMvc.perform(post("/api/mcp/external-servers")
                        .header("Authorization", "Bearer " + tenantToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"nope\",\"transport\":\"SSE\",\"url\":\"http://127.0.0.1:1/mcp\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/mcp/external-servers/" + id)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    private String token(Principal principal) {
        return jwtService.issue(principal);
    }
}
