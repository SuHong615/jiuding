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

/** docs/20 section 3: tenant switch validates the same username + password hash. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantSwitchAuthenticationTest extends AbstractIdentityWebTest {

    private String alpha;
    private String bravo;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        alpha = createTenant("t-alpha", "Alpha");
        bravo = createTenant("t-bravo", "Bravo");
        createAccount(alpha, "shared", DEFAULT_PASSWORD);
        createAccount(bravo, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);
        activateTenant(bravo);
    }

    @Test
    void switchIssuesTheTargetTenantPrincipal() throws Exception {
        String bravoAccount = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'shared'",
                String.class, bravo);

        JsonNode data = read(mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-bravo\",\"password\":\""
                                + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(data.path("tenantId").asText()).isEqualTo(bravo);
        assertThat(data.path("tenantCode").asText()).isEqualTo("t-bravo");
        assertThat(data.path("accountId").asText()).isEqualTo(bravoAccount);
    }

    @Test
    void switchRequiresTheMatchingPassword() throws Exception {
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-bravo\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-missing\",\"password\":\""
                                + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledTargetAccountCannotBeSwitchedTo() throws Exception {
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE tenant_id = ? AND username = 'shared'",
                bravo);
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-bravo\",\"password\":\""
                                + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        // Username-only login now resolves to the remaining ACTIVE tenant only.
        mockMvc.perform(post("/internal/auth/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t-alpha"))
                .andExpect(jsonPath("$.data.tenantSelectionRequired").doesNotExist());
    }
}
