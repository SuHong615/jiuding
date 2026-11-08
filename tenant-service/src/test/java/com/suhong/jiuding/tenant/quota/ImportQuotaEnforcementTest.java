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
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/17 section 4: bulk import checks the quota row by row; over-quota rows are recorded
 * as failed without aborting the batch.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportQuotaEnforcementTest extends AbstractQuotaWebTest {

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @BeforeEach
    void setUp() {
        cleanQuotaDatabase();
        initAdminToken();
    }

    @Test
    void accountImportStopsAtTheSeatQuotaPerRow() throws Exception {
        String tenantId = createTenant("t-import-seats", "Import Seats", Map.of("quotaSeats", 1));
        Map<String, Object> body = Map.of("items", List.of(
                accountItem("u1"),
                accountItem("u2"),
                accountItem("u3")));

        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts/import")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("total").asInt()).isEqualTo(3);
        assertThat(data.path("imported").asInt()).isEqualTo(1);
        assertThat(data.path("failed").asInt()).isEqualTo(2);
        assertThat(data.path("tempPasswords").size()).isEqualTo(1);

        String firstError = data.path("errors").get(0).path("message").asText();
        assertThat(firstError).contains("QUOTA_EXCEEDED", "used=", "limit=");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE tenant_id = ? AND deleted_at IS NULL",
                Long.class, tenantId)).isEqualTo(1);
    }

    @Test
    void orgUnitImportStopsAtTheOrganizationQuotaPerRow() throws Exception {
        String tenantId = createTenant("t-import-orgs", "Import Orgs", Map.of("quotaOrgUnits", 1));
        Map<String, Object> body = Map.of("items", List.of(
                orgItem(null, "HQ", "Headquarters"),
                orgItem("HQ", "ENG", "Engineering"),
                orgItem(null, "OPS", "Operations")));

        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units/import")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("total").asInt()).isEqualTo(3);
        assertThat(data.path("imported").asInt()).isEqualTo(1);
        assertThat(data.path("failed").asInt()).isEqualTo(2);
        assertThat(data.path("errors").get(0).path("message").asText())
                .contains("QUOTA_EXCEEDED", "used=1", "limit=1");
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM org_units WHERE tenant_id = ?", Long.class, tenantId))
                .isEqualTo(1);
    }

    private Map<String, Object> accountItem(String username) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("username", username);
        item.put("displayName", "User " + username);
        item.put("email", username + "@acme.io");
        item.put("role", "MEMBER");
        return item;
    }

    private Map<String, Object> orgItem(String parentCode, String code, String name) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("parentCode", parentCode);
        item.put("code", code);
        item.put("name", name);
        item.put("type", "COMPANY");
        return item;
    }
}
