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
package com.suhong.jiuding.approval.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.suhong.jiuding.approval.config.ApprovalProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the real HTTP downstream clients against a local stub server
 * (no Docker, no external network): envelope unwrapping, error mapping, timeouts.
 */
class DownstreamClientTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AtomicReference<String> lastInternalToken = new AtomicReference<>();
    private final AtomicReference<String> lastStatusBody = new AtomicReference<>();

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startStub() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/skills/approval-policy/run", exchange -> respond(exchange, 200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"decision\":\"AUTO_APPROVE\","
                        + "\"actions\":[{\"action\":\"setSlaHours\",\"value\":6}]}}"));
        server.createContext("/api/skills/bare/run", exchange -> respond(exchange, 200,
                "{\"decision\":\"NEEDS_REVIEW\"}"));
        server.createContext("/api/skills/broken/run", exchange -> respond(exchange, 500,
                "{\"code\":50000,\"message\":\"boom\"}"));
        server.createContext("/api/skills/error-envelope/run", exchange -> respond(exchange, 200,
                "{\"code\":53000,\"message\":\"agent failed\"}"));
        server.createContext("/internal/tenants/t-1/status", exchange -> {
            lastInternalToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Token"));
            lastStatusBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            respond(exchange, 200, "{\"code\":0,\"message\":\"ok\",\"data\":{}}");
        });
        server.createContext("/internal/tenants/t-fail/status", exchange -> respond(exchange, 500,
                "{\"code\":50000,\"message\":\"tenant down\"}"));
        server.createContext("/internal/tenants/t-1", exchange -> respond(exchange, 200,
                "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"t-1\",\"status\":\"APPROVED\"}}"));
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void stopStub() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void agentClientUnwrapsApiResponseEnvelope() {
        HttpAgentServiceClient client = agentClient(baseUrl);
        JsonNode result = client.runSkill("approval-policy", Map.of("riskLevel", "LOW"), "t-1");
        assertThat(result.path("decision").asText()).isEqualTo("AUTO_APPROVE");
        assertThat(result.path("actions").get(0).path("value").asInt()).isEqualTo(6);
    }

    @Test
    void agentClientAcceptsBarePayload() {
        JsonNode result = agentClient(baseUrl).runSkill("bare", Map.of(), "t-1");
        assertThat(result.path("decision").asText()).isEqualTo("NEEDS_REVIEW");
    }

    @Test
    void agentClientThrowsOnHttpError() {
        assertThatThrownBy(() -> agentClient(baseUrl).runSkill("broken", Map.of(), "t-1"))
                .isInstanceOf(DownstreamException.class)
                .hasMessageContaining("broken");
    }

    @Test
    void agentClientThrowsOnErrorEnvelope() {
        assertThatThrownBy(() -> agentClient(baseUrl).runSkill("error-envelope", Map.of(), "t-1"))
                .isInstanceOf(DownstreamException.class)
                .hasMessageContaining("53000");
    }

    @Test
    void agentClientThrowsWhenServiceIsUnreachable() {
        assertThatThrownBy(() -> agentClient("http://127.0.0.1:1").runSkill("approval-policy", Map.of(), "t-1"))
                .isInstanceOf(DownstreamException.class)
                .hasMessageContaining("agent-service");
    }

    @Test
    void tenantClientSendsInternalTokenAndStatusBody() {
        tenantClient(baseUrl).updateStatus("t-1", "APPROVED", "approval approved");
        assertThat(lastInternalToken.get()).isEqualTo("dev-internal-token");
        assertThat(lastStatusBody.get()).contains("\"status\":\"APPROVED\"");
    }

    @Test
    void tenantClientThrowsOnHttpError() {
        assertThatThrownBy(() -> tenantClient(baseUrl).updateStatus("t-fail", "ACTIVE", "retry"))
                .isInstanceOf(DownstreamException.class)
                .hasMessageContaining("tenant-service");
    }

    @Test
    void tenantClientUnwrapsTenantSnapshot() {
        JsonNode tenant = tenantClient(baseUrl).getTenant("t-1");
        assertThat(tenant.path("status").asText()).isEqualTo("APPROVED");
    }

    private HttpAgentServiceClient agentClient(String url) {
        return new HttpAgentServiceClient(restClient(url), properties(), objectMapper);
    }

    private HttpTenantServiceClient tenantClient(String url) {
        return new HttpTenantServiceClient(restClient(url), properties(), objectMapper);
    }

    private ApprovalProperties properties() {
        ApprovalProperties properties = new ApprovalProperties();
        properties.setDownstreamApiKey("dev-mcp-key");
        properties.setInternalToken("dev-internal-token");
        properties.setConnectTimeoutMs(500);
        properties.setReadTimeoutMs(2000);
        return properties;
    }

    private RestClient restClient(String url) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(500);
        factory.setReadTimeout(2000);
        return RestClient.builder().baseUrl(url).requestFactory(factory).build();
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, payload.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(payload);
        }
    }
}
