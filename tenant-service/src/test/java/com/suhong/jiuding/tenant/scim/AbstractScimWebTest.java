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
package com.suhong.jiuding.tenant.scim;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.tenant.support.TenantFixtures;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Shared fixtures for the SCIM (v6B) web tests. */
abstract class AbstractScimWebTest {

    @Autowired
    protected MockMvc mockMvc;
    @Autowired
    protected ObjectMapper objectMapper;
    @Autowired
    protected JwtService jwtService;
    @Autowired
    protected JdbcTemplate jdbc;

    protected String adminToken;

    protected void cleanScimDatabase() {
        TenantFixtures.clean(jdbc);
        jdbc.update("DELETE FROM scim_tokens");
    }

    protected void initAdminToken() {
        adminToken = bearer(new Principal("u-admin", "admin", null, Set.of("PLATFORM_ADMIN"), "JWT"));
    }

    protected String createTenant(String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", code, "name", name,
                                "contactEmail", code + "@acme.io", "plan", "PRO"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    protected JsonNode createScimToken(String tenantId, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId, "name", name))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data");
    }

    protected String createOrg(String tenantId, String code, String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", code, "name", name, "type", "COMPANY"))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).path("data").path("id").asText();
    }

    protected MockHttpServletRequestBuilder scimGet(String path, String token) {
        return get(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected MockHttpServletRequestBuilder scimPost(String path, String token, Object body) throws Exception {
        return post(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder scimPut(String path, String token, Object body) throws Exception {
        return put(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder scimPatch(String path, String token, Object body) throws Exception {
        return patch(path)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body));
    }

    protected MockHttpServletRequestBuilder scimDelete(String path, String token) {
        return delete(path).header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    protected Map<String, Object> member(String accountId) {
        Map<String, Object> member = new LinkedHashMap<>();
        member.put("value", accountId);
        return member;
    }

    protected long scimTokenAuditCount(String tenantId, String action) {
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
