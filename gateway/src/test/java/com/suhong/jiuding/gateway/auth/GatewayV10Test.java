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
package com.suhong.jiuding.gateway.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.oidc.OidcDiscoveryClient;
import com.suhong.jiuding.gateway.oidc.SsoProviderClient;
import com.suhong.jiuding.gateway.support.StubServer;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v10 gateway identity features (docs/20 §3): platform accounts stored in tenant_db,
 * tenant selection / switching and dynamic SSO providers loaded from tenant-service.
 */
@SpringBootTest(properties = {
        "tenancy.auth.preset-fallback-enabled=true",
        "tenancy.auth.oidc.enabled=false",
        "tenancy.auth.oidc.providers-cache-seconds=60"
})
@AutoConfigureMockMvc
class GatewayV10Test {

    private static final String STATE_SECRET = "tenancy-center-dev-secret-change-me-please";
    private static final AtomicReference<String> IDP_BASE = new AtomicReference<>();
    /** "list" = candidate endpoint available; "500" = endpoint missing (tenant-service v10). */
    private static final AtomicReference<String> CANDIDATES_MODE = new AtomicReference<>("list");

    private static final StubServer IDP = new StubServer((request, exchange) -> {
        String base = IDP_BASE.get();
        switch (request.path()) {
            case "/.well-known/openid-configuration" -> StubServer.respond(exchange, 200,
                    "application/json", "{\"issuer\":\"" + base + "\","
                            + "\"authorization_endpoint\":\"" + base + "/authorize\","
                            + "\"token_endpoint\":\"" + base + "/token\","
                            + "\"userinfo_endpoint\":\"" + base + "/userinfo\"}");
            case "/token" -> StubServer.respond(exchange, 200, "application/json",
                    "{\"access_token\":\"at-corp\",\"token_type\":\"Bearer\"}");
            case "/userinfo" -> StubServer.respond(exchange, 200, "application/json",
                    "{\"sub\":\"sso-corp-1\",\"name\":\"Corp Alice\",\"roles\":[\"TENANT_ADMIN\"]}");
            default -> StubServer.respond(exchange, 404, "application/json", "{}");
        }
    });

