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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TenantSoftDeleteTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JwtService jwtService;
    @Autowired
    private JdbcTemplate jdbc;

    private String adminToken;
    private String operatorToken;

    @BeforeEach
    void setUp() {
        TenantFixtures.clean(jdbc);
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
        operatorToken = bearer(new Principal("u-op", "operator", "t-acme", Set.of("TENANT_ADMIN"), "JWT"));
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void activeTenantCannotBeDeleted() throws Exception {
        String id = createTenant("t-active", "Active Tenant");
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", id);

        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ACTIVE")));

        mockMvc.perform(get("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedAt").doesNotExist());
    }

    @Test
    void softDeletedTenantDisappearsFromDefaultListButIsVisibleIncludingDeleted() throws Exception {
        String id = createTenant("t-draft", "Draft Tenant");

        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.deletedAt").isNotEmpty());

        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get("/api/tenants?includeDeleted=true").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].tenantCode").value("t-draft"))
                .andExpect(jsonPath("$.data.items[0].deletedAt").isNotEmpty());

        mockMvc.perform(get("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));

        // Deleting twice is a conflict, not a silent success.
        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value(40900));

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits WHERE tenant_id = ?"
                + " AND action = 'TENANT_DELETED'", Long.class, id)).isEqualTo(1);
    }

    @Test
    void onlyPlatformAdminsCanDeleteOrListDeletedTenants() throws Exception {
        String id = createTenant("t-draft", "Draft Tenant");

        mockMvc.perform(delete("/api/tenants/" + id).header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(get("/api/tenants?includeDeleted=true")
                        .header(HttpHeaders.AUTHORIZATION, operatorToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        mockMvc.perform(delete("/api/tenants/" + id))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }

    private String createTenant(String code, String name) throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode data = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        return data.path("id").asText();
    }

    private String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
