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

import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
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

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v5 gateway routing (docs/15 §4.2): {@code /api/mcp/**} → mcp-service,
 * {@code /api/me/**} + {@code /api/platform/roles/**} + {@code /api/platform/permissions}
 * → tenant-service, {@code /api/public/**} whitelisted and proxied to tenant-service,
 * while the local {@code /api/platform/overview|health} endpoints stay local.
 */
@SpringBootTest
@AutoConfigureMockMvc
class V5ProxyRoutesTest {

    private static final StubServer TENANT = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"tenant-service\","
                            + "\"path\":\"" + request.path() + "\"}}"));
    private static final StubServer MCP = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"mcp-service\","
                            + "\"path\":\"" + request.path() + "\"}}"));

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", MCP::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TENANT.close();
        MCP.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void mcpConsoleRoutesAreProxiedToMcpService() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/mcp/config").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("mcp-service"));
        assertThat(MCP.lastRequest().path()).isEqualTo("/api/mcp/config");

        mockMvc.perform(get("/api/mcp/keys").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("mcp-service"));
        assertThat(MCP.lastRequest().path()).isEqualTo("/api/mcp/keys");
    }

    @Test
    void meAndPlatformRbacRoutesGoToTenantService() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/me/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/me/permissions");

        mockMvc.perform(get("/api/platform/roles?scope=TENANT").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/roles");

        mockMvc.perform(get("/api/platform/permissions").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/permissions");
    }

    @Test
    void platformOverviewAndHealthStayLocal() throws Exception {
        int before = TENANT.requests().size();
        mockMvc.perform(get("/api/platform/overview").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").exists())
                .andExpect(jsonPath("$.data.services").isArray());
        boolean platformPathProxied = TENANT.requests().stream()
                .skip(before)
                .anyMatch(request -> request.path().startsWith("/api/platform/"));
        assertThat(platformPathProxied).as("local platform endpoints must not be proxied").isFalse();
    }

    @Test
    void publicOnboardingRouteIsWhitelistedAndProxied() throws Exception {
        // No Authorization header: /api/public/** is whitelisted in the gateway config.
        mockMvc.perform(post("/api/public/onboarding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"companyName\":\"Acme\",\"contactEmail\":\"a@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/public/onboarding");

        // /api/mcp/** still requires authentication.
        mockMvc.perform(get("/api/mcp/config")).andExpect(status().isUnauthorized());
    }

    private String adminToken() {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }
}
