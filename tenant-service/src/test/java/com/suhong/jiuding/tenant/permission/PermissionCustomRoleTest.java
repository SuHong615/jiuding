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
package com.suhong.jiuding.tenant.permission;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v9: custom roles created through the RBAC API take effect for JWT callers immediately and
 * the 60s permission cache is evicted on every matrix mutation.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionCustomRoleTest extends AbstractPermissionWebTest {

    private static final String ROLE = "CUSTOM_READER";

    @BeforeEach
    void setUp() throws Exception {
        prepareTenant();
        mockMvc.perform(post("/api/platform/roles")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", ROLE, "name", "Custom Reader", "scope", "TENANT"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM roles WHERE code = ?", ROLE);
    }

    @Test
    void customRoleGrantsTakeEffectImmediatelyAndCanBeRevoked() throws Exception {
        grantPermissions(List.of("ORG_READ"));

        String token = roleToken(ROLE);
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));

        // Matrix expansion is visible on the next request (cache evicted by RbacService).
        grantPermissions(List.of("ORG_READ", "ACCOUNT_READ"));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        // Revoking the permissions also takes effect immediately.
        grantPermissions(List.of());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());

        // Deleting the custom role leaves the JWT with no permissions at all.
        grantPermissions(List.of("ORG_READ"));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/platform/roles/" + ROLE)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void customRoleCannotExceedItsGrantedPermissions() throws Exception {
        grantPermissions(List.of("ACCOUNT_READ"));
        String token = roleToken(ROLE);

        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/roles")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    private void grantPermissions(List<String> codes) throws Exception {
        mockMvc.perform(put("/api/platform/roles/" + ROLE + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissionCodes", codes))))
                .andExpect(status().isOk());
    }
}
