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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: internal platform user authentication used by the gateway login. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformUserLoginTest extends AbstractIdentityWebTest {

    @BeforeEach
    void setUp() {
        cleanIdentityDatabase();
    }

    @Test
    void authenticatesActivePlatformUserAndUpdatesLastLogin() throws Exception {
        String id = createPlatformUser("ops-admin", DEFAULT_PASSWORD, List.of("PLATFORM_ADMIN"));

        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(id))
                .andExpect(jsonPath("$.data.username").value("ops-admin"))
                .andExpect(jsonPath("$.data.status").value("ACTIVE"))
                .andExpect(jsonPath("$.data.roles[0]").value("PLATFORM_ADMIN"))
                .andExpect(jsonPath("$.data.lastLoginAt").isNotEmpty());
        assertThat(jdbc.queryForObject("SELECT last_login_at FROM platform_users WHERE id = ?",
                Object.class, id)).isNotNull();

        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"missing\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void disabledUserCannotLoginAndResetPasswordRotatesCredentials() throws Exception {
        String id = createPlatformUser("ops-admin", DEFAULT_PASSWORD, List.of("AUDITOR"));
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/platform/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"DISABLED\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());

        // Re-enable and reset the password (server generates a one-time temporary password).
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/platform/users/" + id)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\"}"))
                .andExpect(status().isOk());
        JsonNode reset = read(mockMvc.perform(post("/api/platform/users/" + id + "/reset-password")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("ops-admin"))
                .andReturn()).path("data");
        String temporary = reset.path("temporaryPassword").asText();
        assertThat(temporary).isNotEmpty();

        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"" + DEFAULT_PASSWORD + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/internal/platform-users/authenticate")
                        .header("X-Internal-Token", "dev-internal-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"ops-admin\",\"password\":\"" + temporary + "\"}"))
                .andExpect(status().isOk());
    }

    private String createPlatformUser(String username, String password, List<String> roles)
            throws Exception {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("username", username);
        body.put("password", password);
        body.put("displayName", "Ops");
        body.put("email", username + "@acme.io");
        body.put("roles", roles);
        JsonNode created = read(mockMvc.perform(post("/api/platform/users")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isOk())
                .andReturn());
        return created.path("data").path("id").asText();
    }
}
