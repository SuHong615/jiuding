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
 * v6 stage B gateway routing (docs/16 §7.1): {@code /scim/v2/**} is whitelisted and relayed
 * untouched (tenant-service validates the {@code scim_...} bearer token itself), platform
 * SCIM-token management goes to tenant-service, and the local
 * {@code /api/platform/overview|health} handlers keep working.
 */
@SpringBootTest
@AutoConfigureMockMvc
class ScimProxyRoutesTest {

    private static final StubServer TENANT = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"tenant-service\","
                            + "\"path\":\"" + request.path() + "\","
                            + "\"method\":\"" + request.method() + "\","
                            + "\"authorization\":\"" + (request.header("authorization") == null
                            ? "" : request.header("authorization")) + "\"}}"));

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
    void scimReadIsWhitelistedAndRelaysScimBearerToken() throws Exception {
        mockMvc.perform(get("/scim/v2/ServiceProviderConfig")
                        .header("Authorization", "Bearer scim_test_token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"))
                .andExpect(jsonPath("$.data.path").value("/scim/v2/ServiceProviderConfig"))
                .andExpect(jsonPath("$.data.method").value("GET"))
                .andExpect(jsonPath("$.data.authorization").value("Bearer scim_test_token"));
    }

    @Test
    void scimRequestWithoutAnyJwtReachesTenantServiceInsteadOfGateway401() throws Exception {
        mockMvc.perform(get("/scim/v2/Users"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"))
                .andExpect(jsonPath("$.data.path").value("/scim/v2/Users"));
    }

    @Test
    void scimWriteIsForwardedWithBodyAndContentType() throws Exception {
        mockMvc.perform(post("/scim/v2/Users")
                        .header("Authorization", "Bearer scim_write_token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userName\":\"alice\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));

        StubServer.Recorded recorded = TENANT.lastRequest();
        assertThat(recorded.method()).isEqualTo("POST");
        assertThat(recorded.path()).isEqualTo("/scim/v2/Users");
        assertThat(recorded.body()).contains("\"userName\":\"alice\"");
        assertThat(recorded.header("authorization")).isEqualTo("Bearer scim_write_token");
    }

    @Test
    void platformScimTokenManagementIsProxied() throws Exception {
        mockMvc.perform(get("/api/platform/scim-tokens?tenantId=t-1")
                        .header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/platform/scim-tokens");
        assertThat(TENANT.lastRequest().query()).isEqualTo("tenantId=t-1");
    }

    @Test
    void platformScimTokenManagementStillRequiresJwt() throws Exception {
        mockMvc.perform(get("/api/platform/scim-tokens"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void localPlatformOverviewIsNotRegressed() throws Exception {
        int before = TENANT.requests().size();
        mockMvc.perform(get("/api/platform/overview").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenants").exists())
                .andExpect(jsonPath("$.data.services").isArray());
        boolean platformPathProxied = TENANT.requests().stream()
                .skip(before)
                .anyMatch(request -> request.path().startsWith("/api/platform/"));
        assertThat(platformPathProxied).as("local platform endpoints must stay local").isFalse();
    }

    private String adminToken() {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }
}
