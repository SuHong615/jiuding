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
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/18 section 2: export bundle content and size limits (100 rating rows, 500 audits). */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantExportStructureTest extends AbstractTenantDataWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanTenantDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme", null);
        createOrg(tenantId, "HQ", "Headquarters", "COMPANY");
        createAccount(tenantId, "alice");
        createAccount(tenantId, "bob");
        assessRating(tenantId);
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void exportBundleContainsTenantOrgAccountsRatingAndAudits() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();

        String disposition = result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(disposition).contains("attachment")
                .contains("tenant-t-acme-export-")
                .contains(".json");
        assertThat(result.getResponse().getContentType()).contains("application/json");

        JsonNode bundle = read(result);
        assertThat(bundle.path("exportedAt").asText()).isNotBlank();
        assertThat(bundle.path("tenant").path("id").asText()).isEqualTo(tenantId);
        assertThat(bundle.path("tenant").path("tenantCode").asText()).isEqualTo("t-acme");

        JsonNode orgUnits = bundle.path("orgUnits");
        assertThat(orgUnits.size()).isEqualTo(1);
        assertThat(orgUnits.get(0).path("code").asText()).isEqualTo("HQ");
        assertThat(orgUnits.get(0).path("path").asText()).isNotBlank();
        assertThat(orgUnits.get(0).path("depth").asInt()).isZero();

        JsonNode accounts = bundle.path("accounts");
        assertThat(accounts.size()).isEqualTo(2);
        assertThat(accounts.get(0).path("username").asText()).isNotBlank();
        assertThat(accounts.get(0).has("email")).isTrue();
        assertThat(accounts.get(0).path("mustChangePassword").asBoolean()).isFalse();

        assertThat(bundle.path("rating").path("current").path("grade").asText()).isNotBlank();
        assertThat(bundle.path("rating").path("history").size()).isGreaterThanOrEqualTo(1);
        assertThat(bundle.path("audits").size()).isGreaterThanOrEqualTo(4);
        assertThat(bundle.path("audits").get(0).path("action").asText()).isNotBlank();
        assertThat(auditCount(tenantId, "TENANT_EXPORTED")).isEqualTo(1);
    }

    @Test
    void ratingHistoryIsCappedAt100AndAuditsAt500() throws Exception {
        for (int index = 0; index < 120; index++) {
            jdbc.update("INSERT INTO tenant_ratings (id, tenant_id, grade, score, dim_business,"
                            + " dim_compliance, dim_financial, dim_security, dim_service, source,"
                            + " assessed_by, note, created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenantId, "A", 70 + index % 20, 70, 70, 70, 70, 70,
                    "SKILL", "tester", "bulk", TimeUtil.now().minusSeconds(index));
        }
        for (int index = 0; index < 600; index++) {
            jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                            + " VALUES (?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), tenantId, TimeUtil.now().minusSeconds(index),
                    "tester", "BULK_ACTION", "bulk " + index);
        }

        JsonNode bundle = read(mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(bundle.path("rating").path("history").size()).isEqualTo(100);
        assertThat(bundle.path("audits").size()).isEqualTo(500);
    }

    @Test
    void tenantWithoutRatingExportsNullCurrentAndEmptyHistory() throws Exception {
        String freshId = createTenant("t-fresh", "Fresh", null);
        JsonNode bundle = read(mockMvc.perform(get("/api/tenants/" + freshId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn());
        assertThat(bundle.path("rating").path("current").isNull()).isTrue();
        assertThat(bundle.path("rating").path("history").size()).isZero();
    }
}
