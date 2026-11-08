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
package com.suhong.jiuding.tenant.offboarding;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractTenantDataWebTest;
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

/** docs/18 section 3: ACTIVE/SUSPENDED tenants are terminated, soft deleted and audited. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantOffboardSuccessTest extends AbstractTenantDataWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanTenantDatabase();
        initAdminToken();
        tenantId = createTenant("t-active", "Active Tenant", null);
        createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        createAccount(tenantId, "alice");
        setTenantStatus(tenantId, "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void activeTenantIsTerminatedSoftDeletedAndAudited() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "reason", "contract ended, data retained per DPA"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(tenantId))
                .andExpect(jsonPath("$.data.status").value("TERMINATED"))
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty())
                .andReturn();
        JsonNode view = read(result).path("data");
        assertThat(view.path("deletedAt").asText()).isNotBlank();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, deleted_at FROM tenants WHERE id = ?", tenantId);
        assertThat(row.get("status")).isEqualTo("TERMINATED");
        assertThat(row.get("deleted_at")).isNotNull();

        assertThat(auditCount(tenantId, "TENANT_OFFBOARDED")).isEqualTo(1);
        String detail = jdbc.queryForObject("SELECT detail FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'TENANT_OFFBOARDED'", String.class, tenantId);
        assertThat(detail).contains("contract ended", "reason=");

        // The tenant disappears from default reads and lists.
        mockMvc.perform(get("/api/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
        mockMvc.perform(get("/api/tenants?includeDeleted=true")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].deletedAt").isNotEmpty());

        // Offboarding twice is a 404 (already soft deleted).
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isNotFound());

        // Data is retained until the retention window expires.
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_units WHERE tenant_id = ?", Long.class, tenantId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ?", Long.class, tenantId))
                .isEqualTo(1);
    }

    @Test
    void suspendedTenantCanAlsoBeOffboarded() throws Exception {
        String suspendedId = createTenant("t-suspended", "Suspended", null);
        setTenantStatus(suspendedId, "SUSPENDED");

        mockMvc.perform(post("/api/tenants/" + suspendedId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"customer churn\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"))
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());
        assertThat(auditCount(suspendedId, "TENANT_OFFBOARDED")).isEqualTo(1);
    }
}
