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
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 7.1: SCIM protocol errors use the RFC7644 Error schema
 * {@code {schemas,status,detail}} (401/404/409/400), and the discovery document is exposed
 * with the tenant SCIM token.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScimErrorSchemaTest extends AbstractScimWebTest {

    private String tenantId;
    private String scimToken;

    @BeforeEach
    void setUp() throws Exception {
        cleanScimDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme");
        scimToken = createScimToken(tenantId, "idp").path("token").asText();
        mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of(
                        "userName", "alice", "displayName", "Alice")))
                .andExpect(status().isCreated());
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void unauthorizedRequestsReturnTheScimErrorSchema() throws Exception {
        assertScimError(mockMvc.perform(get("/scim/v2/Users"))
                .andExpect(status().isUnauthorized()).andReturn(), 401);
        assertScimError(mockMvc.perform(scimGet("/scim/v2/Users", "scim_unknown"))
                .andExpect(status().isUnauthorized()).andReturn(), 401);
    }

    @Test
    void notFoundConflictAndBadRequestUseTheScimErrorSchema() throws Exception {
        assertScimError(mockMvc.perform(scimGet("/scim/v2/Users/missing-user", scimToken))
                .andExpect(status().isNotFound()).andReturn(), 404);
        assertScimError(mockMvc.perform(scimGet("/scim/v2/Groups/missing-group", scimToken))
                .andExpect(status().isNotFound()).andReturn(), 404);

        assertScimError(mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of(
                        "userName", "alice", "displayName", "Duplicate")))
                .andExpect(status().isConflict()).andReturn(), 409);

        assertScimError(mockMvc.perform(scimGet("/scim/v2/Users?filter=userName%20pr%20%22a%22",
                        scimToken))
                .andExpect(status().isBadRequest()).andReturn(), 400);
        assertScimError(mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of()))
                .andExpect(status().isBadRequest()).andReturn(), 400);
        MvcResult malformed = mockMvc.perform(post("/scim/v2/Users")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + scimToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertScimError(malformed, 400);
    }

    @Test
    void serviceProviderConfigDescribesSupportedFeatures() throws Exception {
        mockMvc.perform(scimGet("/scim/v2/ServiceProviderConfig", scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.schemas[0]")
                        .value("urn:ietf:params:scim:schemas:core:2.0:ServiceProviderConfig"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.patch.supported").value(true))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.filter.maxResults").value(100))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.authenticationSchemes[0].type").value("oauthbearertoken"));

        assertScimError(mockMvc.perform(get("/scim/v2/ServiceProviderConfig"))
                .andExpect(status().isUnauthorized()).andReturn(), 401);
    }

    private void assertScimError(MvcResult result, int expectedStatus) throws Exception {
        assertThat(result.getResponse().getContentType()).contains("application/scim+json");
        JsonNode body = read(result);
        assertThat(body.path("schemas").get(0).asText())
                .isEqualTo("urn:ietf:params:scim:api:messages:2.0:Error");
        assertThat(body.path("status").asInt()).isEqualTo(expectedStatus);
        assertThat(body.path("detail").asText()).isNotBlank();
    }
}
