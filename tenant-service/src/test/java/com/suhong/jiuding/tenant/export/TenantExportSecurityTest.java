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
package com.suhong.jiuding.tenant.export;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractTenantDataWebTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/18 section 2: PII masking defaults, unmasked access rules, permissions and audit. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantExportSecurityTest extends AbstractTenantDataWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanTenantDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme", null);
        createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        createAccount(tenantId, "alice");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void exportIsMaskedByDefaultAndPlatformCanRequestPlainText() throws Exception {
        JsonNode masked = read(mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(masked.path("accounts").get(0).path("email").asText()).isEqualTo("a***@acme.io");
        assertThat(masked.path("accounts").get(0).path("phone").asText()).isEqualTo("138****1234");

        JsonNode plain = read(mockMvc.perform(get("/api/tenants/" + tenantId + "/export?mask=false")
                        .header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(plain.path("accounts").get(0).path("email").asText()).isEqualTo("alice@acme.io");
        assertThat(plain.path("accounts").get(0).path("phone").asText()).isEqualTo("13812341234");
    }

    @Test
    void tenantAdminAndAuditorAreMaskedOnlyAndOrgAdminMemberAreForbidden() throws Exception {
        String orgId = jdbc.queryForObject(
                "SELECT id FROM org_units WHERE tenant_id = ? LIMIT 1", String.class, tenantId);

        JsonNode tenantAdmin = read(mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme")))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(tenantAdmin.path("accounts").get(0).path("email").asText())
                .isEqualTo("a***@acme.io");
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export?mask=false")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken("t-acme")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export?mask=false")
                        .header(HttpHeaders.AUTHORIZATION, auditorToken("t-acme")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken("t-acme", orgId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, memberToken("t-acme", orgId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-other")))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void exportAuditHasNoPiiAndDeletedTenantIs404() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isOk());
        String detail = jdbc.queryForObject("SELECT detail FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'TENANT_EXPORTED' ORDER BY at DESC LIMIT 1", String.class, tenantId);
        assertThat(detail).contains("mask=true", "accounts=1");
        assertThat(detail).doesNotContain("alice@acme.io", "13812341234");

        String deletableId = createTenant("t-deleted", "Deleted", null);
        mockMvc.perform(delete("/api/tenants/" + deletableId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + deletableId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
        mockMvc.perform(get("/api/tenants/missing-tenant/export")
                        .header(HttpHeaders.AUTHORIZATION, platformToken()))
                .andExpect(status().isNotFound());
    }
}
