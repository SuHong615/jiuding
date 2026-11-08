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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * docs/16 section 7.1: SCIM Groups mapped onto org units with membership sync to accounts,
 * filter/pagination, PATCH members, delete constraints and SCIM_GROUP_* audits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ScimGroupResourceTest extends AbstractScimWebTest {

    private String tenantId;
    private String scimToken;
    private String userOne;
    private String userTwo;
    private String userThree;

    @BeforeEach
    void setUp() throws Exception {
        cleanScimDatabase();
        initAdminToken();
        tenantId = createTenant("t-acme", "Acme");
        scimToken = createScimToken(tenantId, "idp").path("token").asText();
        userOne = createUser("alice");
        userTwo = createUser("bob");
        userThree = createUser("carol");
    }

    @AfterEach
    void tearDown() {
        com.suhong.jiuding.common.security.AuthContext.clear();
        com.suhong.jiuding.common.tenant.TenantContext.clear();
    }

    @Test
    void groupCrudSyncsMembersToAccounts() throws Exception {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("displayName", "Engineering");
        createBody.put("externalId", "ENG");
        createBody.put("members", List.of(member(userOne)));
        MvcResult createdResult = mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.displayName").value("Engineering"))
                .andExpect(jsonPath("$.externalId").value("ENG"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andReturn();
        JsonNode created = read(createdResult);
        String groupId = created.path("id").asText();
        assertThat(created.path("urn:tenancy:params:2.0:Group").path("code").asText()).isEqualTo("ENG");
        assertThat(created.path("urn:tenancy:params:2.0:Group").path("type").asText())
                .isEqualTo("DEPARTMENT");
        assertThat(jdbc.queryForObject("SELECT org_unit_id FROM accounts WHERE id = ?",
                String.class, userOne)).isEqualTo(groupId);

        mockMvc.perform(scimGet("/scim/v2/Groups?filter="
                        + java.net.URLEncoder.encode("displayName eq \"Engineering\"",
                        java.nio.charset.StandardCharsets.UTF_8), scimToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalResults").value(1))
                .andExpect(jsonPath("$.Resources[0].id").value(groupId));

        // PATCH: rename + add a member.
        Map<String, Object> patchBody = Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "displayName", "value", "Engineering 2"),
                Map.of("op", "add", "path", "members", "value", List.of(member(userTwo)))));
        mockMvc.perform(scimPatch("/scim/v2/Groups/" + groupId, scimToken, patchBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Engineering 2"))
                .andExpect(jsonPath("$.members.length()").value(2));

        // PATCH: remove one member via a filtered path.
        Map<String, Object> removeBody = Map.of("Operations", List.of(
                Map.of("op", "remove", "path", "members[value eq \"" + userOne + "\"]")));
        mockMvc.perform(scimPatch("/scim/v2/Groups/" + groupId, scimToken, removeBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].value").value(userTwo));
        assertThat(jdbc.queryForObject("SELECT org_unit_id FROM accounts WHERE id = ?",
                String.class, userOne)).isNull();

        // PUT replaces displayName + members.
        Map<String, Object> putBody = new LinkedHashMap<>();
        putBody.put("displayName", "Engineering 3");
        putBody.put("externalId", "ENG");
        putBody.put("members", List.of(member(userThree)));
        mockMvc.perform(scimPut("/scim/v2/Groups/" + groupId, scimToken, putBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.displayName").value("Engineering 3"))
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.members[0].value").value(userThree));
        assertThat(jdbc.queryForObject("SELECT org_unit_id FROM accounts WHERE id = ?",
                String.class, userTwo)).isNull();
        assertThat(jdbc.queryForObject("SELECT org_unit_id FROM accounts WHERE id = ?",
                String.class, userThree)).isEqualTo(groupId);

        // A group with members cannot be deleted (SCIM 409).
        mockMvc.perform(scimDelete("/scim/v2/Groups/" + groupId, scimToken))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        Map<String, Object> clearMembers = Map.of("Operations", List.of(
                Map.of("op", "replace", "path", "members", "value", List.of())));
        mockMvc.perform(scimPatch("/scim/v2/Groups/" + groupId, scimToken, clearMembers))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(0));
        mockMvc.perform(scimDelete("/scim/v2/Groups/" + groupId, scimToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(scimGet("/scim/v2/Groups/" + groupId, scimToken))
                .andExpect(status().isNotFound());

        assertThat(scimTokenAuditCount(tenantId, "SCIM_GROUP_CREATED")).isEqualTo(1);
        assertThat(scimTokenAuditCount(tenantId, "SCIM_GROUP_UPDATED")).isEqualTo(4);
        assertThat(scimTokenAuditCount(tenantId, "SCIM_GROUP_DELETED")).isEqualTo(1);
    }

    @Test
    void nestedGroupsFollowTheOrganizationTreeConstraints() throws Exception {
        JsonNode parent = createGroup("Parent", "PARENT", null);
        String parentId = parent.path("id").asText();
        Map<String, Object> childBody = new LinkedHashMap<>();
        childBody.put("displayName", "Child");
        childBody.put("externalId", "CHILD");
        childBody.put("urn:tenancy:params:2.0:Group", Map.of("parentId", parentId));
        MvcResult childResult = mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, childBody))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode child = read(childResult);
        assertThat(child.path("urn:tenancy:params:2.0:Group").path("parentId").asText())
                .isEqualTo(parentId);

        mockMvc.perform(scimDelete("/scim/v2/Groups/" + parentId, scimToken))
                .andExpect(status().isConflict());

        mockMvc.perform(scimDelete("/scim/v2/Groups/" + child.path("id").asText(), scimToken))
                .andExpect(status().isNoContent());
        mockMvc.perform(scimDelete("/scim/v2/Groups/" + parentId, scimToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void validatesGroupFiltersAndInput() throws Exception {
        mockMvc.perform(scimGet("/scim/v2/Groups?filter="
                        + java.net.URLEncoder.encode("userName eq \"x\"", java.nio.charset.StandardCharsets.UTF_8),
                        scimToken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400));

        mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, Map.of("externalId", "NO_NAME")))
                .andExpect(status().isBadRequest());

        createGroup("Duplicate", "DUP", null);
        mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, Map.of(
                        "displayName", "Duplicate 2", "externalId", "DUP")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409));

        Map<String, Object> badParent = new LinkedHashMap<>();
        badParent.put("displayName", "Bad Parent");
        badParent.put("urn:tenancy:params:2.0:Group", Map.of("parentId", "missing-parent"));
        mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, badParent))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ helpers

    private JsonNode createGroup(String displayName, String externalId, String parentId) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", displayName);
        if (externalId != null) {
            body.put("externalId", externalId);
        }
        if (parentId != null) {
            body.put("urn:tenancy:params:2.0:Group", Map.of("parentId", parentId));
        }
        MvcResult result = mockMvc.perform(scimPost("/scim/v2/Groups", scimToken, body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result);
    }

    private String createUser(String username) throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userName", username);
        body.put("displayName", "User " + username);
        body.put("active", true);
        MvcResult result = mockMvc.perform(scimPost("/scim/v2/Users", scimToken, body))
                .andExpect(status().isCreated())
                .andReturn();
        return read(result).path("id").asText();
    }
}
