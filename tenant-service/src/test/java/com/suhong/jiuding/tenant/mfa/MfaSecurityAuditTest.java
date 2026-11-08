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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 3: audit trail (MFA_ENROLLED / MFA_VERIFIED / MFA_DISABLED /
 * MFA_CHALLENGE_FAILED) and the login security events for the two-step login.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaSecurityAuditTest extends AbstractMfaWebTest {

    private String accountId;
    private String accountToken;
    private String mfaSecret;

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
    void auditTrailCoversEnrollmentVerificationAndDisable() throws Exception {
        mfaSecret = enroll();

        String challengeToken = read(internalAuthenticate("t-acme", "alice", "Passw0rd123",
                "203.0.113.7", "audit-agent")
                .andExpect(status().isOk())
                .andReturn()).path("data").path("challengeToken").asText();
        authenticateMfa(challengeToken, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/me/mfa/disable")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code",
                                totpService.code(mfaSecret, System.currentTimeMillis())))))
                .andExpect(status().isOk());

        assertThat(auditCount(tenantId, "MFA_ENROLLED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "MFA_VERIFIED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "MFA_DISABLED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT actor FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'MFA_ENROLLED'", String.class, tenantId)).isEqualTo("alice");
        assertThat(jdbc.queryForObject("SELECT actor FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'MFA_VERIFIED'", String.class, tenantId)).isEqualTo("alice");
    }

    @Test
    void loginEventsOnlyRecordSuccessAfterTheSecondFactor() throws Exception {
        mfaSecret = enroll();

        String challengeToken = read(internalAuthenticate("t-acme", "alice", "Passw0rd123",
                "203.0.113.7", "audit-agent")
                .andExpect(status().isOk())
                .andReturn()).path("data").path("challengeToken").asText();
        // Password step alone does not create a login success event.
        assertThat(loginEventCount(tenantId, true, null)).isZero();

        authenticateMfa(challengeToken, "abcdef").andExpect(status().isUnauthorized());
        assertThat(loginEventCount(tenantId, false, "MFA_CHALLENGE_FAILED")).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT ip FROM account_login_events WHERE tenant_id = ?"
                + " AND success = FALSE ORDER BY at DESC LIMIT 1", String.class, tenantId))
                .isEqualTo("198.51.100.42");

        String nextChallenge = read(internalAuthenticate("t-acme", "alice", "Passw0rd123",
                "203.0.113.7", "audit-agent")
                .andExpect(status().isOk())
                .andReturn()).path("data").path("challengeToken").asText();
        authenticateMfa(nextChallenge, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isOk());

        assertThat(loginEventCount(tenantId, true, null)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT user_agent FROM account_login_events WHERE tenant_id = ?"
                + " AND success = TRUE ORDER BY at DESC LIMIT 1", String.class, tenantId))
                .isEqualTo("mfa-test-agent");
        assertThat(auditCount(tenantId, "LOGIN_SUCCESS")).isEqualTo(1);
        assertThat(auditCount(tenantId, "MFA_CHALLENGE_FAILED")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ helpers

    private String enroll() throws Exception {
        MvcResult setup = mockMvc.perform(post("/api/me/mfa/setup")
                        .header(HttpHeaders.AUTHORIZATION, accountToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = read(setup).path("data");
        String secret = data.path("secret").asText();
        mockMvc.perform(post("/api/me/mfa/verify")
                        .header(HttpHeaders.AUTHORIZATION, accountToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("code",
                                totpService.code(secret, System.currentTimeMillis())))))
                .andExpect(status().isOk());
        return secret;
    }
}
