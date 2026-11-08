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
package com.suhong.jiuding.tenant.quota;

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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/17 section 4: seat quota is enforced when creating accounts (409 QUOTA_EXCEEDED). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AccountQuotaEnforcementTest extends AbstractQuotaWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanQuotaDatabase();
        initAdminToken();
        tenantId = createTenant("t-seats", "Seats", Map.of("quotaSeats", 2));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void thirdAccountIsRejectedWithQuotaExceededAndDeleteFreesTheSeat() throws Exception {
        createAccount(tenantId, "alice");
        createAccount(tenantId, "bob");

        MvcResult rejected = attemptCreate("carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("QUOTA_EXCEEDED")))
                .andReturn();
        String message = read(rejected).path("message").asText();
        assertThat(message).contains("used=2", "limit=2");
        assertThat(accountCount()).isEqualTo(2);

        // Soft deleting an account frees the seat (used counts non deleted rows only).
        String bobId = jdbc.queryForObject(
                "SELECT id FROM accounts WHERE tenant_id = ? AND username = 'bob'", String.class, tenantId);
        mockMvc.perform(delete("/api/tenants/" + tenantId + "/accounts/" + bobId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        createAccount(tenantId, "carol");
        assertThat(accountCount()).isEqualTo(2);
    }

    @Test
    void loweringTheLimitBelowCurrentUsageBlocksTheNextCreate() throws Exception {
        createAccount(tenantId, "alice");
        createAccount(tenantId, "bob");
        updateTenant(tenantId, Map.of("quotaSeats", 1));

        attemptCreate("carol")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("used=2")));
        assertThat(accountCount()).isEqualTo(2);
    }

    @Test
    void unlimitedSeatQuotaAllowsManyAccounts() throws Exception {
        String unlimitedId = createTenant("t-unlimited", "Unlimited", null);
        createAccount(unlimitedId, "u1");
        createAccount(unlimitedId, "u2");
        createAccount(unlimitedId, "u3");
        createAccount(unlimitedId, "u4");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ?", Long.class, unlimitedId))
                .isEqualTo(4);
    }

    private org.springframework.test.web.servlet.ResultActions attemptCreate(String username)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        body.put("email", username + "@acme.io");
        body.put("role", "MEMBER");
        body.put("initialPassword", "Passw0rd123");
        return mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long accountCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ? AND deleted_at IS NULL",
                Long.class, tenantId);
        return count == null ? 0L : count;
    }
}
