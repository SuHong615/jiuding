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
package com.suhong.jiuding.tenant.platformuser;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MvcResult;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: platform user CRUD, password reset and audit trail. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformUserManagementTest extends AbstractIdentityWebTest {

    @BeforeEach
    void setUp() {
        cleanIdentityDatabase();
    }

    @Test
    void createListGetUpdateAndDeletePlatformUser() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/platform/users")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createJson("ops-admin", DEFAULT_PASSWORD, List.of("PLATFORM_ADMIN"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("ops-admin"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0]").value("PLATFORM_ADMIN"))
                .andReturn();
        String id = read(created).path("data").path("id").asText();
        assertThat(read(created).path("data").has("temporaryPassword")).isFalse();

        String stored = jdbc.queryForObject(
                "SELECT password_hash FROM platform_users WHERE id = ?", String.class, id);
        assertThat(stored).contains(":").doesNotContain(DEFAULT_PASSWORD);
        assertThat(auditCount("PLATFORM_USER_CREATED")).isEqualTo(1);

        mockMvc.perform(get("/api/platform/users").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].username").value("ops-admin"));
        mockMvc.perform(get("/api/platform/users/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.email").value("ops-admin@acme.io"));

        mockMvc.perform(put("/api/platform/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Ops Admin\",\"roles\":[\"AUDITOR\"],"
                                + "\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.displayName").value("Ops Admin"))
                .andExpect(jsonPath("$.data.roles[0]").value("AUDITOR"));
        assertThat(auditCount("PLATFORM_USER_UPDATED")).isEqualTo(1);

        mockMvc.perform(delete("/api/platform/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/users/" + id).header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        assertThat(auditCount("PLATFORM_USER_DELETED")).isEqualTo(1);
    }

    @Test
    void managementRequiresPlatformAdministratorJwt() throws Exception {
        String body = createJson("ops-admin", DEFAULT_PASSWORD, List.of("AUDITOR"));
        mockMvc.perform(post("/api/platform/users")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(Set.of("TENANT_ADMIN"), "t-acme", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/users"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/platform/users")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        // Duplicate usernames are rejected with 409.
        mockMvc.perform(post("/api/platform/users")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/platform/users")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    private String createJson(String username, String password, List<String> roles)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("displayName", "Ops");
        body.put("email", username + "@acme.io");
        body.put("roles", roles);
        return objectMapper.writeValueAsString(body);
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = 'platform' AND action = ?", Long.class, action);
        return count == null ? 0L : count;
    }
}
