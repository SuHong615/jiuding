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
import com.suhong.jiuding.common.security.Headers;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantApiContractTest {

    private static final String INTERNAL_TOKEN = "dev-internal-token";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM tenant_audits");
        jdbc.update("DELETE FROM collect_jobs");
        jdbc.update("DELETE FROM tenants");
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void createListAndGetFollowTheContract() throws Exception {
        String id = createTenant("t-acme", "Acme", false);

        mockMvc.perform(authed(get("/api/tenants")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.page").value(0))
                .andExpect(jsonPath("$.data.size").value(20))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("t-acme"));

        MvcResult result = mockMvc.perform(authed(get("/api/tenants/" + id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = read(result).path("data");
        assertThat(data.path("id").asText()).isEqualTo(id);
        assertThat(data.path("status").asText()).isEqualTo("DRAFT");
        assertThat(data.path("plan").asText()).isEqualTo("PRO");
        assertThat(data.path("region").asText()).isEqualTo("cn-shanghai");
        assertThat(data.path("isolationMode").asText()).isEqualTo("SHARED_SCHEMA");
        assertThat(data.path("source").asText()).isEqualTo("MANUAL");
        assertThat(data.path("riskLevel").isNull()).isTrue();
        assertThat(data.path("version").asLong()).isZero();
        assertThat(data.path("createdAt").asText()).isNotBlank();
        assertThat(data.path("updatedAt").asText()).isNotBlank();

        mockMvc.perform(authed(get("/api/tenants/does-not-exist")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void createValidatesInputAndRejectsDuplicateCodes() throws Exception {
        mockMvc.perform(authed(post("/api/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"t-invalid\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        mockMvc.perform(authed(post("/api/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"t-invalid2\",\"name\":\"X\",\"plan\":\"GOLD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));

        createTenant("t-dup", "Duplicate", false);
        mockMvc.perform(authed(post("/api/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-dup", "Duplicate Again")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));
    }

    @Test
    void updateSubmitAndDegradationAreEnforced() throws Exception {
        String id = createTenant("t-submit", "Submit Me", false);

        mockMvc.perform(authed(put("/api/tenants/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-submit", "Submit Me Renamed")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Submit Me Renamed"))
                .andExpect(jsonPath("$.data.version").value(1));

        // approval-service is unreachable in the test profile -> degraded, never 5xx.
        mockMvc.perform(authed(post("/api/tenants/" + id + "/submit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"))
                .andExpect(jsonPath("$.data.submittedAt").isNotEmpty());

        MvcResult audits = mockMvc.perform(authed(get("/api/tenants/" + id + "/audits")))
                .andExpect(status().isOk())
                .andReturn();
        List<String> actions = new ArrayList<>();
        read(audits).path("data").forEach(node -> actions.add(node.path("action").asText()));
        assertThat(actions).contains("UPDATED", "SUBMITTED", "APPROVAL_DEGRADED");

        mockMvc.perform(authed(put("/api/tenants/" + id))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tenantJson("t-submit", "Too Late")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));

        mockMvc.perform(authed(post("/api/tenants/" + id + "/submit")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));
    }

    @Test
    void internalTokenLifecycleAndIdempotency() throws Exception {
        String id = createTenant("t-life", "Lifecycle", false);
        mockMvc.perform(authed(post("/api/tenants/" + id + "/submit")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        // X-Internal-Token is mandatory and validated by tenancy-common's AuthFilter.
        mockMvc.perform(get("/internal/tenants/" + id))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/tenants/" + id).header(Headers.INTERNAL_TOKEN, "wrong-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/internal/tenants/" + id).header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.plan").value("PRO"))
                .andExpect(jsonPath("$.data.status").value("SUBMITTED"));

        mockMvc.perform(internal(patch("/internal/tenants/" + id + "/risk"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskLevel\":\"HIGH\",\"riskScore\":82}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.riskLevel").value("HIGH"))
                .andExpect(jsonPath("$.data.riskScore").value(82));

        internalStatus(id, "PENDING_APPROVAL", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_APPROVAL"));
        internalStatus(id, "APPROVED", "auto")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.decidedAt").isNotEmpty());
        // Idempotent retry of the same status must not fail.
        internalStatus(id, "APPROVED", "auto")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        internalStatus(id, "PROVISIONING", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PROVISIONING"));
        internalStatus(id, "ACTIVE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.activatedAt").isNotEmpty());
        internalStatus(id, "ACTIVE", null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));

        // Public lifecycle operations remain strict.
        mockMvc.perform(authed(post("/api/tenants/" + id + "/suspend"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"compliance\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUSPENDED"));
        mockMvc.perform(authed(post("/api/tenants/" + id + "/suspend"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));
        mockMvc.perform(authed(post("/api/tenants/" + id + "/activate")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ACTIVE"));
        mockMvc.perform(authed(post("/api/tenants/" + id + "/terminate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"cleanup\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"));
        mockMvc.perform(authed(post("/api/tenants/" + id + "/terminate"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"again\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));

        // Illegal internal transition: DRAFT cannot jump straight to ACTIVE.
        String draftId = createTenant("t-draft", "Draft Only", false);
        internalStatus(draftId, "ACTIVE", null)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(54000));
        mockMvc.perform(internal(patch("/internal/tenants/" + draftId + "/risk"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"riskLevel\":\"NOPE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void inlineCollectionDeduplicatesAndExposesJobs() throws Exception {
        createTenant("t-existing", "Existing", false);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sourceName", "batch-1");
        body.put("mode", "MANUAL_BATCH");
        body.put("autoSubmit", false);
        body.put("records", List.of(
                Map.of("tenantCode", "t-existing", "name", "Duplicate"),
                Map.of("tenantCode", "t-batch-1", "name", "Batch One"),
                Map.of("tenantCode", "bad code", "name", "Invalid")));

        MvcResult result = mockMvc.perform(authed(post("/api/tenants/collect"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode job = read(result).path("data");
        assertThat(job.path("status").asText()).isEqualTo("PARTIAL");
        assertThat(job.path("total").asInt()).isEqualTo(3);
        assertThat(job.path("imported").asInt()).isEqualTo(1);
        assertThat(job.path("duplicated").asInt()).isEqualTo(1);
        assertThat(job.path("failed").asInt()).isEqualTo(1);
        assertThat(job.path("errors").size()).isEqualTo(1);
        assertThat(job.path("finishedAt").asText()).isNotBlank();
        String jobId = job.path("id").asText();

        mockMvc.perform(authed(get("/api/tenants/collect/jobs")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(jobId));
        mockMvc.perform(authed(get("/api/tenants/collect/jobs/" + jobId)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PARTIAL"));
        mockMvc.perform(authed(get("/api/tenants/collect/jobs/missing")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void csvUploadCreatesCollectionJob() throws Exception {
        String csv = "tenantCode,name,contactEmail,plan\n"
                + "t-csv-1,Csv One,csv1@acme.io,PRO\n"
                + "t-csv-2,\"Csv, Two\",csv2@acme.io,FREE\n";
        MockMultipartFile file = new MockMultipartFile("file", "tenants.csv", "text/csv",
                csv.getBytes(StandardCharsets.UTF_8));

        MvcResult result = mockMvc.perform(multipart("/api/tenants/collect/csv")
                        .file(file)
                        .param("sourceName", "csv-file")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode job = read(result).path("data");
        assertThat(job.path("mode").asText()).isEqualTo("CSV");
        assertThat(job.path("status").asText()).isEqualTo("SUCCEEDED");
        assertThat(job.path("total").asInt()).isEqualTo(2);
        assertThat(job.path("imported").asInt()).isEqualTo(2);
    }

    @Test
    void overviewAndFiltersFollowTheContract() throws Exception {
        createTenant("t-alpha", "Alpha", false);
        createTenant("t-beta", "Beta", false);

        mockMvc.perform(authed(get("/api/tenants/overview")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.active").value(0))
                .andExpect(jsonPath("$.data.pending").value(0))
                .andExpect(jsonPath("$.data.byStatus.DRAFT").value(2))
                .andExpect(jsonPath("$.data.byPlan.PRO").value(2))
                .andExpect(jsonPath("$.data.byRisk.UNKNOWN").value(2));

        mockMvc.perform(authed(get("/api/tenants?status=DRAFT&keyword=beta")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("t-beta"));

        mockMvc.perform(authed(get("/api/tenants?plan=FREE")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(authed(get("/api/tenants?size=0")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
        mockMvc.perform(authed(get("/api/tenants?status=NOPE")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    // ------------------------------------------------------------------ helpers

    private String createTenant(String code, String name, boolean autoSubmit) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        body.put("autoSubmit", autoSubmit);
        MvcResult result = mockMvc.perform(authed(post("/api/tenants"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String tenantJson(String code, String name) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
        body.put("contactEmail", code + "@acme.io");
        return objectMapper.writeValueAsString(body);
    }

    private org.springframework.test.web.servlet.ResultActions internalStatus(String id, String status, String reason)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", status);
        body.put("reason", reason);
        return mockMvc.perform(internal(post("/internal/tenants/" + id + "/status"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private MockHttpServletRequestBuilder internal(MockHttpServletRequestBuilder builder) {
        return builder.header(Headers.INTERNAL_TOKEN, INTERNAL_TOKEN);
    }

    private MockHttpServletRequestBuilder authed(MockHttpServletRequestBuilder builder) {
        return builder.header(HttpHeaders.AUTHORIZATION, adminToken);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
