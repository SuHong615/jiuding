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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 7.1: SCIM tokens are tenant scoped, so every Users/Groups access is
 * isolated to the tenant bound to the bearer token (cross-tenant ids are 404).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScimTenantIsolationTest extends AbstractScimWebTest {

    private String tenantA;
    private String tenantB;
    private String tokenA;
    private String tokenB;
    private String userA;
    private String userB;

    @BeforeEach
    void setUp() throws Exception {
        cleanScimDatabase();
        initAdminToken();
        tenantA = createTenant("t-alpha", "Alpha");
        tenantB = createTenant("t-beta", "Beta");
        tokenA = createScimToken(tenantA, "idp-a").path("token").asText();
        tokenB = createScimToken(tenantB, "idp-b").path("token").asText();
        userA = createUser(tokenA, "alice");
        userB = createUser(tokenB, "alice");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void sameUsernameIsAllowedInDifferentTenantsButResourcesStayIsolated() throws Exception {
        JsonNode listA = read(mockMvc.perform(scimGet("/scim/v2/Users", tokenA))
                .andExpect(status().isOk()).andReturn());
        JsonNode listB = read(mockMvc.perform(scimGet("/scim/v2/Users", tokenB))
                .andExpect(status().isOk()).andReturn());
        assertThat(listA.path("totalResults").asInt()).isEqualTo(1);
        assertThat(listA.path("Resources").get(0).path("id").asText()).isEqualTo(userA);
        assertThat(listB.path("totalResults").asInt()).isEqualTo(1);
        assertThat(listB.path("Resources").get(0).path("id").asText()).isEqualTo(userB);

        // Tenant A can never address tenant B resources.
        mockMvc.perform(scimGet("/scim/v2/Users/" + userB, tokenA))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
        mockMvc.perform(scimPatch("/scim/v2/Users/" + userB, tokenA,
                        Map.of("Operations", List.of(Map.of(
                                "op", "replace", "path", "active", "value", false)))))
                .andExpect(status().isNotFound());
        mockMvc.perform(scimDelete("/scim/v2/Users/" + userB, tokenA))
                .andExpect(status().isNotFound());
    }

    @Test
    void groupsAreIsolatedAndTokenListIsTenantFiltered() throws Exception {
        MvcResult groupResult = mockMvc.perform(scimPost("/scim/v2/Groups", tokenB, Map.of(
                        "displayName", "Beta Team", "externalId", "BETA_TEAM")))
                .andExpect(status().isCreated())
                .andReturn();
        String betaGroup = read(groupResult).path("id").asText();

        mockMvc.perform(scimGet("/scim/v2/Groups", tokenA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(0));
        mockMvc.perform(scimGet("/scim/v2/Groups/" + betaGroup, tokenA))
                .andExpect(status().isNotFound());
        mockMvc.perform(scimDelete("/scim/v2/Groups/" + betaGroup, tokenA))
                .andExpect(status().isNotFound());

        JsonNode filtered = read(mockMvc.perform(get("/api/platform/scim-tokens?tenantId=" + tenantA)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andReturn()).path("data");
        assertThat(filtered.size()).isEqualTo(1);
        assertThat(filtered.get(0).path("tenantId").asText()).isEqualTo(tenantA);
    }

    // ------------------------------------------------------------------ helpers

    private String createUser(String token, String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", username);
        body.put("displayName", "User " + username);
        body.put("active", true);
        MvcResult result = mockMvc.perform(scimPost("/scim/v2/Users", token, body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).path("id").asText();
    }
}
