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
 * v10 gateway routing (docs/20 §3/§4.5): platform account management, SSO provider management
 * and the tenant lifecycle workflow API are proxied to tenant-service, while the local
 * {@code /api/platform/overview|health} handlers (and the existing RBAC routes) keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class V10ProxyRoutesTest {

    private static final StubServer TENANT = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"tenant-service\","
                            + "\"path\":\"" + request.path() + "\"}}"));

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", TENANT::baseUrl);
    }

    @AfterAll
    static void stopStub() {
        TENANT.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void platformUserManagementIsProxied() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/platform/users").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/users");

        mockMvc.perform(get("/api/platform/users/pu-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/users/pu-1");

        mockMvc.perform(post("/api/platform/users")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().method()).isEqualTo("POST");
        assertThat(TENANT.lastRequest().body()).contains("\"username\":\"alice\"");
    }

    @Test
    void ssoProviderManagementIsProxied() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/platform/sso-providers").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/sso-providers");

        mockMvc.perform(get("/api/platform/sso-providers/sp-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/sso-providers/sp-1");
    }

    @Test
    void tenantLifecycleWorkflowIsProxied() throws Exception {
        mockMvc.perform(get("/api/workflows/tenant-lifecycle").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/workflows/tenant-lifecycle");
    }

    @Test
    void localPlatformOverviewAndHealthAreNotRegressed() throws Exception {
        String token = adminToken();
        int before = TENANT.requests().size();
        mockMvc.perform(get("/api/platform/overview").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").exists());
        mockMvc.perform(get("/api/platform/health").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        boolean platformPathProxied = TENANT.requests().stream()
                .skip(before)
                .anyMatch(request -> request.path().startsWith("/api/platform/"));
        assertThat(platformPathProxied).as("local platform endpoints must stay local").isFalse();

        // Existing RBAC route still goes to tenant-service.
        mockMvc.perform(get("/api/platform/roles").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
    }

    private String adminToken() {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }
}
