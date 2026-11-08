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
 * v9 phase 1: annotated endpoints are gated by the caller's effective role permissions while
 * the existing tenant/subtree checks stay in place as an additional gate.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PermissionInterceptorEnforcementTest extends AbstractPermissionWebTest {

    @BeforeEach
    void setUp() throws Exception {
        prepareTenant();
        mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    @Test
    void platformAdministratorPassesEveryAnnotatedEndpoint() throws Exception {
        String token = platformToken();
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId).header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/roles").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/permissions").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
    }

    @Test
    void roleWithoutPermissionsIsForbiddenWith40300() throws Exception {
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, roleToken("GHOST_ROLE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, roleToken("GHOST_ROLE")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, roleToken("GHOST_ROLE")))
                .andExpect(status().isForbidden());
    }

    @Test
    void memberKeepsItsDocumentedReadOnlySurface() throws Exception {
        String token = memberToken();
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId)
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(40300));
        mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NO\",\"name\":\"No\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void auditorCanReadButCannotWrite() throws Exception {
        String token = auditorToken();
        mockMvc.perform(get("/api/tenants").header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/tenants/" + tenantId + "/ratings")
                        .header(HttpHeaders.AUTHORIZATION, token))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/org-units")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"NO\",\"name\":\"No\",\"type\":\"COMPANY\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/accounts")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"displayName\":\"Nobody\",\"role\":\"MEMBER\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/tenants/" + tenantId + "/ratings/assess")
                        .header(HttpHeaders.AUTHORIZATION, token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }
}
