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
package com.suhong.jiuding.tenant.platformaudit;

import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;

import java.util.Set;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/21 section 2: only platform administrator JWTs may use the platform audit center. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PlatformAuditPermissionTest extends AbstractIdentityWebTest {

    private String tenantId;

    @BeforeEach
    void setUp() throws Exception {
        cleanIdentityDatabase();
        tenantId = createTenant("t-alpha", "Alpha");
        activateTenant(tenantId);
    }

    @Test
    void tenantAdministratorAuditorAndMemberAreForbidden() throws Exception {
        String tenantAdmin = bearer(Set.of("TENANT_ADMIN"), "t-alpha", null);
        String auditor = bearer(Set.of("AUDITOR"), "t-alpha", null);
        String member = bearer(Set.of("MEMBER"), "t-alpha", null);

        mockMvc.perform(get("/api/platform/audits").header(HttpHeaders.AUTHORIZATION, tenantAdmin))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/platform/audits").header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/audits").header(HttpHeaders.AUTHORIZATION, member))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/audits/export")
                        .header(HttpHeaders.AUTHORIZATION, tenantAdmin))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/audits/export")
                        .header(HttpHeaders.AUTHORIZATION, auditor))
                .andExpect(status().isForbidden());
    }

    @Test
    void platformAdministratorAndAuthenticationRules() throws Exception {
        mockMvc.perform(get("/api/platform/audits").header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/audits/export")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        // Trusted machine callers are not platform administrator JWTs for this audience.
        mockMvc.perform(get("/api/platform/audits").header("X-API-Key", "dev-mcp-key"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/audits"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/platform/audits/export"))
                .andExpect(status().isUnauthorized());
    }
}
