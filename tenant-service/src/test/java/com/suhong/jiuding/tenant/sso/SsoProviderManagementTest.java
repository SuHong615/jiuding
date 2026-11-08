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
package com.suhong.jiuding.tenant.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.tenant.support.AbstractIdentityWebTest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** docs/20 section 3: SSO provider CRUD, public enabled list and internal gateway list. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SsoProviderManagementTest extends AbstractIdentityWebTest {

    @BeforeEach
    void setUp() {
        cleanIdentityDatabase();
    }

    @Test
    void adminCanCrudProvidersWithoutExposingTheClientSecret() throws Exception {
        JsonNode enabled = createProvider("okta", "Okta", true, "https://idp.example");
        JsonNode disabled = createProvider("azure", "Azure AD", false, "https://login.example");
        assertThat(enabled.path("code").asText()).isEqualTo("okta");
        assertThat(enabled.path("hasClientSecret").asBoolean()).isTrue();
        assertThat(enabled.has("clientSecret")).isFalse();

        mockMvc.perform(get("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2));
        String enabledId = enabled.path("id").asText();
        mockMvc.perform(get("/api/platform/sso-providers/" + enabledId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Okta"))
                .andExpect(jsonPath("$.data.clientSecret").doesNotExist());

        // Public login-button list: enabled only, no credentials.
        mockMvc.perform(get("/api/auth/oidc/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("okta"))
                .andExpect(jsonPath("$.data[0].clientSecret").doesNotExist())
                .andExpect(jsonPath("$.data[0].issuer").doesNotExist());

        // Internal gateway list carries the client credentials.
        mockMvc.perform(get("/internal/sso-providers/enabled")
                        .header("X-Internal-Token", "dev-internal-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].clientId").value("client-okta"))
                .andExpect(jsonPath("$.data[0].clientSecret").value("secret-okta"));

        // Disable the enabled provider: the public list becomes empty.
        mockMvc.perform(put("/api/platform/sso-providers/" + enabledId)
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));
        mockMvc.perform(get("/api/auth/oidc/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        mockMvc.perform(delete("/api/platform/sso-providers/" + disabled.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/platform/sso-providers/" + disabled.path("id").asText())
                        .header(HttpHeaders.AUTHORIZATION, adminToken))
                .andExpect(status().isNotFound());
        assertThat(auditCount("SSO_PROVIDER_CREATED")).isEqualTo(2);
        assertThat(auditCount("SSO_PROVIDER_UPDATED")).isEqualTo(1);
        assertThat(auditCount("SSO_PROVIDER_DELETED")).isEqualTo(1);
    }

    @Test
    void providerManagementRequiresPlatformAdministrator() throws Exception {
        String body = providerJson("okta", "Okta", true, "https://idp.example");
        mockMvc.perform(post("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION,
                                bearer(Set.of("TENANT_ADMIN"), "t-acme", null))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/platform/sso-providers"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/platform/sso-providers")
                        .header("X-API-Key", "dev-mcp-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
        // Duplicate provider codes are a 409 conflict.
        mockMvc.perform(post("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isConflict());
    }

    private JsonNode createProvider(String code, String name, boolean enabled, String issuer)
            throws Exception {
        return read(mockMvc.perform(post("/api/platform/sso-providers")
                        .header(HttpHeaders.AUTHORIZATION, adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(providerJson(code, name, enabled, issuer)))
                .andExpect(status().isOk())
                .andReturn()).path("data");
    }

    private String providerJson(String code, String name, boolean enabled, String issuer)
            throws Exception {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("name", name);
        body.put("issuer", issuer);
        body.put("clientId", "client-" + code);
        body.put("clientSecret", "secret-" + code);
        body.put("scopes", "openid profile email");
        body.put("redirectUri", "https://app.example/api/auth/oidc/callback");
        body.put("frontendRedirect", "https://app.example/login");
        body.put("roleClaim", "roles");
        body.put("defaultRole", "MEMBER");
        body.put("enabled", enabled);
        return objectMapper.writeValueAsString(body);
    }

    private long auditCount(String action) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM tenant_audits"
                + " WHERE tenant_id = 'platform' AND action = ?", Long.class, action);
        return count == null ? 0L : count;
    }
}
