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
import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v9 phase 2: custom role grants apply to the newly annotated endpoints, while the existing
 * service-level checks (platform-only offboarding, SCIM token management, quota audience)
 * remain an additional gate that a custom role cannot bypass.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase2CustomRoleTest extends AbstractPermissionWebTest {

    private static final String ROLE = "PHASE2_CUSTOM";

    @BeforeEach
    void setUp() throws Exception {
        prepareTenant();
        mockMvc.perform(post("/api/platform/roles")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "code", ROLE, "name", "Phase2 Custom", "scope", "TENANT"))))
                .andExpect(status().isOk());
    }

    @AfterEach
    void tearDown() {
        jdbc.update("DELETE FROM role_permissions WHERE role_code = ?", ROLE);
        jdbc.update("DELETE FROM roles WHERE code = ?", ROLE);
    }

    @Test
    void customRoleCanManageItsOwnMfaAndLosesItWhenRevoked() throws Exception {
        grant(List.of("ACCOUNT_CHANGE_PASSWORD"));
        String token = bearer(accountId("alice"), "alice", Set.of(ROLE), "t-acme", orgId);

        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/me/mfa/setup").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        grant(List.of());
        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
    }

    @Test
    void customRoleCannotBypassServiceLevelAudienceChecks() throws Exception {
        grant(List.of("RATING_READ", "TENANT_AUDIT_READ", "RBAC_MANAGE"));
        String token = roleToken(ROLE);

        // Annotations pass (RATING_READ / audit:read / rbac:write) but the quota, export and
        // SCIM token services still require their documented audiences.
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/scim-tokens?tenantId=" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        // Offboarding remains platform-only for the same reason.
        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"custom role\"}"))
                .andExpect(status().isForbidden());
    }

    private void grant(List<String> codes) throws Exception {
        mockMvc.perform(put("/api/platform/roles/" + ROLE + "/permissions")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("permissionCodes", codes))))
                .andExpect(status().isOk());
    }
}
