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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/20 section 3: username-only login resolves the tenant by username+password and asks
 * for a tenant selection when several ACTIVE tenants match.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiTenantAuthenticationTest extends AbstractIdentityWebTest {

    @BeforeEach
    void setUp() {
        cleanIdentityDatabase();
    }

    @Test
    void usernameOnlyLoginReturnsTenantSelectionForMultipleMatches() throws Exception {
        String alpha = createTenant("t-alpha", "Alpha");
        String bravo = createTenant("t-bravo", "Bravo");
        createAccount(alpha, "shared", DEFAULT_PASSWORD);
        createAccount(bravo, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);
        activateTenant(bravo);

        // An inactive tenant with the same username must not be offered.
        String draft = createTenant("t-draft", "Draft");
        createAccount(draft, "shared", DEFAULT_PASSWORD);

        JsonNode data = read(mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantSelectionRequired").value(true))
                .andReturn()).path("data");
        JsonNode tenants = data.path("tenants");
        assertThat(tenants.size()).isEqualTo(2);
        assertThat(tenants.get(0).path("tenantCode").asText()).isEqualTo("t-alpha");
        assertThat(tenants.get(0).path("tenantId").asText()).isEqualTo(alpha);
        assertThat(tenants.get(0).path("name").asText()).isEqualTo("Alpha");
        assertThat(tenants.get(1).path("tenantCode").asText()).isEqualTo("t-bravo");
        assertThat(data.path("accountId").isMissingNode() || data.path("accountId").isNull()).isTrue();
    }

    @Test
    void explicitTenantCodeStillUsesTheOriginalFlow() throws Exception {
        String alpha = createTenant("t-alpha", "Alpha");
        createAccount(alpha, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);

        mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"t-alpha\",\"username\":\"shared\",\"password\":\""
                                + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t-alpha"))
                .andExpect(jsonPath("$.data.tenantSelectionRequired").doesNotExist());
    }

    @Test
    void matchingOnlyOneTenantPasswordLogsInDirectly() throws Exception {
        String alpha = createTenant("t-alpha", "Alpha");
        String bravo = createTenant("t-bravo", "Bravo");
        createAccount(alpha, "shared", "AlphaPassw0rd");
        createAccount(bravo, "shared", "BravoPassw0rd");
        activateTenant(alpha);
        activateTenant(bravo);

        mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"password\":\"BravoPassw0rd\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t-bravo"))
                .andExpect(jsonPath("$.data.tenantSelectionRequired").doesNotExist());
    }

    @Test
    void unknownUsernameOrPasswordIsRejected() throws Exception {
        String alpha = createTenant("t-alpha", "Alpha");
        createAccount(alpha, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);

        mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
    }
}
