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

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v9 phase 2: quota/export/offboard/MFA/SCIM-token annotations mapped onto the existing v5
 * permission matrix, including the self-service MFA exception for MEMBER accounts.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Phase2AnnotationMatrixTest extends AbstractPermissionWebTest {

    @BeforeEach
    void setUp() throws Exception {
        prepareTenant();
    }

    @Test
    void platformAdministratorPassesThePhase2Endpoints() throws Exception {
        String token = platformToken();
        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.seats.used").value(1));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/scim-tokens?tenantId=" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());

        jdbc.update("UPDATE tenants SET status = 'ACTIVE' WHERE id = ?", tenantId);
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"phase2 test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("TERMINATED"));
    }

    @Test
    void tenantAdministratorAndAuditorAreGatedByTheirMatrix() throws Exception {
        for (String token : new String[]{tenantAdminToken(), auditorToken()}) {
            mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isOk());
            // Offboard stays platform-only: permission annotation passes, service rejects.
            mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                            .header(HttpHeaders.AUTHORIZATION, token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"not platform\"}"))
                    .andExpect(status().isForbidden());
            // SCIM token management requires RBAC_MANAGE, which only PLATFORM_ADMIN owns.
            mockMvc.perform(get("/api/platform/scim-tokens?tenantId=" + tenantId)
                            .header(HttpHeaders.AUTHORIZATION, token))
                    .andExpect(status().isForbidden());
        }
    }

    @Test
    void memberKeepsSelfServiceMfaButLosesQuotaExportAndScimManagement() throws Exception {
        String aliceId = accountId("alice");
        String memberSelf = bearer(aliceId, "alice", Set.of("MEMBER"), "t-acme", orgId);

        mockMvc.perform(get("/api/me/mfa").header(HttpHeaders.AUTHORIZATION, memberSelf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        // account:write via ACCOUNT_CHANGE_PASSWORD lets MEMBER manage its own MFA.
        mockMvc.perform(post("/api/me/mfa/setup").header(HttpHeaders.AUTHORIZATION, memberSelf))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.secret").isNotEmpty());

        mockMvc.perform(get("/api/tenants/" + tenantId + "/quota")
                        .header(HttpHeaders.AUTHORIZATION, memberSelf))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/export")
                        .header(HttpHeaders.AUTHORIZATION, memberSelf))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/offboard")
                        .header(HttpHeaders.AUTHORIZATION, memberSelf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"nope\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/scim-tokens")
                        .header(HttpHeaders.AUTHORIZATION, memberSelf))
                .andExpect(status().isForbidden());
    }
}
