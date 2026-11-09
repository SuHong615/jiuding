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
package com.suhong.jiuding.gateway.oidc;

import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v12 Mock OIDC is dev-only: nothing is exposed when {@code tenancy.auth.mock-oidc.enabled=false}. */
@SpringBootTest
@AutoConfigureMockMvc
class MockOidcDisabledTest {

    private static final StubServer TENANT = new StubServer((request, exchange) ->
            StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                    "{\"code\":40400,\"message\":\"not found\"}"));

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
    void mockOidcEndpointsAreNotExposedByDefault() throws Exception {
        mockMvc.perform(get("/mock-oidc/.well-known/openid-configuration"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/mock-oidc/authorize?response_type=code&redirect_uri=http://localhost/cb"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/mock-oidc/userinfo"))
                .andExpect(status().isNotFound());
    }
}
