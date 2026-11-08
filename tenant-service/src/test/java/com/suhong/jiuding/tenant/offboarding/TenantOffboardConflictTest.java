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

import com.suhong.jiuding.tenant.support.AbstractTenantDataWebTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/18 section 3: only platform administrators may offboard and only from ACTIVE/SUSPENDED. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantOffboardConflictTest extends AbstractTenantDataWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanTenantDatabase();
        initAdminToken();
        tenantId = createTenant("t-draft", "Draft", null);
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void onlyActiveOrSuspendedTenantsCanBeOffboarded() throws Exception {
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"too early\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        setTenantStatus(tenantId, "PENDING_APPROVAL");
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"pending\"}"))
                .andExpect(status().isConflict());

        setTenantStatus(tenantId, "TERMINATED");
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"already terminated\"}"))
                .andExpect(status().isConflict());

        // A failed offboard must not soft delete the tenant.
        assertThat(jdbc.queryForObject("SELECT deleted_at FROM tenants WHERE id = ?",
                Object.class, tenantId)).isNull();
        assertThat(auditCount(tenantId, "TENANT_OFFBOARDED")).isZero();
    }

    @Test
    void reasonIsRequired() throws Exception {
        setTenantStatus(tenantId, "ACTIVE");
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\" \"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void offboardRequiresPlatformAdministratorJwt() throws Exception {
        setTenantStatus(tenantId, "ACTIVE");
        String orgId = createOrg(tenantId, "HQ", "Headquarters", "COMPANY");

        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-draft"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken("t-draft"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, memberToken("t-draft", orgId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"not allowed\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"anonymous\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"machine\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownOrDeletedTenantIs404() throws Exception {
        mockMvc.perform(post("/api/tenants/missing-tenant/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"missing\"}"))
                .andExpect(status().isNotFound());

        String deletedId = createTenant("t-deleted", "Deleted", null);
        mockMvc.perform(delete("/api/tenants/" + deletedId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tenants/" + deletedId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"already gone\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }
}
