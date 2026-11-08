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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v9: platform administrators bypass the matrix with the {@code *} wildcard (even if their
 * role_permissions rows are missing) and trusted machine callers keep their existing access.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionPlatformBypassTest extends AbstractPermissionWebTest {

    @BeforeEach
    void setUp() throws Exception {
        prepareTenant();
    }

    @Test
    void platformAdminWildcardDoesNotDependOnTheMatrixRows() throws Exception {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = 'PLATFORM_ADMIN'");
        try {
            String token = platformToken();
            mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/platform/permissions")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"code\":\"NEW\",\"name\":\"New\",\"type\":\"COMPANY\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/me/permissions")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.permissions[0]").value("*"));
        } finally {
            // Restore the documented built-in matrix expected by other tests.
            jdbc.update("INSERT INTO role_permissions (role_code, permission_code)"
                    + " SELECT 'PLATFORM_ADMIN', code FROM permissions");
        }
    }

    @Test
    void trustedMachineCallerKeepsPlatformWideAccess() throws Exception {
        mockMvc.perform(get("/api/tenants").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"machine\",\"displayName\":\"Machine\","
                                + "\"role\":\"MEMBER\",\"initialPassword\":\"Passw0rd123\"}"))
                .andExpect(status().isOk());
    }
}
