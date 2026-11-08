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
package com.suhong.jiuding.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** JSON-RPC 2.0 envelope handling: handshake, session id, errors, notifications, batch. */
@SpringBootTest(properties = "tenancy.security.api-keys=test-key")
@AutoConfigureMockMvc
class JsonRpcProtocolTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void initializeHandshakeReturnsCapabilitiesAndSession() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                                + "\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
                                + "\"clientInfo\":{\"name\":\"cli\",\"version\":\"1.0\"}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jsonrpc").value("2.0"))
                .andExpect(jsonPath("$.id").value(1))
                .andExpect(jsonPath("$.result.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.result.capabilities.tools.listChanged").value(false))
                .andExpect(jsonPath("$.result.capabilities.resources.subscribe").value(false))
                .andExpect(jsonPath("$.result.serverInfo.name").value("tenancy-mcp"))
                .andExpect(jsonPath("$.result.instructions").isNotEmpty())
                .andReturn();
        assertThat(result.getResponse().getHeader("Mcp-Session-Id")).isNotBlank();
    }

    @Test
    void initializeEchoesProvidedSessionId() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .header("Mcp-Session-Id", "session-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"initialize\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(header().string("Mcp-Session-Id", "session-42"));
    }

    @Test
    void pingReturnsEmptyResultAndEchoesRequestId() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .header("X-Request-Id", "req-mcp-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Request-Id", "req-mcp-1"))
                .andExpect(jsonPath("$.id").value(2))
                .andExpect(jsonPath("$.result").isMap());
    }

    @Test
    void unknownMethodReturns32601() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/erase\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32601))
                .andExpect(jsonPath("$.result").doesNotExist());
    }

    @Test
    void invalidJsonRpcVersionReturns32600() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"1.0\",\"id\":4,\"method\":\"ping\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    void missingMethodReturns32600() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32600));
    }

    @Test
    void notificationsInitializedReturns202WithoutBody() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"))
                .andExpect(status().isAccepted())
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).isEmpty();
    }

    @Test
    void unknownToolReturns32602() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"tenant.drop\",\"arguments\":{}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void missingToolNameReturns32602() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void malformedJsonReturnsParseError() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32700));
    }

    @Test
    void batchRequestReturnsArrayOfResponses() throws Exception {
        MvcResult result = mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"ping\"},"
                                + "{\"jsonrpc\":\"2.0\",\"id\":11,\"method\":\"resources/list\"},"
                                + "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}]"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode array = objectMapper.readTree(result.getResponse().getContentAsString());
        assertThat(array.isArray()).isTrue();
        assertThat(array.size()).isEqualTo(2);
        assertThat(array.get(0).path("id").asInt()).isEqualTo(10);
        assertThat(array.get(1).path("id").asInt()).isEqualTo(11);
    }

    @Test
    void unknownResourceUriReturns32602() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":12,\"method\":\"resources/read\","
                                + "\"params\":{\"uri\":\"tenancy://unknown\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }

    @Test
    void unknownPromptReturns32602() throws Exception {
        mockMvc.perform(post("/mcp")
                        .header("X-API-Key", "test-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":13,\"method\":\"prompts/get\","
                                + "\"params\":{\"name\":\"nope\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));
    }
}
