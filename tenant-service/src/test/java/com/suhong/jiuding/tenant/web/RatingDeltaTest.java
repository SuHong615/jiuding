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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/14 section 3.3: rating deltas ({@code previousGrade/previousScore/scoreDelta/gradeChanged}),
 * consecutive history deltas and the {@code RATING_GRADE_CHANGED} audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RatingDeltaTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        MvcResult created = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "t-acme",
                                "name", "Acme",
                                "contactEmail", "alice@acme.io",
                                "plan", "FREE"))))
                .andExpect(status().isOk())
                .andReturn();
        tenantId = read(created).path("data").path("id").asText();
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void assessmentExposesGradeDeltaAndAuditsGradeChanges() throws Exception {
        JsonNode first = assess();
        assertThat(first.path("grade").asText()).isEqualTo("B");
        assertThat(first.path("score").asInt()).isEqualTo(69);
        assertThat(first.path("previousGrade").isNull()).isTrue();
        assertThat(first.path("previousScore").isNull()).isTrue();
        assertThat(first.path("scoreDelta").asInt()).isZero();
        assertThat(first.path("gradeChanged").asBoolean()).isFalse();

        // Upgrade the tenant, then re-assess: the grade must jump B -> AAA.
        jdbc.update("UPDATE tenants SET plan = 'ENTERPRISE', quota_seats = 1000,"
                + " quota_storage_gb = 1000, contact_name = 'Alice' WHERE id = ?", tenantId);

        JsonNode second = assess();
        assertThat(second.path("grade").asText()).isEqualTo("AAA");
        assertThat(second.path("score").asInt()).isEqualTo(92);
        assertThat(second.path("previousGrade").asText()).isEqualTo("B");
        assertThat(second.path("previousScore").asInt()).isEqualTo(69);
        assertThat(second.path("scoreDelta").asInt()).isEqualTo(23);
        assertThat(second.path("gradeChanged").asBoolean()).isTrue();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = 'RATING_GRADE_CHANGED'", Long.class, tenantId))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT detail FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = 'RATING_GRADE_CHANGED'", String.class, tenantId))
                .contains("B", "AAA");

        // Current rating also carries the delta.
        JsonNode current = read(mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings/current")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(current.path("grade").asText()).isEqualTo("AAA");
        assertThat(current.path("previousGrade").asText()).isEqualTo("B");
        assertThat(current.path("scoreDelta").asInt()).isEqualTo(23);

        // History is newest first with consecutive deltas; the oldest row has no previous.
        MvcResult historyResult = mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode history = read(historyResult).path("data");
        assertThat(history.path("total").asInt()).isEqualTo(2);
        JsonNode newest = history.path("items").get(0);
        JsonNode oldest = history.path("items").get(1);
        assertThat(newest.path("grade").asText()).isEqualTo("AAA");
        assertThat(newest.path("previousGrade").asText()).isEqualTo("B");
        assertThat(newest.path("gradeChanged").asBoolean()).isTrue();
        assertThat(oldest.path("grade").asText()).isEqualTo("B");
        assertThat(oldest.path("previousGrade").isNull()).isTrue();
        assertThat(oldest.path("gradeChanged").asBoolean()).isFalse();
    }

    @Test
    void manualOverrideReportsDeltaAgainstTheCurrentRating() throws Exception {
        assess();
        jdbc.update("UPDATE tenants SET plan = 'ENTERPRISE', quota_seats = 1000,"
                + " quota_storage_gb = 1000, contact_name = 'Alice' WHERE id = ?", tenantId);
        JsonNode assessed = assess();
        assertThat(assessed.path("grade").asText()).isEqualTo("AAA");

        JsonNode overridden = read(mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/override")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "grade", "A", "score", 72, "reason", "manual recalibration"))))
                .andExpect(status().isOk()).andReturn()).path("data");
        assertThat(overridden.path("grade").asText()).isEqualTo("A");
        assertThat(overridden.path("previousGrade").asText()).isEqualTo("AAA");
        assertThat(overridden.path("previousScore").asInt()).isEqualTo(92);
        assertThat(overridden.path("scoreDelta").asInt()).isEqualTo(-20);
        assertThat(overridden.path("gradeChanged").asBoolean()).isTrue();
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode assess() throws Exception {
        return read(mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk()).andReturn()).path("data");
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
