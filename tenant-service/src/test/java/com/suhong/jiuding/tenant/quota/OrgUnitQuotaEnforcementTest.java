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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/17 section 4: organization unit quota is enforced when creating org units. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgUnitQuotaEnforcementTest extends AbstractQuotaWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanQuotaDatabase();
        initAdminToken();
        tenantId = createTenant("t-orgs", "Orgs", Map.of("quotaOrgUnits", 1));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void orgQuotaIsEnforcedAndTenantViewExposesTheLimit() throws Exception {
        createOrg(tenantId, "HQ", "Headquarters", "COMPANY");

        MvcResult rejected = attemptOrg("ENG", "Engineering")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(42900))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("QUOTA_EXCEEDED")))
                .andReturn();
        assertThat(read(rejected).path("message").asText()).contains("used=1", "limit=1");
        assertThat(orgCount()).isEqualTo(1);

        mockMvc.perform(get("/api/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotaOrgUnits").value(1));

        // Raising the quota through the tenant update endpoint takes effect immediately.
        updateTenant(tenantId, Map.of("quotaOrgUnits", 2));
        mockMvc.perform(get("/api/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.quotaOrgUnits").value(2));
        createOrg(tenantId, "ENG", "Engineering", "DEPARTMENT");
        attemptOrg("OPS", "Operations").andExpect(status().isConflict());
        assertThat(orgCount()).isEqualTo(2);

        // 0 (non positive) means unlimited.
        updateTenant(tenantId, Map.of("quotaOrgUnits", 0));
        createOrg(tenantId, "OPS", "Operations", "COMPANY");
        createOrg(tenantId, "EAST", "East", "BRANCH");
        assertThat(orgCount()).isEqualTo(4);
        JsonNode tenant = read(mockMvc.perform(get("/api/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(tenant.path("quotaOrgUnits").asInt()).isZero();
    }

    @Test
    void deletingAnOrgFreesQuota() throws Exception {
        String firstId = createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        attemptOrg("ENG", "Engineering").andExpect(status().isConflict());

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/tenants/" + tenantId + "/org-units/" + firstId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        createOrg(tenantId, "ENG", "Engineering", "DEPARTMENT");
        assertThat(orgCount()).isEqualTo(1);
    }

    private org.springframework.test.web.servlet.ResultActions attemptOrg(String code, String name)
            throws Exception {
        return mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                .header(HttpHeaders.AUTHORIZATION, adminToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "code", code, "name", name, "type", "COMPANY"))));
    }

    private long orgCount() {
        Long count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_units WHERE tenant_id = ?", Long.class, tenantId);
        return count == null ? 0L : count;
    }
}
