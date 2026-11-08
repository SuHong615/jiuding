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

import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: the legacy username + tenantCode + password switch keeps working. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class LegacySwitchCompatibilityTest extends AbstractIdentityWebTest {

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        String alpha = createTenant("t-alpha", "Alpha");
        String bravo = createTenant("t-bravo", "Bravo");
        createAccount(alpha, "shared", DEFAULT_PASSWORD);
        createAccount(bravo, "shared", DEFAULT_PASSWORD);
        activateTenant(alpha);
        activateTenant(bravo);
    }

    @Test
    void legacyPasswordSwitchStillReturnsTheTargetPrincipal() throws Exception {
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-bravo\","
                                + "\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantCode").value("t-bravo"))
                .andExpect(jsonPath("$.data.accountId").isNotEmpty());
    }

    @Test
    void legacySwitchRejectsWrongOrMissingCredentials() throws Exception {
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"shared\",\"tenantCode\":\"t-bravo\","
                                + "\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }
}
