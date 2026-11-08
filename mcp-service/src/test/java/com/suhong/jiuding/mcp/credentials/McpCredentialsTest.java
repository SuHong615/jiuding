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
package com.suhong.jiuding.mcp.credentials;

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
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v5 personal MCP API key lifecycle + /mcp DB-key authentication + call auditing
 * (docs/15 §4). Static env keys must keep working unchanged.
 */
@SpringBootTest
@AutoConfigureMockMvc
class McpCredentialsTest {

    private static final String STATIC_KEY = "dev-mcp-key";

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        if ("/api/tenants".equals(request.path())) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"items\":[{\"id\":\"t-1\","
                            + "\"tenantCode\":\"acme\"}],\"total\":1,\"page\":0,\"size\":20}}");
        } else {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{}}");
        }
    });

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.mcp.tenant-service-url", TENANT::baseUrl);
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

    @Autowired
    private ApiKeyRepository apiKeyRepository;

    @Test
    void configEndpointReturnsCopyReadySnippet() throws Exception {
        mockMvc.perform(get("/api/mcp/config").header("Authorization", bearer(adminToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.endpoint").value("/mcp"))
                .andExpect(jsonPath("$.data.transport").isNotEmpty())
                .andExpect(jsonPath("$.data.protocolVersion").value("2025-06-18"))
                .andExpect(jsonPath("$.data.sampleConfig.mcpServers").isMap())
                .andExpect(jsonPath("$.data.keyHint").isNotEmpty())
                .andExpect(jsonPath("$.data.docs").isNotEmpty());
    }

    @Test
    void keyLifecycleReturnsPlaintextOnceThenHidesIt() throws Exception {
        String adminToken = adminToken();
        MvcResult created = mockMvc.perform(post("/api/mcp/keys")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ci-key\",\"expiresInDays\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKey").isNotEmpty())
                .andExpect(jsonPath("$.data.keyPrefix").isNotEmpty())
                .andReturn();
        JsonNode createdBody = objectMapper.readTree(created.getResponse().getContentAsString());
        String apiKey = createdBody.path("data").path("apiKey").asText();
        String keyId = createdBody.path("data").path("id").asText();
        assertThat(apiKey).startsWith("tk_");
        assertThat(createdBody.path("data").path("keyPrefix").asText()).hasSize(12);

        // The clear-text key (and any hash) must never appear in the list response.
        MvcResult listed = mockMvc.perform(get("/api/mcp/keys").header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.id=='" + keyId + "')].keyPrefix").exists())
                .andReturn();
        String listJson = listed.getResponse().getContentAsString();
        assertThat(listJson).doesNotContain(apiKey).doesNotContain("keyHash");

        // Revoke -> REVOKED, and the key no longer authenticates /mcp.
        mockMvc.perform(delete("/api/mcp/keys/" + keyId).header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));

        mockMvc.perform(post("/mcp").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void dbKeyAuthenticatesMcpWhileStaticKeyStillWorks() throws Exception {
        String adminToken = adminToken();
        String apiKey = createKey(adminToken, "auth-key");

        mockMvc.perform(post("/mcp").header("X-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(22));

        mockMvc.perform(post("/mcp").header("X-API-Key", STATIC_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.tools.length()").value(22));
    }

    @Test
    void toolCallsAreAuditedWithIpUserAgentAndOutcome() throws Exception {
        String adminToken = adminToken();
        String apiKey = createKey(adminToken, "audit-key");

        mockMvc.perform(post("/mcp").header("X-API-Key", apiKey)
                        .header("X-Forwarded-For", "203.0.113.55, 10.0.0.1")
                        .header("User-Agent", "e2e-mcp/1.0")
                        .header("X-Request-Id", "req-audit-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"tenant.list\",\"arguments\":{\"page\":0,\"size\":5}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.isError").value(false));

        // Protocol-level failure: unknown tool -> -32602, still audited as failed.
        mockMvc.perform(post("/mcp").header("X-API-Key", apiKey)
                        .header("X-Forwarded-For", "203.0.113.55")
                        .header("User-Agent", "e2e-mcp/1.0")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"no.such.tool\",\"arguments\":{}}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.error.code").value(-32602));

        MvcResult auditResult = mockMvc.perform(get("/api/mcp/audits?tool=tenant.list")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode items = objectMapper.readTree(auditResult.getResponse().getContentAsString())
                .path("data").path("items");
        JsonNode successAudit = findAudit(items, "tenant.list", true);
        assertThat(successAudit).isNotNull();
        assertThat(successAudit.path("ip").asText()).isEqualTo("203.0.113.55");
        assertThat(successAudit.path("userAgent").asText()).isEqualTo("e2e-mcp/1.0");
        assertThat(successAudit.path("requestId").asText()).isEqualTo("req-audit-1");
        assertThat(successAudit.path("durationMs").asLong()).isGreaterThanOrEqualTo(0);

        MvcResult failedAudit = mockMvc.perform(get("/api/mcp/audits?tool=no.such.tool")
                        .header("Authorization", bearer(adminToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode failedItems = objectMapper.readTree(failedAudit.getResponse().getContentAsString())
                .path("data").path("items");
        JsonNode failure = findAudit(failedItems, "no.such.tool", false);
        assertThat(failure).isNotNull();
        assertThat(failure.path("errorCode").asInt()).isEqualTo(-32602);
    }

    @Test
    void expiredKeyIsRejected() throws Exception {
        String rawKey = "tk_expired" + UUID.randomUUID().toString().replace("-", "");
        apiKeyRepository.insert(new ApiKeyRow(UUID.randomUUID().toString(), "expired-key",
                rawKey.substring(0, 12), ApiKeyService.sha256Hex(rawKey), "u-expired", "expired",
                null, "PLATFORM_ADMIN", "ACTIVE", LocalDateTime.now().minusDays(10),
                LocalDateTime.now().minusDays(1), null, null));

        mockMvc.perform(post("/mcp").header("X-API-Key", rawKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void tenantAccountSeesOnlyOwnKeysAndAudits() throws Exception {
        String adminToken = adminToken();
        String tenantToken = tenantToken();
        String adminKeyId = objectMapper.readTree(mockMvc.perform(post("/api/mcp/keys")
                        .header("Authorization", bearer(adminToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"admin-only-key\"}"))
                .andReturn().getResponse().getContentAsString()).path("data").path("id").asText();

        MvcResult tenantCreated = mockMvc.perform(post("/api/mcp/keys")
                        .header("Authorization", bearer(tenantToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"alice-key\"}"))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tenantBody = objectMapper.readTree(tenantCreated.getResponse().getContentAsString());
        String tenantKeyId = tenantBody.path("data").path("id").asText();
        String tenantApiKey = tenantBody.path("data").path("apiKey").asText();

        MvcResult tenantList = mockMvc.perform(get("/api/mcp/keys").header("Authorization", bearer(tenantToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode tenantKeys = objectMapper.readTree(tenantList.getResponse().getContentAsString()).path("data");
        for (JsonNode item : tenantKeys) {
            assertThat(item.path("ownerUsername").asText()).isEqualTo("Alice");
            assertThat(item.path("id").asText()).isNotEqualTo(adminKeyId);
        }
        boolean containsTenantKey = false;
        for (JsonNode item : tenantKeys) {
            if (item.path("id").asText().equals(tenantKeyId)) {
                containsTenantKey = true;
            }
        }
        assertThat(containsTenantKey).isTrue();

        // Tenant key executes a tool -> the audit appears for the tenant owner.
        mockMvc.perform(post("/mcp").header("X-API-Key", tenantApiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
                                + "\"params\":{\"name\":\"tenant.list\",\"arguments\":{}}}"))
                .andExpect(status().isOk());

        MvcResult tenantAudits = mockMvc.perform(get("/api/mcp/audits").header("Authorization", bearer(tenantToken)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode auditItems = objectMapper.readTree(tenantAudits.getResponse().getContentAsString())
                .path("data").path("items");
        for (JsonNode item : auditItems) {
            assertThat(item.path("ownerUsername").asText()).isEqualTo("Alice");
        }
        assertThat(auditItems.size()).isGreaterThanOrEqualTo(1);

        // Non-admin cannot list every key.
        mockMvc.perform(get("/api/mcp/keys?all=true").header("Authorization", bearer(tenantToken)))
                .andExpect(status().isForbidden());
    }

    private String createKey(String token, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/mcp/keys")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"" + name + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("apiKey").asText();
    }

    private JsonNode findAudit(JsonNode items, String tool, boolean success) {
        for (JsonNode item : items) {
            if (tool.equals(item.path("tool").asText()) && item.path("success").asBoolean() == success) {
                return item;
            }
        }
        return null;
    }

    private String adminToken() {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    private String tenantToken() {
        return jwtService.issue(new Principal("acc-1", "Alice", "t-acme",
                Set.of("TENANT_ADMIN"), "JWT"));
    }

    private static String bearer(String token) {
        return "Bearer " + token;
    }
}
