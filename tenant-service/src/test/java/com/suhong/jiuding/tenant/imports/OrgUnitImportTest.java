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
package com.suhong.jiuding.tenant.imports;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.repository.OrgUnitRepository;
import com.suhong.jiuding.tenant.repository.OrgUnitRow;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Organization unit bulk import: topology, duplicate skipping, row isolation, limits, RBAC. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrgUnitImportTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TenantRepository tenants;
    @Autowired
    private OrgUnitRepository orgUnits;

    private String adminToken;
    private String tenantId;
    private String tenantCode;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM onboarding_applications");
        TenantFixtures.clean(jdbc);
        adminToken = "Bearer " + jwtService.issue(
                new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        tenantCode = "t-org-imp-" + (System.nanoTime() % 100000);
        tenantId = createTenant(tenantCode);
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void createsTopologicalTreeAndSkipsDuplicates() throws Exception {
        String csv = """
                parentCode,code,name,type,sortOrder,managerUsername
                ENG,TEAM1,Team One,TEAM,2,
                ,HQ,Headquarters,COMPANY,0,
                HQ,ENG,Engineering,DEPARTMENT,1,
                """;
        JsonNode first = uploadOrgCsv(csv);
        assertThat(first.path("total").asInt()).isEqualTo(3);
        assertThat(first.path("imported").asInt()).isEqualTo(3);
        assertThat(first.path("skipped").asInt()).isZero();
        assertThat(first.path("failed").asInt()).isZero();

        TenantRow tenant = tenants.findByCode(tenantCode).orElseThrow();
        List<OrgUnitRow> rows = orgUnits.list(tenant.id(), null, null);
        OrgUnitRow hq = byCode(rows, "HQ");
        OrgUnitRow eng = byCode(rows, "ENG");
        OrgUnitRow team = byCode(rows, "TEAM1");
        assertThat(hq.depth()).isZero();
        assertThat(eng.depth()).isEqualTo(1);
        assertThat(eng.path()).isEqualTo(hq.path() + "/" + eng.id());
        assertThat(team.depth()).isEqualTo(2);
        assertThat(team.path()).startsWith(eng.path() + "/");

        JsonNode second = uploadOrgCsv(csv);
        assertThat(second.path("total").asInt()).isEqualTo(3);
        assertThat(second.path("imported").asInt()).isZero();
        assertThat(second.path("skipped").asInt()).isEqualTo(3);
        assertThat(second.path("failed").asInt()).isZero();
    }

    @Test
    void jsonItemsAreAccepted() throws Exception {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", "HQ");
        item.put("name", "Headquarters");
        item.put("type", "COMPANY");
        item.put("sortOrder", 0);
        List<Map<String, Object>> items = new ArrayList<>();
        items.add(item);
        Map<String, Object> child = new LinkedHashMap<>();
        child.put("parentCode", "HQ");
        child.put("code", "ENG");
        child.put("name", "Engineering");
        child.put("type", "DEPARTMENT");
        items.add(child);

        JsonNode data = read(mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units/import")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", items))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");
        assertThat(data.path("imported").asInt()).isEqualTo(2);
        assertThat(data.path("failed").asInt()).isZero();
    }

    @Test
    void invalidRowsAreReportedWithoutAbortingTheBatch() throws Exception {
        String csv = """
                parentCode,code,name,type,sortOrder,managerUsername
                ,HQ,Headquarters,COMPANY,0,
                ,BAD,Bad Row,,
                NOPE,ORPHAN,Orphan,TEAM,0,
                """;
        JsonNode data = uploadOrgCsv(csv);
        assertThat(data.path("total").asInt()).isEqualTo(3);
        assertThat(data.path("imported").asInt()).isEqualTo(1);
        assertThat(data.path("failed").asInt()).isEqualTo(2);
        assertThat(data.path("errors").size()).isEqualTo(2);
        assertThat(data.path("errors").get(0).path("row").asInt()).isEqualTo(2);
        assertThat(data.path("errors").get(0).path("message").asText()).containsIgnoringCase("type");
        assertThat(data.path("errors").get(1).path("message").asText()).contains("NOPE");
    }

    @Test
    void moreThanOneThousandRowsIsRejected() throws Exception {
        List<Map<String, Object>> items = new ArrayList<>();
        for (int index = 0; index < 1001; index++) {
            items.add(Map.of("code", "C" + index, "name", "Node " + index, "type", "TEAM"));
        }
        mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units/import")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("items", items))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(40001));
    }

    @Test
    void orgAdminCannotBulkImport() throws Exception {
        String orgId = createOrg("HQ", "Headquarters", "COMPANY");
        String orgAdminToken = "Bearer " + jwtService.issue(new Principal(
                "org-admin-account", "orgadmin", tenantId, Set.of("ORG_ADMIN"), "JWT", orgId));

        mockMvc.perform(multipart("/api/tenants/" + tenantId + "/org-units/import")
                        .file(csvFile("org-units.csv", "parentCode,code,name,type\n,HQ2,Forbidden,TEAM\n"))
                        .header(HttpHeaders.AUTHORIZATION, orgAdminToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode uploadOrgCsv(String csv) throws Exception {
        return read(mockMvc.perform(multipart("/api/tenants/" + tenantId + "/org-units/import")
                        .file(csvFile("org-units.csv", csv))
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn()).path("data");
    }

    private String createTenant(String code) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", "Import Tenant");
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private String createOrg(String code, String name, String type) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    private MockMultipartFile csvFile(String name, String csv) {
        return new MockMultipartFile("file", name, "text/csv", csv.getBytes(StandardCharsets.UTF_8));
    }

    private OrgUnitRow byCode(List<OrgUnitRow> rows, String code) {
        return rows.stream().filter(row -> code.equals(row.code())).findFirst().orElseThrow();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
