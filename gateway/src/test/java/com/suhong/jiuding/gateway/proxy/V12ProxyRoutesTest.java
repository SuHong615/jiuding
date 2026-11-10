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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v12 routing (docs/23 §3): {@code /api/approval-workflows/**} goes to approval-service while
 * {@code /api/workflows/**} (tenant lifecycle) keeps going to tenant-service.
 */
@SpringBootTest
@AutoConfigureMockMvc
class V12ProxyRoutesTest {

    private static final StubServer TENANT = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"tenant-service\","
                            + "\"path\":\"" + request.path() + "\"}}"));
    private static final StubServer APPROVAL = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"routedBy\":\"approval-service\","
                            + "\"path\":\"" + request.path() + "\"}}"));

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", APPROVAL::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", TENANT::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        TENANT.close();
        APPROVAL.close();
    }

    @BeforeEach
    void resetStubs() {
        TENANT.reset();
        APPROVAL.reset();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JwtService jwtService;

    @Test
    void approvalWorkflowRoutesGoToApprovalService() throws Exception {
        String token = adminToken();
        mockMvc.perform(get("/api/approval-workflows").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("approval-service"));
        assertThat(APPROVAL.lastRequest().path()).isEqualTo("/api/approval-workflows");

        mockMvc.perform(get("/api/approval-workflows/wf-1").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("approval-service"));
        assertThat(APPROVAL.lastRequest().path()).isEqualTo("/api/approval-workflows/wf-1");
        assertThat(TENANT.requests()).isEmpty();
    }

    @Test
    void tenantLifecycleWorkflowStillGoesToTenantService() throws Exception {
        mockMvc.perform(get("/api/workflows/tenant-lifecycle").header("Authorization", "Bearer " + adminToken()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.routedBy").value("tenant-service"));
        assertThat(TENANT.lastRequest().path()).isEqualTo("/api/workflows/tenant-lifecycle");
        assertThat(APPROVAL.requests()).isEmpty();
    }

    @Test
    void approvalWorkflowsRequireAuthentication() throws Exception {
        mockMvc.perform(get("/api/approval-workflows"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(get("/api/approval-workflows/wf-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        assertThat(APPROVAL.requests()).isEmpty();
    }

    private String adminToken() {
        return jwtService.issue(new Principal("u-admin", "admin", null,
                Set.of("PLATFORM_ADMIN"), "JWT"));
    }
}
