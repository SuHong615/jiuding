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
package com.suhong.jiuding.tenant.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 3: JWT self-service MFA enrollment lifecycle
 * ({@code GET /api/me/mfa}, setup -&gt; verify -&gt; disable) and the related assertions.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaSelfServiceTest extends AbstractMfaWebTest {

    private String accountId;
    private String accountToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanMfaDatabase();
        initAdminToken();
        this.tenantId = createTenant("t-acme", "Acme");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        String orgId = createOrg(tenantId, "HQ", "Headquarters");
        accountId = createAccount(tenantId, "alice", "Passw0rd123", orgId);
        accountToken = accountToken(accountId, "alice", "t-acme");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void enrollVerifyStatusAndDisableLifecycle() throws Exception {
        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        MvcResult setup = mockMvc.perform(post("/api/me/mfa/setup")
                        .header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isNotEmpty())
                .andExpect(jsonPath("$.data.recoveryCodes.length()").value(8))
                .andReturn();
        JsonNode setupData = read(setup).path("data");
        String secret = setupData.path("secret").asText();
        String otpauthUrl = setupData.path("otpauthUrl").asText();
        assertThat(otpauthUrl).startsWith("otpauth://totp/").contains("secret=" + secret);
        assertThat(column("enabled")).isEqualTo("false");

        String code = totpService.code(secret, System.currentTimeMillis());
        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));
        assertThat(column("enabled")).isEqualTo("true");
        assertThat(jdbc.queryForObject("SELECT confirmed_at FROM account_mfa WHERE account_id = ?",
                Object.class, accountId)).isNotNull();
        assertThat(auditCount(tenantId, "MFA_ENROLLED")).isEqualTo(1);

        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.confirmedAt").isNotEmpty());

        // A second setup while enabled is a conflict.
        mockMvc.perform(post("/api/me/mfa/setup").header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        String disableCode = totpService.code(secret, System.currentTimeMillis());
        mockMvc.perform(post("/api/me/mfa/disable")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code", disableCode))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        assertThat(column("enabled")).isEqualTo("false");
        assertThat(auditCount(tenantId, "MFA_DISABLED")).isEqualTo(1);
    }

    @Test
    void invalidCodesAreRejectedWithoutChangingState() throws Exception {
        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"123456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        MvcResult setup = mockMvc.perform(post("/api/me/mfa/setup")
                        .header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isOk())
                .andReturn();
        String secret = read(setup).path("data").path("secret").asText();

        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"abcdef\"}"))
                .andExpect(status().isBadRequest());
        String otherSecret = totpService.generateSecret();
        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code",
                                totpService.code(otherSecret, System.currentTimeMillis())))))
                .andExpect(status().isBadRequest());
        assertThat(column("enabled")).isEqualTo("false");

        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code",
                                totpService.code(secret, System.currentTimeMillis())))))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/me/mfa/disable")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"ABCDEFGH\"}"))
                .andExpect(status().isBadRequest());
        assertThat(column("enabled")).isEqualTo("true");
    }

    @Test
    void onlyTenantAccountsCanManageMfa() throws Exception {
        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(post("/api/me/mfa/setup").header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/me/mfa"))
                .andExpect(status().isUnauthorized());
    }

    private String column(String name) {
        return String.valueOf(jdbc.queryForObject(
                "SELECT " + name + " FROM account_mfa WHERE account_id = ?", Object.class, accountId));
    }
}
