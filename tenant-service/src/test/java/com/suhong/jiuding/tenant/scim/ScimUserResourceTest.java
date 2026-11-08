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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 7.1: SCIM Users resource mapped onto accounts, including filters,
 * pagination, PUT/PATCH/DELETE and the SCIM_USER_* audit trail.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScimUserResourceTest extends AbstractScimWebTest {

    private String tenantId;
    private String scimToken;
    private String orgId;

    @BeforeEach
    void setUp() throws Exception {
        cleanScimDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme");
        orgId = createOrg(tenantId, "HQ", "Headquarters");
        scimToken = createScimToken(tenantId, "idp").path("token").asText();
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void createGetUpdateAndDeleteMapOntoAccounts() throws Exception {
        JsonNode created = createUser("Alice", "Alice A", true, orgId);
        String id = created.path("id").asText();
        assertThat(created.path("schemas").toString())
                .contains("urn:ietf:params:scim:schemas:core:2.0:User");
        assertThat(created.path("userName").asText()).isEqualTo("alice");
        assertThat(created.path("displayName").asText()).isEqualTo("Alice A");
        assertThat(created.path("active").asBoolean()).isTrue();
        assertThat(created.path("emails").get(0).path("value").asText()).isEqualTo("alice@acme.io");
        assertThat(created.path("phoneNumbers").get(0).path("value").asText()).isEqualTo("13812341234");
        assertThat(created.path("roles").get(0).path("value").asText()).isEqualTo("MEMBER");
        assertThat(created.path("urn:tenancy:params:2.0:User").path("orgUnitId").asText())
                .isEqualTo(orgId);
        assertThat(created.path("meta").path("resourceType").asText()).isEqualTo("User");

        Map<String, Object> stored = jdbc.queryForMap(
                "SELECT username, display_name, status, must_change_password, password_hash"
                        + " FROM accounts WHERE id = ?", id);
        assertThat(stored.get("username")).isEqualTo("alice");
        assertThat(stored.get("display_name")).isEqualTo("Alice A");
        assertThat(stored.get("status")).isEqualTo("ACTIVE");
        assertThat((Boolean) stored.get("must_change_password")).isTrue();
        assertThat(stored.get("password_hash")).isNotNull();

        mockMvc.perform(scimGet("/scim/v2/Users/" + id, scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.id").value(id));

        // PUT replaces displayName/active; userName is immutable.
        Map<String, Object> putBody = new LinkedHashMap<>();
        putBody.put("userName", "alice");
        putBody.put("displayName", "Alice B");
        putBody.put("active", false);
        putBody.put("emails", List.of(Map.of("value", "alice.b@acme.io")));
        mockMvc.perform(scimPut("/scim/v2/Users/" + id, scimToken, putBody))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.displayName").value("Alice B"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.active").value(false));
        assertThat(jdbc.queryForObject("SELECT status FROM accounts WHERE id = ?", String.class, id))
                .isEqualTo("DISABLED");

        // PATCH active/displayName.
        Map<String, Object> patchBody = Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "active", "value", true),
                Map.of("op", "replace", "path", "displayName", "value", "Alice C")));
        mockMvc.perform(scimPatch("/scim/v2/Users/" + id, scimToken, patchBody))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.displayName").value("Alice C"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.active").value(true));

        mockMvc.perform(scimDelete("/scim/v2/Users/" + id, scimToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(scimGet("/scim/v2/Users/" + id, scimToken))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value(404));

        assertThat(scimTokenAuditCount(tenantId, "SCIM_USER_CREATED")).isEqualTo(1);
        assertThat(scimTokenAuditCount(tenantId, "SCIM_USER_UPDATED")).isEqualTo(2);
        assertThat(scimTokenAuditCount(tenantId, "SCIM_USER_DELETED")).isEqualTo(1);
    }

    @Test
    void listSupportsFiltersAndPagination() throws Exception {
        createUser("Alice", "Alice One", true, orgId);
        createUser("Bob", "Bob Two", true, orgId);
        JsonNode carol = createUser("Carol", "Carol Three", false, orgId);

        mockMvc.perform(scimGet("/scim/v2/Users", scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalResults").value(3))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.Resources.length()").value(3));

        mockMvc.perform(scimGet("/scim/v2/Users?filter="
                        + java.net.URLEncoder.encode("userName eq \"bob\"", java.nio.charset.StandardCharsets.UTF_8),
                        scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalResults").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.Resources[0].userName").value("bob"));

        mockMvc.perform(scimGet("/scim/v2/Users?filter="
                        + java.net.URLEncoder.encode("active eq false", java.nio.charset.StandardCharsets.UTF_8),
                        scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalResults").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.Resources[0].id").value(carol.path("id").asText()));

        mockMvc.perform(scimGet("/scim/v2/Users?filter="
                        + java.net.URLEncoder.encode("displayName eq \"Bob Two\"",
                        java.nio.charset.StandardCharsets.UTF_8), scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalResults").value(1));

        mockMvc.perform(scimGet("/scim/v2/Users?startIndex=2&count=1", scimToken))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.startIndex").value(2))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.itemsPerPage").value(1))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.totalResults").value(3));

        mockMvc.perform(scimGet("/scim/v2/Users?filter="
                        + java.net.URLEncoder.encode("userName co \"a\"", java.nio.charset.StandardCharsets.UTF_8),
                        scimToken))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value(400));
    }

    @Test
    void validatesUserInputAndUniqueness() throws Exception {
        createUser("Alice", "Alice", true, orgId);

        mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of(
                        "userName", "alice", "displayName", "Duplicate")))
                .andExpect(status().isConflict())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                        .jsonPath("$.status").value(409));

        mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of(
                        "displayName", "No username")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(scimPost("/scim/v2/Users", scimToken, Map.of(
                        "userName", "badrole", "roles", List.of(Map.of("value", "SUPERUSER")))))
                .andExpect(status().isBadRequest());

        Map<String, Object> badOrg = new LinkedHashMap<>();
        badOrg.put("userName", "bador");
        badOrg.put("urn:tenancy:params:2.0:User", Map.of("orgUnitId", "missing-org"));
        mockMvc.perform(scimPost("/scim/v2/Users", scimToken, badOrg))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createUser(String username, String displayName, boolean active, String orgUnitId)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("schemas", List.of("urn:ietf:params:scim:schemas:core:2.0:User"));
        body.put("userName", username);
        body.put("displayName", displayName);
        body.put("emails", List.of(Map.of("value", username.toLowerCase() + "@acme.io", "primary", true)));
        body.put("phoneNumbers", List.of(Map.of("value", "13812341234")));
        body.put("active", active);
        body.put("roles", List.of(Map.of("value", "MEMBER")));
        if (orgUnitId != null) {
            body.put("urn:tenancy:params:2.0:User", Map.of("orgUnitId", orgUnitId));
        }
        MvcResult result = mockMvc.perform(scimPost("/scim/v2/Users", scimToken, body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }
}
