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
import com.suhong.jiuding.tenant.support.TimeUtil;
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

/** docs/20 section 3: passwordless tenant switch validated by username + password hash. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PasswordlessSwitchTest extends AbstractIdentityWebTest {

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
    }

    @Test
    void passwordlessSwitchReturnsTheTargetTenantPrincipal() throws Exception {
        String bravoAccount = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'shared'",
                String.class, bravo);
        JsonNode data = read(mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"t-bravo\"}"))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(data.path("tenantId").asText()).isEqualTo(bravo);
        assertThat(data.path("tenantCode").asText()).isEqualTo("t-bravo");
        assertThat(data.path("accountId").asText()).isEqualTo(bravoAccount);
    }

    @Test
    void switchRejectsPlatformSessionsAndPasswordMismatches() throws Exception {
        String platformUserId = read(mockMvc.perform(post("/api/platform/users")
                        .header(org.springframework.http.HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"platform-ops\",\"password\":\""
                                + DEFAULT_PASSWORD + "\",\"roles\":[\"AUDITOR\"]}"))
                .andExpect(status().isOk())
                .andReturn()).path("data").path("id").asText();
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + platformUserId
                                + "\",\"tenantCode\":\"t-bravo\"}"))
                .andExpect(status().isUnauthorized());

        // A tenant with the same username but a different password is not a member relation.
        String charlie = createTenant("t-charlie", "Charlie");
        createAccount(charlie, "shared", "DifferentPassw0rd");
        activateTenant(charlie);
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"t-charlie\"}"))
                .andExpect(status().isUnauthorized());

        // Inactive target tenant and missing target tenant are rejected.
        String draft = createTenant("t-draft", "Draft");
        createAccount(draft, "shared", DEFAULT_PASSWORD);
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"t-draft\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"missing\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledSourceAccountCannotSwitch() throws Exception {
        jdbc.update("UPDATE accounts SET status = 'DISABLED' WHERE id = ?", alphaAccount);
        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"t-bravo\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void mfaEnabledTargetReturnsAChallenge() throws Exception {
        String bravoAccount = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'shared'",
                String.class, bravo);
        jdbc.update("INSERT INTO account_mfa (account_id, tenant_id, secret_base32, enabled,"
                        + " created_at, confirmed_at) VALUES (?,?,?,?,?,?)",
                bravoAccount, bravo, "JBSWY3DPEHPK3PXP", true, TimeUtil.now(), TimeUtil.now());

        mockMvc.perform(post("/internal/auth/switch-tenant")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentAccountId\":\"" + alphaAccount
                                + "\",\"tenantCode\":\"t-bravo\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaRequired").value(true))
                .andExpect(jsonPath("$.data.challengeToken").isNotEmpty())
                .andExpect(jsonPath("$.data.accountId").doesNotExist());
    }
}
