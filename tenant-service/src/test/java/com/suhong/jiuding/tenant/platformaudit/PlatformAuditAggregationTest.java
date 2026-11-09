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
package com.suhong.jiuding.tenant.platformaudit;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/21 section 2: cross-tenant audit aggregation, filtering, ordering and pagination. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAuditAggregationTest extends AbstractIdentityWebTest {

    private String alpha;
    private String bravo;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        alpha = createTenant("t-alpha", "Alpha");
        bravo = createTenant("t-bravo", "Bravo");
        activateTenant(alpha);
        activateTenant(bravo);
        jdbc.update("DELETE FROM tenant_audits");

        insertAudit(alpha, TimeUtil.now().minusHours(1), "alice", "ACCOUNT_CREATED",
                "created alpha account");
        insertAudit(alpha, TimeUtil.now().minusHours(2), "bob", "ACCOUNT_UPDATED",
                "updated alpha account");
        insertAudit(bravo, TimeUtil.now().minusHours(3), "carol", "ACCOUNT_CREATED",
                "created bravo account with, comma");
        // Older than the default 30 day window.
        insertAudit(alpha, TimeUtil.now().minusDays(40), "dave", "OLD_ACTION", "too old");
    }

    @Test
    void aggregatesAcrossTenantsWithTenantMetadataAndDescOrder() throws Exception {
        JsonNode data = read(mockMvc.perform(get("/api/platform/audits")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");

        assertThat(data.path("total").asInt()).isEqualTo(3);
        JsonNode items = data.path("items");
        assertThat(items.size()).isEqualTo(3);
        assertThat(items.get(0).path("action").asText()).isEqualTo("ACCOUNT_CREATED");
        assertThat(items.get(0).path("tenantId").asText()).isEqualTo(alpha);
        assertThat(items.get(0).path("tenantCode").asText()).isEqualTo("t-alpha");
        assertThat(items.get(0).path("tenantName").asText()).isEqualTo("Alpha");
        assertThat(items.get(0).path("at").asText()).isNotBlank();
        assertThat(items.get(0).path("actor").asText()).isEqualTo("alice");
        assertThat(items.get(0).path("detail").asText()).isEqualTo("created alpha account");
        assertThat(items.get(1).path("at").asText())
                .isGreaterThan(items.get(2).path("at").asText());
    }

    @Test
    void filtersByTenantActionKeywordAndTimeRange() throws Exception {
        mockMvc.perform(get("/api/platform/audits?tenantId=" + alpha)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/platform/audits?tenantId=t-bravo")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
        mockMvc.perform(get("/api/platform/audits?action=ACCOUNT_CREATED")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));
        mockMvc.perform(get("/api/platform/audits?keyword=comma")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("t-bravo"));
        mockMvc.perform(get("/api/platform/audits?keyword=alpha")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2));

        String from = TimeUtil.iso(TimeUtil.now().minusMinutes(90));
        mockMvc.perform(get("/api/platform/audits?from=" + from)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("ACCOUNT_CREATED"));
    }

    @Test
    void paginatesAndValidatesParameters() throws Exception {
        JsonNode first = read(mockMvc.perform(get("/api/platform/audits?page=0&size=2")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(first.path("items").size()).isEqualTo(2);
        assertThat(first.path("total").asInt()).isEqualTo(3);
        assertThat(first.path("page").asInt()).isZero();
        assertThat(first.path("size").asInt()).isEqualTo(2);
        JsonNode second = read(mockMvc.perform(get("/api/platform/audits?page=1&size=2")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(second.path("items").size()).isEqualTo(1);

        mockMvc.perform(get("/api/platform/audits?from=2026-01-02T00:00:00Z&to=2026-01-01T00:00:00Z")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/platform/audits?size=100000")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/platform/audits?page=-1")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/api/platform/audits?from=not-a-timestamp")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isBadRequest());
    }

    private void insertAudit(String tenantId, java.time.LocalDateTime at, String actor,
                             String action, String detail) {
        jdbc.update("INSERT INTO tenant_audits (id, tenant_id, at, actor, action, detail)"
                        + " VALUES (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), tenantId, at, actor, action, detail);
    }
}
