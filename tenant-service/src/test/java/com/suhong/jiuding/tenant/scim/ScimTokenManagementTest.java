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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 7.1: platform SCIM token management (create returns the clear text token
 * only once, SHA-256 hash persistence, revocation) and token based SCIM authentication.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScimTokenManagementTest extends AbstractScimWebTest {

    @Autowired
    private JwtService jwtService;

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanScimDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void createReturnsClearTextOnceAndOnlyTheHashIsStored() throws Exception {
        JsonNode created = createScimToken(tenantId, "okta");
        String token = created.path("token").asText();
        String id = created.path("id").asText();

        assertThat(token).startsWith("scim_");
        assertThat(created.path("prefix").asText()).isEqualTo(token.substring(0, 13));
        assertThat(created.path("status").asText()).isEqualTo("ACTIVE");

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT token_hash, token_prefix, status FROM scim_tokens WHERE id = ?", id);
        assertThat(stored.get("token_hash")).isNotEqualTo(token);
        assertThat(String.valueOf(stored.get("token_hash"))).hasSize(64).doesNotContain(token);
        assertThat(stored.get("token_prefix")).isEqualTo(token.substring(0, 13));
        assertThat(scimTokenAuditCount(tenantId, "SCIM_TOKEN_CREATED")).isEqualTo(1);

        MvcResult list = mockMvc.perform(get("/api/platform/scim-tokens?tenantId=" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andReturn();
        JsonNode tokenView = read(list).path("data").get(0);
        assertThat(tokenView.has("token")).isFalse();
        assertThat(tokenView.has("tokenHash")).isFalse();
        assertThat(tokenView.path("id").asText()).isEqualTo(id);

        // The token authenticates the SCIM endpoints and its last_used_at is tracked.
        mockMvc.perform(scimGet("/scim/v2/Users", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(0));
        assertThat(jdbc.queryForObject("SELECT last_used_at FROM scim_tokens WHERE id = ?",
                Object.class, id)).isNotNull();
    }

    @Test
    void revokeDisablesTheTokenAndIsAudited() throws Exception {
        JsonNode created = createScimToken(tenantId, "okta");
        String token = created.path("token").asText();
        String id = created.path("id").asText();

        mockMvc.perform(delete("/api/platform/scim-tokens/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"))
                .andExpect(jsonPath("$.data.revokedAt").isNotEmpty());
        assertThat(scimTokenAuditCount(tenantId, "SCIM_TOKEN_REVOKED")).isEqualTo(1);

        mockMvc.perform(scimGet("/scim/v2/Users", token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.schemas[0]")
                        .value("urn:ietf:params:scim:api:messages:2.0:Error"));

        // Revoking twice is idempotent.
        mockMvc.perform(delete("/api/platform/scim-tokens/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REVOKED"));
        assertThat(scimTokenAuditCount(tenantId, "SCIM_TOKEN_REVOKED")).isEqualTo(1);
    }

    @Test
    void tokenManagementRequiresPlatformAdminJwt() throws Exception {
        String tenantAdmin = bearer(new Principal("u-tadmin", "tadmin", "t-acme",
                java.util.Set.of("TENANT_ADMIN"), "JWT"));
        mockMvc.perform(get("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdmin))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdmin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId, "name", "nope"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/scim-tokens"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", "missing-tenant", "name", "nope"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantId", tenantId, "name", " "))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(delete("/api/platform/scim-tokens/not-a-token")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidBearerTokensAreRejectedWithScimError() throws Exception {
        mockMvc.perform(get("/scim/v2/Users"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").isNotEmpty());
        mockMvc.perform(scimGet("/scim/v2/Users", "scim_not-a-real-token"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/scim/v2/Users").header(HttpHeaders.AUTHORIZATION, "Bearer regular-jwt"))
                .andExpect(status().isUnauthorized());
    }
}
