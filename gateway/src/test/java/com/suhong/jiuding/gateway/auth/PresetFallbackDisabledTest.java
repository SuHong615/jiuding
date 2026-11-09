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
package com.suhong.jiuding.gateway.auth;

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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v12 docs/23 §5: with {@code tenancy.auth.preset-fallback-enabled=false} (production
 * default) platform accounts come from tenant-service only — the local preset directory is
 * ignored, while tenant-account login is unaffected.
 */
@SpringBootTest(properties = "tenancy.auth.preset-fallback-enabled=false")
@AutoConfigureMockMvc
class PresetFallbackDisabledTest {

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        if ("/internal/platform-users/authenticate".equals(request.path())) {
            StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                    "{\"code\":40100,\"message\":\"invalid credentials\"}");
            return;
        }
        if ("/internal/auth/authenticate".equals(request.path())
                && request.body() != null && request.body().contains("\"tenantCode\":\"acme\"")) {
            StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"accountId\":\"acc-acme\","
                            + "\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\",\"username\":\"alice\","
                            + "\"displayName\":\"Alice Acme\",\"role\":\"TENANT_ADMIN\","
                            + "\"orgUnitId\":\"org-acme\",\"mustChangePassword\":false}}");
            return;
        }
        if ("/internal/auth/authenticate".equals(request.path())) {
            StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                    "{\"code\":40100,\"message\":\"invalid credentials\"}");
            return;
        }
        StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                "{\"code\":40400,\"message\":\"not found\"}");
    });

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

    @Test
    void presetPlatformUserIsRejectedWhenFallbackDisabled() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        // The DB lookup was attempted; the preset directory must not be consulted.
        boolean platformLookup = TENANT.requests().stream()
                .anyMatch(request -> "/internal/platform-users/authenticate".equals(request.path()));
        org.assertj.core.api.Assertions.assertThat(platformLookup).isTrue();
    }

    @Test
    void tenantAccountLoginStillWorksWithoutPresetFallback() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Secret@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.principal.authType").value("TENANT"));
    }
}
