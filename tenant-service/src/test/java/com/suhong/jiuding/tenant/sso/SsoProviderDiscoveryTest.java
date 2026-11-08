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
package com.suhong.jiuding.tenant.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: the provider discovery test pulls the OIDC well-known document. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SsoProviderDiscoveryTest extends AbstractIdentityWebTest {

    private HttpServer server;
    private String issuer;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/.well-known/openid-configuration", exchange -> {
            byte[] body = ("{\"issuer\":\"https://idp.example\",\"authorization_endpoint\":"
                    + "\"https://idp.example/authorize\"}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.createContext("/bad/.well-known/openid-configuration", exchange -> {
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        issuer = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void discoveryTestSucceedsForAValidProvider() throws Exception {
        String id = createProvider("okta", issuer);
        mockMvc.perform(post("/api/platform/sso-providers/" + id + "/test")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.issuer").value(issuer))
                .andExpect(jsonPath("$.data.authorizationEndpoint")
                        .value("https://idp.example/authorize"));
    }

    @Test
    void discoveryTestRejectsInvalidUnreachableOrMissingIssuers() throws Exception {
        String bad = createProvider("bad", issuer + "/bad");
        mockMvc.perform(post("/api/platform/sso-providers/" + bad + "/test")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());

        String unreachable = createProvider("unreachable", "http://127.0.0.1:1");
        mockMvc.perform(post("/api/platform/sso-providers/" + unreachable + "/test")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());

        String missing = createProvider("missing-issuer", "");
        mockMvc.perform(post("/api/platform/sso-providers/" + missing + "/test")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
    }

    private String createProvider(String code, String issuer) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", "Provider " + code);
        body.put("issuer", issuer);
        body.put("clientId", "client-" + code);
        body.put("clientSecret", "secret-" + code);
        JsonNode created = read(mockMvc.perform(post("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        return created.path("id").asText();
    }
}
