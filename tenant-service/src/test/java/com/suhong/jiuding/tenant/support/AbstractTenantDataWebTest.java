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
package com.suhong.jiuding.tenant.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared fixtures for the v8 tenant export / offboarding tests. */
public abstract class AbstractTenantDataWebTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected JdbcTemplate jdbc;

    protected String adminToken;

    protected void cleanTenantDatabase() {
        TenantFixtures.clean(jdbc);
    }

    protected void initAdminToken() {
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    protected String createTenant(String code, String name, Map<String, Object> extra) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("tenantCode", code);
        body.put("name", name);
        body.put("contactEmail", code + "@acme.io");
        body.put("plan", "PRO");
        if (extra != null) {
            body.putAll(extra);
        }
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    protected String createOrg(String tenantId, String code, String name, String type) throws Exception {
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

    protected String createAccount(String tenantId, String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("displayName", "User " + username);
        body.put("email", username + "@acme.io");
        body.put("phone", "13812341234");
        body.put("role", "MEMBER");
        body.put("initialPassword", "Passw0rd123");
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("account").path("id").asText();
    }

    protected void assessRating(String tenantId) throws Exception {
        mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    protected void setTenantStatus(String tenantId, String status) {
        jdbc.update("UPDATE tenants SET status = ? WHERE id = ?", status, tenantId);
    }

    protected String platformToken() {
        return bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    protected String tenantAdminToken(String tenantCode) {
        return bearer(new Principal("u-tadmin", "tadmin", tenantCode, Set.of("TENANT_ADMIN"), "JWT"));
    }

    protected String auditorToken(String tenantCode) {
        return bearer(new Principal("u-aud", "auditor", tenantCode, Set.of("AUDITOR"), "JWT"));
    }

    protected String orgAdminToken(String tenantCode, String orgUnitId) {
        return bearer(new Principal("u-oadmin", "oadmin", tenantCode, Set.of("ORG_ADMIN"), "JWT",
                orgUnitId));
    }

    protected String memberToken(String tenantCode, String orgUnitId) {
        return bearer(new Principal("u-mem", "member", tenantCode, Set.of("MEMBER"), "JWT",
                orgUnitId));
    }

    protected long auditCount(String tenantId, String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = ? AND action = ?", Long.class, tenantId, action);
        return count == null ? 0L : count;
    }

    protected JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    protected String bearer(Principal principal) {
        return "Bearer " + jwtService.issue(principal);
    }
}