    static {
        IDP_BASE.set(IDP.baseUrl());
    }

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        String path = request.path();
        String body = request.body() == null ? "" : request.body();
        switch (path) {
            case "/internal/platform-users/authenticate" -> {
                if (body.contains("\"username\":\"dbadmin\"") && body.contains("Db@12345")) {
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"userId\":\"pu-1\","
                                    + "\"username\":\"dbadmin\",\"displayName\":\"DB Admin\","
                                    + "\"roles\":[\"PLATFORM_ADMIN\"],\"status\":\"ACTIVE\"}}");
                } else {
                    StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                            "{\"code\":40100,\"message\":\"invalid credentials\"}");
                }
            }
            case "/internal/auth/authenticate" -> {
                if (body.contains("\"username\":\"multi\"") && body.contains("Multi@123")) {
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"tenantSelectionRequired\":true,"
                                    + "\"tenants\":[{\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\",\"name\":\"Acme\"},"
                                    + "{\"tenantId\":\"t-beta\",\"tenantCode\":\"beta\",\"name\":\"Beta\"}]}}");
                } else if (body.contains("\"username\":\"alice\"") && body.contains("Alice@123")) {
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"accountId\":\"acc-acme\","
                                    + "\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\",\"username\":\"alice\","
                                    + "\"displayName\":\"Alice Acme\",\"role\":\"TENANT_ADMIN\","
                                    + "\"orgUnitId\":\"org-acme\",\"mustChangePassword\":false}}");
                } else {
                    StubServer.respond(exchange, 401, "application/json;charset=UTF-8",
                            "{\"code\":40100,\"message\":\"invalid credentials\"}");
                }
            }
            case "/internal/auth/tenants" -> {
                if ("500".equals(CANDIDATES_MODE.get())) {
                    StubServer.respond(exchange, 500, "application/json;charset=UTF-8",
                            "{\"code\":50000,\"message\":\"internal error\"}");
                } else {
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"tenants\":["
                                    + "{\"tenantId\":\"t-acme\",\"tenantCode\":\"acme\",\"name\":\"Acme\"},"
                                    + "{\"tenantId\":\"t-beta\",\"tenantCode\":\"beta\",\"name\":\"Beta\"}]}}");
                }
            }
            case "/internal/auth/switch-tenant" -> {
                if (body.contains("\"tenantCode\":\"beta\"")) {
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"accountId\":\"acc-beta\","
                                    + "\"tenantId\":\"t-beta\",\"tenantCode\":\"beta\",\"username\":\"alice\","
                                    + "\"displayName\":\"Alice Beta\",\"role\":\"TENANT_ADMIN\","
                                    + "\"orgUnitId\":\"org-beta\",\"mustChangePassword\":false}}");
                } else {
                    StubServer.respond(exchange, 403, "application/json;charset=UTF-8",
                            "{\"code\":40300,\"message\":\"not allowed\"}");
                }
            }
            case "/internal/sso-providers/enabled" -> StubServer.respond(exchange, 200,
                    "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":{\"providers\":[{\"code\":\"corp\","
                            + "\"name\":\"Corp SSO\",\"issuer\":\"" + IDP_BASE.get() + "\","
                            + "\"clientId\":\"corp-client\",\"clientSecret\":\"corp-secret\","
                            + "\"scopes\":\"openid profile email\","
                            + "\"redirectUri\":\"http://localhost:18080/api/auth/oidc/callback\","
                            + "\"frontendRedirect\":\"http://localhost:15173/sso-callback\","
                            + "\"roleClaim\":\"roles\",\"defaultRole\":\"AUDITOR\"}]}}");
            default -> {
                if (path.startsWith("/internal/tenants/")) {
                    String tenantId = path.substring("/internal/tenants/".length());
                    StubServer.respond(exchange, 200, "application/json;charset=UTF-8",
                            "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"" + tenantId
                                    + "\",\"tenantCode\":\"acme\",\"name\":\"Acme\"}}");
                } else {
                    StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                            "{\"code\":40400,\"message\":\"not found\"}");
                }
            }
        }
    });

    @DynamicPropertySource
    static void downstreamProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.gateway.tenant-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.approval-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.agent-service-url", TENANT::baseUrl);
        registry.add("tenancy.gateway.mcp-service-url", TENANT::baseUrl);
    }

    @AfterAll
    static void stopStubs() {
        IDP.close();
        TENANT.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SsoProviderClient ssoProviderClient;

    @Autowired
    private OidcDiscoveryClient oidcDiscoveryClient;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    @BeforeEach
    void resetState() {
        ssoProviderClient.clearCache();
        oidcDiscoveryClient.invalidateCache();
        loginRateLimiter.reset();
        TENANT.reset();
        CANDIDATES_MODE.set("list");
    }

    @Test
    void platformAccountLoginUsesTenantDatabase() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"dbadmin\",\"password\":\"Db@12345\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.principal.username").value("DB Admin"))
                .andExpect(jsonPath("$.data.principal.authType").value("PLATFORM"))
                .andExpect(jsonPath("$.data.principal.roles[0]").value("PLATFORM_ADMIN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Principal principal = jwtService.parse(body.path("data").path("token").asText());
        assertThat(principal.username()).isEqualTo("DB Admin");
        assertThat(principal.roles()).contains("PLATFORM_ADMIN");

        StubServer.Recorded recorded = TENANT.lastRequest();
        assertThat(recorded.path()).isEqualTo("/internal/platform-users/authenticate");
        assertThat(recorded.header("x-internal-token")).isEqualTo("dev-internal-token");
    }

    @Test
    void legacyPresetFallbackStillWorksAndIsNotExposedAsList() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.principal.username").value("admin"))
                .andReturn();
        String json = result.getResponse().getContentAsString();
        assertThat(json).doesNotContain("presets").doesNotContain("presetUsers").doesNotContain("availableUsers");
    }

    @Test
    void multipleTenantMembershipsReturnSelection() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"multi\",\"password\":\"Multi@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tenantSelectionRequired").value(true))
                .andExpect(jsonPath("$.data.mfaRequired").value(false))
                .andExpect(jsonPath("$.data.tenants.length()").value(2))
                .andExpect(jsonPath("$.data.tenants[0].tenantCode").value("acme"))
                .andExpect(jsonPath("$.data.tenants[1].tenantCode").value("beta"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void explicitTenantCodeStillLogsInDirectly() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Alice@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-acme"))
                .andExpect(jsonPath("$.data.principal.orgUnitId").value("org-acme"))
                .andExpect(jsonPath("$.data.principal.authType").value("TENANT"));
    }

    @Test
    void switchableTenantsEndpointUsesCurrentIdentity() throws Exception {
        String token = tenantToken();
        mockMvc.perform(get("/api/auth/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].tenantCode").value("acme"))
                .andExpect(jsonPath("$.data[1].tenantCode").value("beta"));
        StubServer.Recorded recorded = lastRequest("/internal/auth/tenants");
        assertThat(recorded).isNotNull();
        assertThat(recorded.header("x-internal-token")).isEqualTo("dev-internal-token");
        assertThat(recorded.query()).contains("currentTenantId=t-acme");
        assertThat(recorded.query()).contains("accountId=acc-acme");
        assertThat(recorded.query()).contains("username=alice");
    }

    @Test
    void switchTenantIssuesTargetTenantJwt() throws Exception {
        String token = tenantToken();
        MvcResult result = mockMvc.perform(post("/api/auth/switch-tenant")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"beta\",\"password\":\"Alice@123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-beta"))
                .andExpect(jsonPath("$.data.principal.orgUnitId").value("org-beta"))
                .andExpect(jsonPath("$.data.principal.roles[0]").value("TENANT_ADMIN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Principal principal = jwtService.parse(body.path("data").path("token").asText());
        assertThat(principal.tenantId()).isEqualTo("t-beta");
        assertThat(principal.orgUnitId()).isEqualTo("org-beta");
        assertThat(principal.roles()).contains("TENANT_ADMIN");

        StubServer.Recorded recorded = lastRequest("/internal/auth/switch-tenant");
        assertThat(recorded.body()).contains("\"tenantCode\":\"beta\"");
        // Legacy v10 contract when a password is supplied: {username, tenantCode, password}.
        assertThat(recorded.body()).contains("\"username\":\"alice\"");
        assertThat(recorded.body()).contains("\"password\":\"Alice@123\"");
        assertThat(recorded.body()).contains("\"currentAccountId\":\"acc-acme\"");
    }

    @Test
    void passwordLessSwitchUsesCurrentAccountIdContract() throws Exception {
        String token = tenantToken();
        MvcResult result = mockMvc.perform(post("/api/auth/switch-tenant")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenantCode\":\"beta\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.principal.tenantId").value("t-beta"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        Principal principal = jwtService.parse(body.path("data").path("token").asText());
        assertThat(principal.tenantId()).isEqualTo("t-beta");

        StubServer.Recorded recorded = lastRequest("/internal/auth/switch-tenant");
        assertThat(recorded.body()).contains("\"currentAccountId\":\"acc-acme\"");
        assertThat(recorded.body()).contains("\"tenantCode\":\"beta\"");
        // v10.1 password-less payload contains no credentials.
        assertThat(recorded.body()).doesNotContain("\"password\"");
        assertThat(recorded.body()).doesNotContain("\"username\"");
    }

    @Test
    void platformAdministratorGetsEmptySwitcherList() throws Exception {
        MvcResult login = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"Admin@123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        String token = objectMapper.readTree(login.getResponse().getContentAsString())
                .path("data").path("token").asText();
        mockMvc.perform(get("/api/auth/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void switchableTenantsDegradeToEmptyListWhenCandidateEndpointFails() throws Exception {
        CANDIDATES_MODE.set("500");
        String token = tenantToken();
        mockMvc.perform(get("/api/auth/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void dynamicProvidersArePublicAndDriveAuthorization() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/providers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].code").value("corp"))
                .andExpect(jsonPath("$.data[0].name").value("Corp SSO"))
                .andExpect(jsonPath("$.data[0].loginUrl").value(
                        org.hamcrest.Matchers.containsString("provider=corp")));

        mockMvc.perform(get("/api/auth/oidc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.providerName").value("Corp SSO"));

        MvcResult authorize = mockMvc.perform(get("/api/auth/oidc/authorize").param("provider", "corp"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn();
        String authorizationUrl = objectMapper.readTree(authorize.getResponse().getContentAsString())
                .path("data").path("authorizationUrl").asText();
        Map<String, String> query = query(authorizationUrl);
        assertThat(authorizationUrl).startsWith(IDP_BASE.get() + "/authorize");
        assertThat(query.get("client_id")).isEqualTo("corp-client");
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(query.get("state")).isNotBlank();
    }

    @Test
    void dynamicProviderCallbackExchangesCodeAndIssuesJwt() throws Exception {
        MvcResult authorize = mockMvc.perform(get("/api/auth/oidc/authorize").param("provider", "corp"))
                .andExpect(status().isOk())
                .andReturn();
        String authorizationUrl = objectMapper.readTree(authorize.getResponse().getContentAsString())
                .path("data").path("authorizationUrl").asText();
        Map<String, String> query = query(authorizationUrl);

        MvcResult callback = mockMvc.perform(get("/api/auth/oidc/callback")
                        .param("code", "corp-code")
                        .param("state", query.get("state")))
                .andExpect(status().isFound())
                .andExpect(header().exists("Location"))
                .andReturn();
        String location = callback.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost:15173/sso-callback#token=");

        Principal principal = jwtService.parse(tokenFromRedirect(location));
        assertThat(principal.userId()).isEqualTo("sso-corp-1");
        assertThat(principal.username()).isEqualTo("Corp Alice");
        assertThat(principal.roles()).contains("TENANT_ADMIN");

        StubServer.Recorded tokenRequest = lastRequestOf(IDP, "/token");
        assertThat(tokenRequest).isNotNull();
        Map<String, String> form = form(tokenRequest.body());
        assertThat(form.get("client_id")).isEqualTo("corp-client");
        assertThat(form.get("client_secret")).isEqualTo("corp-secret");
        assertThat(form.get("redirect_uri")).isEqualTo("http://localhost:18080/api/auth/oidc/callback");
        assertThat(sha256Base64Url(form.get("code_verifier"))).isEqualTo(query.get("code_challenge"));
    }

    @Test
    void unknownDynamicProviderReturns404() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/authorize").param("provider", "unknown"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void enabledProviderListIsCached() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/providers")).andExpect(status().isOk());
        long first = providerFetchCount();
        mockMvc.perform(get("/api/auth/oidc/providers")).andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/oidc/status")).andExpect(status().isOk());
        assertThat(providerFetchCount()).isEqualTo(first);
    }

    private String tenantToken() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alice\",\"password\":\"Alice@123\",\"tenantCode\":\"acme\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString())
                .path("data").path("token").asText();
    }

    private long providerFetchCount() {
        return TENANT.requests().stream()
                .filter(request -> "/internal/sso-providers/enabled".equals(request.path()))
                .count();
    }

    private StubServer.Recorded lastRequest(String path) {
        return lastRequestOf(TENANT, path);
    }

    private StubServer.Recorded lastRequestOf(StubServer stub, String path) {
        StubServer.Recorded last = null;
        for (StubServer.Recorded request : stub.requests()) {
            if (path.equals(request.path())) {
                last = request;
            }
        }
        return last;
    }

    private Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        int index = url.indexOf('?');
        if (index < 0) {
            return values;
        }
        for (String pair : url.substring(index + 1).split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                values.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int split = pair.indexOf('=');
            if (split > 0) {
                values.put(URLDecoder.decode(pair.substring(0, split), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private String tokenFromRedirect(String location) {
        String fragment = location.substring(location.indexOf('#') + 1);
        for (String part : fragment.split("&")) {
            if (part.startsWith("token=")) {
                return URLDecoder.decode(part.substring("token=".length()), StandardCharsets.UTF_8);
            }
        }
        throw new AssertionError("redirect has no token: " + location);
    }

    private static String sha256Base64Url(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
