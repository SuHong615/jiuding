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
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/17 section 3: quota / usage API shape (limit/used/remaining/percent + status),
 * unlimited semantics, permission matrix and the internal gateway snapshot.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class QuotaUsageApiTest extends AbstractQuotaWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanQuotaDatabase();
        initAdminToken();
        Map<String, Object> quotas = new LinkedHashMap<>();
        quotas.put("quotaSeats", 4);
        quotas.put("quotaOrgUnits", 5);
        quotas.put("quotaStorageGb", 20);
        quotas.put("quotaApiRpm", 100);
        tenantId = createTenant("t-acme", "Acme", quotas);
        createAccount(tenantId, "alice");
        createAccount(tenantId, "bob");
        createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        createOrg(tenantId, "ENG", "Engineering", "DEPARTMENT");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void exposesLimitsUsageRemainingAndPercent() throws Exception {
        JsonNode quota = quota(tenantId, adminToken);
        assertThat(quota.path("tenantId").asText()).isEqualTo(tenantId);
        assertThat(quota.path("plan").asText()).isEqualTo("PRO");

        assertThat(quota.path("seats").path("limit").asInt()).isEqualTo(4);
        assertThat(quota.path("seats").path("used").asInt()).isEqualTo(2);
        assertThat(quota.path("seats").path("remaining").asInt()).isEqualTo(2);
        assertThat(quota.path("seats").path("percent").asInt()).isEqualTo(50);

        assertThat(quota.path("orgUnits").path("limit").asInt()).isEqualTo(5);
        assertThat(quota.path("orgUnits").path("used").asInt()).isEqualTo(2);
        assertThat(quota.path("orgUnits").path("remaining").asInt()).isEqualTo(3);
        assertThat(quota.path("orgUnits").path("percent").asInt()).isEqualTo(40);

        assertThat(quota.path("storageGb").path("limit").asInt()).isEqualTo(20);
        assertThat(quota.path("storageGb").path("used").isNull()).isTrue();
        assertThat(quota.path("storageGb").path("remaining").isNull()).isTrue();
        assertThat(quota.path("apiRpm").path("limit").asInt()).isEqualTo(100);
        assertThat(quota.path("status").asText()).isEqualTo("OK");
    }

    @Test
    void statusMovesFromWarningToExceeded() throws Exception {
        // 4 of 5 seats -> 80% -> WARNING.
        createAccount(tenantId, "carol");
        createAccount(tenantId, "dave");
        updateTenant(tenantId, Map.of(
                "quotaSeats", 5, "quotaOrgUnits", 5, "quotaStorageGb", 20, "quotaApiRpm", 100));
        JsonNode warning = quota(tenantId, adminToken);
        assertThat(warning.path("seats").path("percent").asInt()).isEqualTo(80);
        assertThat(warning.path("status").asText()).isEqualTo("WARNING");

        // 4 of 4 seats -> EXCEEDED.
        updateTenant(tenantId, Map.of(
                "quotaSeats", 4, "quotaOrgUnits", 5, "quotaStorageGb", 20, "quotaApiRpm", 100));
        JsonNode exceeded = quota(tenantId, adminToken);
        assertThat(exceeded.path("seats").path("remaining").asInt()).isZero();
        assertThat(exceeded.path("status").asText()).isEqualTo("EXCEEDED");
    }

    @Test
    void nullOrNonPositiveQuotasAreUnlimited() throws Exception {
        String unlimitedId = createTenant("t-unlimited", "Unlimited", Map.of(
                "quotaOrgUnits", -3));
        createAccount(unlimitedId, "carol");
        createAccount(unlimitedId, "dave");
        createOrg(unlimitedId, "ROOT", "Root", "COMPANY");

        JsonNode quota = quota(unlimitedId, adminToken);
        assertThat(quota.path("seats").path("limit").isNull()).isTrue();
        assertThat(quota.path("seats").path("used").asInt()).isEqualTo(2);
        assertThat(quota.path("seats").path("remaining").isNull()).isTrue();
        assertThat(quota.path("seats").path("percent").isNull()).isTrue();
        assertThat(quota.path("orgUnits").path("limit").isNull()).isTrue();
        assertThat(quota.path("storageGb").path("limit").isNull()).isTrue();
        assertThat(quota.path("apiRpm").path("limit").isNull()).isTrue();
        assertThat(quota.path("status").asText()).isEqualTo("OK");

        // No quota fields at all is unlimited as well.
        String plainId = createTenant("t-plain", "Plain", null);
        createOrg(plainId, "A", "A", "COMPANY");
        createOrg(plainId, "B", "B", "COMPANY");
        JsonNode plain = quota(plainId, adminToken);
        assertThat(plain.path("orgUnits").path("limit").isNull()).isTrue();
        assertThat(plain.path("orgUnits").path("used").asInt()).isEqualTo(2);
    }

    @Test
    void quotaVisibilityFollowsThePermissionMatrix() throws Exception {
        String orgId = jdbc.queryForObject(
                "SELECT id FROM org_units WHERE tenant_id = ? LIMIT 1", String.class, tenantId);

        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.limit").value(4));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken("t-acme")))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken("t-acme", orgId)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, memberToken("t-acme", orgId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-other")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota"))
                .andExpect(status().isUnauthorized());

        // Internal snapshot used by the gateway RPM limiter.
        mockMvc.perform(get("/internal/tenants/" + tenantId + "/quota")
                        .header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiRpm.limit").value(100))
                .andExpect(jsonPath("$.data.seats.used").value(2));
        mockMvc.perform(get("/internal/tenants/" + tenantId + "/quota"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/tenants/missing/quota")
                        .header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isNotFound());
    }
}
