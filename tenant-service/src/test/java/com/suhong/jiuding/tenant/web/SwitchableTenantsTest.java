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
package com.suhong.jiuding.tenant.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: the switchable tenant list for the current session. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SwitchableTenantsTest extends AbstractIdentityWebTest {

    private String alpha;
    private String bravo;
    private String alphaAccount;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        alpha = createTenant("t-alpha", "Alpha");
        bravo = createTenant("t-bravo", "Bravo");
        alphaAccount = createAccount(alpha, "shared", DEFAULT_PASSWORD);
        createAccount(bravo, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);
        activateTenant(bravo);

        // Same username but a different password: must not be listed as switchable.
        String charlie = createTenant("t-charlie", "Charlie");
        createAccount(charlie, "shared", "DifferentPassw0rd");
        activateTenant(charlie);
        // Inactive tenant: never listed.
        String draft = createTenant("t-draft", "Draft");
        createAccount(draft, "shared", DEFAULT_PASSWORD);
    }

    @Test
    void listsOtherTenantsWithTheSamePasswordAndExcludesTheCurrentOne() throws Exception {
        JsonNode data = read(mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("accountId", alphaAccount)
                        .param("currentTenantId", "t-alpha"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("tenantId").asText()).isEqualTo(bravo);
        assertThat(data.get(0).path("tenantCode").asText()).isEqualTo("t-bravo");
        assertThat(data.get(0).path("name").asText()).isEqualTo("Bravo");

        // The current tenant id (UUID form) is excluded as well.
        JsonNode byId = read(mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("accountId", alphaAccount)
                        .param("currentTenantId", alpha))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(byId.size()).isEqualTo(1);
        assertThat(byId.get(0).path("tenantCode").asText()).isEqualTo("t-bravo");
    }

    @Test
    void usernameAndCurrentTenantIdAlsoResolveTheSession() throws Exception {
        JsonNode data = read(mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("username", "shared")
                        .param("currentTenantId", "t-alpha"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(data.size()).isEqualTo(1);
        assertThat(data.get(0).path("tenantCode").asText()).isEqualTo("t-bravo");
    }

    @Test
    void platformOrUnknownSessionsReturnAnEmptyList() throws Exception {
        String platformUserId = read(mockMvc.perform(post("/api/platform/users")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-ops\",\"password\":\""
                                + DEFAULT_PASSWORD + "\",\"roles\":[\"PLATFORM_ADMIN\"]}"))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asText();

        mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("accountId", platformUserId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("accountId", "missing-account"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        mockMvc.perform(get("/internal/auth/tenants")
                        .header("X-Internal-Token", "dev-internal-token")
                        .param("username", "platform-ops"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
        // The endpoint is internal-only.
        mockMvc.perform(get("/internal/auth/tenants").param("accountId", alphaAccount))
                .andExpect(status().isUnauthorized());
    }
}
