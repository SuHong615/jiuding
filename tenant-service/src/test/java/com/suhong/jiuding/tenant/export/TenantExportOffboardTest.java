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
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/18: tenant data export (PII masking, permissions) and offboarding. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantExportOffboardTest extends AbstractTenantDataWebTest {

    private String tenantId;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        cleanTenantDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme", new LinkedHashMap<>());
        createAccount(tenantId, "alice");
        Map<String, Object> org = new LinkedHashMap<>();
        org.put("code", "HQ");
        org.put("name", "Headquarters");
        org.put("type", "COMPANY");
        MvcResult orgResult = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(org)))
                .andExpect(status().isOk())
                .andReturn();
        orgId = read(orgResult).path("data").path("id").asText();
        assessRating(tenantId);
        setTenantStatus(tenantId, "ACTIVE");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    private JsonNode exportBody(MvcResult result) throws Exception {
        JsonNode root = read(result);
        return root.has("data") ? root.path("data") : root;
    }

    @Test
    void exportDefaultsToMaskedPiiWithAttachment() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        JsonNode data = exportBody(result);
        assertThat(data.path("tenant").path("id").asText()).isEqualTo(tenantId);
        assertThat(data.path("orgUnits").isArray()).isTrue();
        assertThat(data.path("orgUnits").size()).isEqualTo(1);
        assertThat(data.path("accounts").size()).isEqualTo(1);
        assertThat(data.path("accounts").get(0).path("email").asText()).contains("*");
        assertThat(data.path("rating").path("current").path("grade").asText()).isNotBlank();
        assertThat(data.path("rating").path("history").isArray()).isTrue();
        assertThat(data.path("audits").isArray()).isTrue();
        assertThat(auditCount(tenantId, "TENANT_EXPORTED")).isEqualTo(1);
    }

    @Test
    void platformAdminCanExportPlaintext() throws Exception {
        JsonNode data = exportBody(mockMvc.perform(get("/api/tenants/" + tenantId + "/export?mask=false")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(data.path("accounts").get(0).path("email").asText()).contains("@");
    }

    @Test
    void tenantAdminCanExportButNotPlaintext() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme")))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export?mask=false")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme")))
                .andExpect(status().isForbidden());
    }

    @Test
    void orgAdminAndMemberCannotExport() throws Exception {
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken("t-acme", orgId)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, memberToken("t-acme", orgId)))
                .andExpect(status().isForbidden());
    }

    @Test
    void offboardTerminatesAndSoftDeletesWithAudit() throws Exception {
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"contract ended\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"))
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());

        assertThat(auditCount(tenantId, "TENANT_OFFBOARDED")).isEqualTo(1);

        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void offboardRequiresPlatformAdminAndValidState() throws Exception {
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdminToken("t-acme"))
                        .contentType("application/json")
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());

        setTenantStatus(tenantId, "DRAFT");
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"draft\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    void offboardRequiresReason() throws Exception {
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType("application/json")
                        .content("{\"reason\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
