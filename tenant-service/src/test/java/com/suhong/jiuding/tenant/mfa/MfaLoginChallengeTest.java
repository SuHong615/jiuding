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

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 3 login changes: password + MFA enabled returns a 5 minute challenge token
 * (claim {@code mfa=true}) instead of a principal; the second step verifies TOTP / one-time
 * recovery codes, with single-use challenges, 5 failure invalidation and expiry rejection.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MfaLoginChallengeTest extends AbstractMfaWebTest {

    private String accountId;
    private String accountToken;
    private String orgId;
    private String mfaSecret;
    private List<String> recoveryCodes;

    @BeforeEach
    void setUp() throws Exception {
        cleanMfaDatabase();
        initAdminToken();
        this.tenantId = createTenant("t-acme", "Acme");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        orgId = createOrg(tenantId, "HQ", "Headquarters");
        accountId = createAccount(tenantId, "alice", "Passw0rd123", orgId);
        accountToken = accountToken(accountId, "alice", "t-acme");
        JsonNode enrolled = enroll();
        mfaSecret = enrolled.path("secret").asText();
        recoveryCodes = new java.util.ArrayList<>();
        enrolled.path("recoveryCodes").forEach(node -> recoveryCodes.add(node.asText()));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void passwordStepReturnsChallengeAndSecondStepReturnsTheFullPrincipal() throws Exception {
        MvcResult challengeResult = internalAuthenticate("t-acme", "alice", "Passw0rd123",
                "192.0.2.9", "login-agent")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mfaRequired").value(true))
                .andReturn();
        JsonNode challenge = read(challengeResult).path("data");
        String challengeToken = challenge.path("challengeToken").asText();
        assertThat(challengeToken).isNotBlank();
        assertThat(challenge.has("accountId")).isFalse();
        assertThat(challenge.path("username").asText()).isEqualTo("alice");

        // The challenge token is a short lived JWT with mfa=true and no role claim.
        JsonNode claims = decodeClaims(challengeToken);
        assertThat(claims.path("mfa").asBoolean()).isTrue();
        assertThat(claims.path("exp").asLong() - claims.path("iat").asLong()).isEqualTo(300);
        assertThat(claims.has("roles")).isFalse();
        assertThat(claims.has("role")).isFalse();

        // No LOGIN_SUCCESS / last_login before the second factor is completed.
        assertThat(auditCount(tenantId, "LOGIN_SUCCESS")).isZero();
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM accounts WHERE id = ?",
                Object.class, accountId)).isNull();

        String code = totpService.code(mfaSecret, System.currentTimeMillis());
        MvcResult result = authenticateMfa(challengeToken, code)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(accountId))
                .andExpect(jsonPath("$.data.role").value("MEMBER"))
                .andExpect(jsonPath("$.data.tenantCode").value("t-acme"))
                .andExpect(jsonPath("$.data.orgUnitPath").value("/" + orgId))
                .andReturn();
        assertThat(read(result).path("data").has("mfaRequired")).isFalse();
        assertThat(auditCount(tenantId, "MFA_VERIFIED")).isEqualTo(1);
        assertThat(auditCount(tenantId, "LOGIN_SUCCESS")).isEqualTo(1);
        assertThat(loginEventCount(tenantId, true, null)).isEqualTo(1);

        // Challenges are single use.
        authenticateMfa(challengeToken, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    @Test
    void fiveFailedCodesInvalidateTheChallenge() throws Exception {
        String challengeToken = challengeToken();
        for (int attempt = 1; attempt <= 5; attempt++) {
            authenticateMfa(challengeToken, "abcdef")
                    .andExpect(status().isUnauthorized());
        }
        String challengeId = latestChallengeId(accountId);
        Map<String, Object> state = jdbc.queryForMap(
                "SELECT attempts, consumed_at FROM mfa_challenges WHERE id = ?", challengeId);
        assertThat(((Number) state.get("attempts")).intValue()).isEqualTo(5);
        assertThat(state.get("consumed_at")).isNotNull();
        assertThat(auditCount(tenantId, "MFA_CHALLENGE_FAILED")).isEqualTo(5);
        assertThat(loginEventCount(tenantId, false, "MFA_CHALLENGE_FAILED")).isEqualTo(5);

        // Even a correct code cannot revive an invalidated challenge.
        authenticateMfa(challengeToken, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isUnauthorized());

        // A fresh password step issues a fresh challenge that still works.
        String freshToken = challengeToken();
        authenticateMfa(freshToken, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isOk());
    }

    @Test
    void expiredChallengeIsRejected() throws Exception {
        String challengeToken = challengeToken();
        jdbc.update("UPDATE mfa_challenges SET expires_at = ? WHERE account_id = ?",
                com.suhong.jiuding.tenant.support.TimeUtil.now().minusSeconds(5), accountId);

        authenticateMfa(challengeToken, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("invalid credentials"));
        assertThat(auditCount(tenantId, "MFA_CHALLENGE_FAILED")).isEqualTo(1);
    }

    @Test
    void recoveryCodeIsAcceptedOnceOnly() throws Exception {
        String firstChallenge = challengeToken();
        authenticateMfa(firstChallenge, recoveryCodes.get(0))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accountId").value(accountId));
        String storedHashes = jdbc.queryForObject(
                "SELECT recovery_codes_hash FROM account_mfa WHERE account_id = ?", String.class, accountId);
        assertThat(storedHashes.split(",")).hasSize(7);

        String secondChallenge = challengeToken();
        authenticateMfa(secondChallenge, recoveryCodes.get(0))
                .andExpect(status().isUnauthorized());
        authenticateMfa(secondChallenge, totpService.code(mfaSecret, System.currentTimeMillis()))
                .andExpect(status().isOk());
    }

    @Test
    void malformedChallengeTokenIsRejected() throws Exception {
        authenticateMfa("not-a-jwt", "123456")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        // Empty required fields are rejected by bean validation before the token check.
        authenticateMfa(null, "123456")
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private String challengeToken() throws Exception {
        return read(internalAuthenticate("t-acme", "alice", "Passw0rd123")
                .andExpect(status().isOk())
                .andReturn()).path("data").path("challengeToken").asText();
    }

    private JsonNode enroll() throws Exception {
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
        return data;
    }

    private JsonNode decodeClaims(String token) throws Exception {
        String payload = token.split("\\.")[1];
        byte[] json = Base64.getUrlDecoder().decode(payload);
        return objectMapper.readTree(new String(json, StandardCharsets.UTF_8));
    }
}
