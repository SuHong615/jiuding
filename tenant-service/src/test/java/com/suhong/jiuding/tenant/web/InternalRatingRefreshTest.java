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
package com.suhong.jiuding.tenant.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.Headers;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.service.RatingRefreshService;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/14 section 3.4: the internal CronJob entry point triggers the rating refresh and
 * reports {@code {refreshed, skipped, failed}}; only ACTIVE, non deleted tenants are
 * refreshed and the endpoint is protected by the internal token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class InternalRatingRefreshTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private RatingRefreshService refreshService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String activeOneId;
    private String activeTwoId;
    private String draftId;
    private String deletedId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        activeOneId = createTenant("t-active-1", "Active One");
        activeTwoId = createTenant("t-active-2", "Active Two");
        draftId = createTenant("t-draft", "Draft");
        deletedId = createTenant("t-deleted", "Deleted");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id IN (?, ?)", activeOneId, activeTwoId);
        mockMvc.perform(delete("/api/tenants/" + deletedId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void internalRefreshReturnsRefreshedSkippedFailedCounters() throws Exception {
        mockMvc.perform(post("/internal/ratings/refresh"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/ratings/refresh").header(Headers.INTERNAL_TOKEN, "wrong-token"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/internal/ratings/refresh").header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.refreshed").value(2))
                .andExpect(jsonPath("$.data.skipped").value(1))
                .andExpect(jsonPath("$.data.failed").value(0));

        assertThat(ratingCount(activeOneId)).isEqualTo(1);
        assertThat(ratingCount(activeTwoId)).isEqualTo(1);
        assertThat(ratingCount(draftId)).isZero();
        assertThat(ratingCount(deletedId)).isZero();
        assertThat(auditCount("RATING_REFRESHED")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT actor FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'RATING_REFRESHED'", String.class, activeOneId)).isEqualTo("system");

        // A second CronJob invocation refreshes again (append-only history).
        mockMvc.perform(post("/internal/ratings/refresh").header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.refreshed").value(2))
                .andExpect(jsonPath("$.data.failed").value(0));
        assertThat(ratingCount(activeOneId)).isEqualTo(2);
    }

    @Test
    void batchLimitIsReportedAsSkipped() {
        RatingRefreshService.RefreshStats stats = refreshService.refresh(1);
        assertThat(stats.refreshed()).isEqualTo(1);
        assertThat(stats.failed()).isZero();
        // 3 non-deleted tenants (2 ACTIVE + 1 DRAFT) with a batch limit of 1 -> 2 skipped.
        assertThat(stats.skipped()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ helpers

    private long ratingCount(String tenantId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_ratings WHERE tenant_id = ?",
                Long.class, tenantId);
        return count == null ? 0L : count;
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE action = ?",
                Long.class, action);
        return count == null ? 0L : count;
    }

    private String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", code,
                                "name", name,
                                "contactEmail", code + "@acme.io",
                                "plan", "PRO"))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }
}
