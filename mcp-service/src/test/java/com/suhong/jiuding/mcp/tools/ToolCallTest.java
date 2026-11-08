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
package com.suhong.jiuding.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.mcp.support.StubServer;
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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** {@code tools/call} success path and downstream-failure → {@code isError:true} mapping. */
@SpringBootTest(properties = "tenancy.security.api-keys=test-key")
@AutoConfigureMockMvc
class ToolCallTest {

    private static final StubServer DOWNSTREAM = new StubServer((request, exchange) -> {
        String path = request.path();
        String method = request.method();
        if ("GET".equals(method) && "/api/tenants".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{\"id\":\"t-1\","
                            + "\"tenantCode\":\"acme\"}],\"total\":1,\"page\":0,\"size\":20}}");
        } else if ("POST".equals(method) && "/api/tenants".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"t-new\","
                            + "\"tenantCode\":\"globex\",\"status\":\"DRAFT\"}}");
        } else if ("GET".equals(method) && "/api/tenants/t-1/org-units".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":\"ou-root\",\"code\":\"HQ\","
                            + "\"name\":\"总部\",\"type\":\"COMPANY\",\"depth\":0}]}");
        } else if ("POST".equals(method) && "/api/tenants/t-1/org-units".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"ou-new\",\"code\":\"RD\","
                            + "\"name\":\"研发部\",\"type\":\"TEAM\",\"depth\":2}}");
        } else if ("GET".equals(method) && "/api/tenants/t-403/org-units".equals(path)) {
            StubServer.respond(exchange, 403, "application/json;charset=UTF-8",
                    "{\"code\":40300,\"message\":\"cross-tenant access denied\"}");
        } else if ("GET".equals(method) && "/api/tenants/t-1/accounts".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{\"id\":\"a-1\","
                            + "\"username\":\"alice\",\"status\":\"ACTIVE\"}],\"total\":1,\"page\":0,\"size\":20}}");
        } else if ("POST".equals(method) && "/api/tenants/t-1/accounts".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"account\":{\"id\":\"a-new\","
                            + "\"username\":\"bob\"},\"tempPassword\":\"Tmp#123456\"}}");
        } else if ("POST".equals(method) && "/api/tenants/t-1/accounts/a-1/reset-password".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"tempPassword\":\"Reset#654321\"}}");
        } else if ("GET".equals(method) && "/api/tenants/t-1/ratings".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{\"id\":\"r-1\","
                            + "\"grade\":\"A\",\"score\":72}],\"total\":1,\"page\":0,\"size\":20}}");
        } else if ("POST".equals(method) && "/api/tenants/t-1/ratings/assess".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"r-new\",\"grade\":\"AA\","
                            + "\"score\":83,\"source\":\"SKILL\"}}");
        } else if ("DELETE".equals(method) && "/api/tenants/t-2".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"t-2\",\"deletedAt\":"
                            + "\"2026-11-08T00:00:00Z\"}}");
        } else if ("/api/tenants/t-404".equals(path)) {
            StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                    "{\"code\":40400,\"message\":\"tenant not found\"}");
        } else if ("/api/approvals/pending".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"id\":\"a-1\",\"state\":\"PENDING_APPROVAL\"}]}");
        } else if ("/api/skills/risk-review/run".equals(path)) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"skillId\":\"risk-review\","
                            + "\"verdict\":\"PASS\",\"score\":90,\"decision\":\"NEEDS_REVIEW\"}}");
        } else if ("/actuator/health".equals(path)) {
            StubServer.respond(exchange, 200, "application/json", "{\"status\":\"UP\"}");
        } else {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{}}");
        }
    });

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.mcp.tenant-service-url", DOWNSTREAM::baseUrl);
        registry.add("tenancy.mcp.approval-service-url", DOWNSTREAM::baseUrl);
        registry.add("tenancy.mcp.agent-service-url", DOWNSTREAM::baseUrl);
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
    void tenantListToolProxiesAndUnwrapsData() throws Exception {
        JsonNode result = callTool("tenant.list", "{\"page\":0,\"size\":5}", "rid-9");
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("items").get(0).path("tenantCode").asText())
                .isEqualTo("acme");
        assertThat(result.path("content").get(0).path("type").asText()).isEqualTo("text");
        assertThat(result.path("content").get(0).path("text").asText()).contains("acme");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.path()).isEqualTo("/api/tenants");
        assertThat(recorded.query()).isEqualTo("page=0&size=5");
        assertThat(recorded.header("x-api-key")).isEqualTo("test-key");
        assertThat(recorded.header("x-request-id")).isEqualTo("rid-9");
    }

    @Test
    void tenantCreateToolPostsArgumentsDownstream() throws Exception {
        JsonNode result = callTool("tenant.create",
                "{\"tenantCode\":\"globex\",\"name\":\"Globex\",\"plan\":\"PRO\",\"quotaSeats\":10}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("status").asText()).isEqualTo("DRAFT");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/api/tenants");
        assertThat(recorded.body()).contains("\"tenantCode\":\"globex\"").contains("\"plan\":\"PRO\"");
    }

    @Test
    void downstreamNotFoundMapsToIsErrorWithErrorCode() throws Exception {
        JsonNode result = callTool("tenant.get", "{\"tenantId\":\"t-404\"}", null);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("errorCode").asInt()).isEqualTo(40400);
        assertThat(result.path("structuredContent").path("message").asText()).isNotBlank();
        assertThat(result.path("content").get(0).path("text").asText()).contains("40400");
    }

    @Test
    void skillRunToolUsesLongerTimeoutPath() throws Exception {
        JsonNode result = callTool("skill.run",
                "{\"skillId\":\"risk-review\",\"tenantId\":\"t-1\",\"input\":{\"industry\":\"finance\"}}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("verdict").asText()).isEqualTo("PASS");
        assertThat(DOWNSTREAM.lastRequest().path()).isEqualTo("/api/skills/risk-review/run");
    }

    @Test
    void approvalListPendingToolReturnsArray() throws Exception {
        JsonNode result = callTool("approval.list_pending", "{}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        // MCP requires object structuredContent, so bare arrays are wrapped as {count, items}.
        assertThat(result.path("structuredContent").isObject()).isTrue();
        assertThat(result.path("structuredContent").path("items").isArray()).isTrue();
        assertThat(result.path("structuredContent").path("items").get(0).path("id").asText()).isEqualTo("a-1");
    }

    @Test
    void missingRequiredArgumentReturnsProtocolError() throws Exception {
        MvcResult response = postRpc("{\"jsonrpc\":\"2.0\",\"id\":21,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"tenant.get\",\"arguments\":{}}}", null);
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    // ------------------------------------------------ docs/11 identity tools

    @Test
    void orgTreeForwardsTenantHeaderAndFormat() throws Exception {
        JsonNode result = callTool("org.tree", "{\"tenantId\":\"t-1\",\"format\":\"flat\"}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").isObject()).isTrue();
        assertThat(result.path("structuredContent").path("items").get(0).path("code").asText()).isEqualTo("HQ");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("GET");
        assertThat(recorded.path()).isEqualTo("/api/tenants/t-1/org-units");
        assertThat(recorded.query()).isEqualTo("format=flat");
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-1");
    }

    @Test
    void orgCreatePostsBodyWithTenantHeader() throws Exception {
        JsonNode result = callTool("org.create",
                "{\"tenantId\":\"t-1\",\"parentId\":\"ou-root\",\"code\":\"RD\","
                        + "\"name\":\"研发部\",\"type\":\"TEAM\",\"sortOrder\":1}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("id").asText()).isEqualTo("ou-new");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/api/tenants/t-1/org-units");
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-1");
        assertThat(recorded.body()).contains("\"code\":\"RD\"").contains("\"type\":\"TEAM\"");
        assertThat(recorded.body()).contains("\"parentId\":\"ou-root\"");
    }

    @Test
    void accountListForwardsFiltersAndTenantHeader() throws Exception {
        JsonNode result = callTool("account.list",
                "{\"tenantId\":\"t-1\",\"role\":\"MEMBER\",\"status\":\"ACTIVE\",\"page\":0,\"size\":10}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("items").get(0).path("username").asText())
                .isEqualTo("alice");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("GET");
        assertThat(recorded.path()).isEqualTo("/api/tenants/t-1/accounts");
        assertThat(recorded.query()).contains("role=MEMBER").contains("status=ACTIVE");
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-1");
    }

    @Test
    void accountCreateReturnsOneTimeTemporaryPassword() throws Exception {
        JsonNode result = callTool("account.create",
                "{\"tenantId\":\"t-1\",\"username\":\"bob\",\"displayName\":\"Bob\","
                        + "\"role\":\"MEMBER\",\"email\":\"bob@example.com\"}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("tempPassword").asText())
                .isEqualTo("Tmp#123456");

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/api/tenants/t-1/accounts");
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-1");
        assertThat(recorded.body()).contains("\"username\":\"bob\"").contains("\"role\":\"MEMBER\"");
    }

    @Test
    void accountResetPasswordPostsToResetEndpoint() throws Exception {
        JsonNode result = callTool("account.reset_password",
                "{\"tenantId\":\"t-1\",\"accountId\":\"a-1\"}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("tempPassword").asText())
                .isEqualTo("Reset#654321");
        assertThat(DOWNSTREAM.lastRequest().path())
                .isEqualTo("/api/tenants/t-1/accounts/a-1/reset-password");
        assertThat(DOWNSTREAM.lastRequest().header("x-tenant-id")).isEqualTo("t-1");
    }

    @Test
    void ratingAssessPostsNoteAndRatingListPages() throws Exception {
        JsonNode assessed = callTool("rating.assess",
                "{\"tenantId\":\"t-1\",\"note\":\"quarterly review\"}", null);
        assertThat(assessed.path("isError").asBoolean()).isFalse();
        assertThat(assessed.path("structuredContent").path("grade").asText()).isEqualTo("AA");
        StubServer.Recorded assessRecord = DOWNSTREAM.lastRequest();
        assertThat(assessRecord.path()).isEqualTo("/api/tenants/t-1/ratings/assess");
        assertThat(assessRecord.body()).contains("quarterly review");
        assertThat(assessRecord.header("x-tenant-id")).isEqualTo("t-1");

        JsonNode history = callTool("rating.list", "{\"tenantId\":\"t-1\",\"page\":0,\"size\":5}", null);
        assertThat(history.path("isError").asBoolean()).isFalse();
        StubServer.Recorded historyRecord = DOWNSTREAM.lastRequest();
        assertThat(historyRecord.path()).isEqualTo("/api/tenants/t-1/ratings");
        assertThat(historyRecord.query()).contains("page=0").contains("size=5");
    }

    @Test
    void tenantDeleteUsesDeleteWithTenantHeader() throws Exception {
        JsonNode result = callTool("tenant.delete", "{\"tenantId\":\"t-2\"}", null);
        assertThat(result.path("isError").asBoolean()).isFalse();
        assertThat(result.path("structuredContent").path("deletedAt").asText()).isNotBlank();

        StubServer.Recorded recorded = DOWNSTREAM.lastRequest();
        assertThat(recorded.method()).isEqualTo("DELETE");
        assertThat(recorded.path()).isEqualTo("/api/tenants/t-2");
        assertThat(recorded.header("x-tenant-id")).isEqualTo("t-2");
    }

    @Test
    void crossTenantForbiddenMapsToIsError() throws Exception {
        JsonNode result = callTool("org.tree", "{\"tenantId\":\"t-403\"}", null);
        assertThat(result.path("isError").asBoolean()).isTrue();
        assertThat(result.path("structuredContent").path("errorCode").asInt()).isEqualTo(40300);
        assertThat(result.path("content").get(0).path("text").asText()).contains("40300");
    }

    @Test
    void orgTreeRejectsInvalidFormatWithProtocolError() throws Exception {
        MvcResult response = postRpc("{\"jsonrpc\":\"2.0\",\"id\":31,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"org.tree\",\"arguments\":"
                + "{\"tenantId\":\"t-1\",\"format\":\"wide\"}}}", null);
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void orgCreateRejectsUnknownTypeWithProtocolError() throws Exception {
        MvcResult response = postRpc("{\"jsonrpc\":\"2.0\",\"id\":32,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"org.create\",\"arguments\":"
                + "{\"tenantId\":\"t-1\",\"code\":\"X\",\"name\":\"X\",\"type\":\"DIVISION\"}}}", null);
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    private JsonNode callTool(String name, String argumentsJson, String requestId) throws Exception {
        String payload = "{\"jsonrpc\":\"2.0\",\"id\":20,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + name + "\",\"arguments\":" + argumentsJson + "}}";
        MvcResult response = postRpc(payload, requestId);
        JsonNode body = objectMapper.readTree(response.getResponse().getContentAsString());
        assertThat(body.has("error")).as("tool call %s returned a JSON-RPC error: %s", name, body).isFalse();
        return body.path("result");
    }

    private MvcResult postRpc(String payload, String requestId) throws Exception {
        var builder = post("/mcp")
                .header("X-API-Key", "test-key")
                .contentType(MediaType.APPLICATION_JSON);
        if (requestId != null) {
            builder = builder.header("X-Request-Id", requestId);
        }
        return mockMvc.perform(builder.content(payload))
                .andExpect(status().isOk())
                .andReturn();
    }
}
