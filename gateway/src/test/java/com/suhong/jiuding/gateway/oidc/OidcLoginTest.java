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
package com.suhong.jiuding.gateway.oidc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.security.JwtService;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * OIDC authorization-code + PKCE flow against an embedded stub IdP (docs/16 §2):
 * discovery caching, signed state, S256 PKCE, token exchange, role mapping, JWT issuance
 * and the final frontend redirect.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OidcLoginTest {

    private static final String STATE_SECRET = "oidc-test-state-secret-1234567890";
    private static final AtomicReference<String> IDP_BASE = new AtomicReference<>();
    private static volatile String userinfoRolesJson = "[\"PLATFORM_ADMIN\",\"unknown-role\"]";

    private static final StubServer IDP = new StubServer((request, exchange) -> {
        String base = IDP_BASE.get();
        switch (request.path()) {
            case "/.well-known/openid-configuration" -> StubServer.respond(exchange, 200,
                    "application/json", "{\"issuer\":\"" + base + "\","
                            + "\"authorization_endpoint\":\"" + base + "/authorize\","
                            + "\"token_endpoint\":\"" + base + "/token\","
                            + "\"userinfo_endpoint\":\"" + base + "/userinfo\"}");
            case "/token" -> StubServer.respond(exchange, 200, "application/json",
                    "{\"access_token\":\"at-oidc\",\"token_type\":\"Bearer\",\"expires_in\":3600}");
            case "/userinfo" -> {
                String authorization = request.header("authorization");
                if (!"Bearer at-oidc".equals(authorization)) {
                    StubServer.respond(exchange, 401, "application/json", "{}");
                } else {
                    StubServer.respond(exchange, 200, "application/json",
                            "{\"sub\":\"sso-user-1\",\"name\":\"Alice SSO\","
                                    + "\"preferred_username\":\"alice\",\"email\":\"alice@example.com\","
                                    + "\"roles\":" + userinfoRolesJson + "}");
                }
            }
            default -> StubServer.respond(exchange, 404, "application/json", "{}");
        }
    });

    static {
        IDP_BASE.set(IDP.baseUrl());
    }

    @DynamicPropertySource
    static void oidcProperties(DynamicPropertyRegistry registry) {
        registry.add("tenancy.auth.oidc.enabled", () -> "true");
        registry.add("tenancy.auth.oidc.provider-name", () -> "Stub SSO");
        registry.add("tenancy.auth.oidc.issuer", IDP::baseUrl);
        registry.add("tenancy.auth.oidc.client-id", () -> "tenancy-gateway");
        registry.add("tenancy.auth.oidc.client-secret", () -> "test-client-secret");
        registry.add("tenancy.auth.oidc.redirect-uri",
                () -> "http://localhost:18080/api/auth/oidc/callback");
        registry.add("tenancy.auth.oidc.frontend-redirect",
                () -> "http://localhost:15173/sso-callback");
        registry.add("tenancy.auth.oidc.scopes", () -> "openid profile email");
        registry.add("tenancy.auth.oidc.role-claim", () -> "roles");
        registry.add("tenancy.auth.oidc.default-role", () -> "AUDITOR");
        registry.add("tenancy.auth.oidc.state-secret", () -> STATE_SECRET);
    }

    @AfterAll
    static void stopIdp() {
        IDP.close();
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Test
    void statusReportsEnabledProviderAndLoginUrl() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.providerName").value("Stub SSO"))
                .andExpect(jsonPath("$.data.loginUrl").value("/api/auth/oidc/authorize"));
    }

    @Test
    void authorizeBuildsSignedStateAndPkceChallenge() throws Exception {
        Map<String, String> query = query(authorizationUrl());
        assertThat(query.get("response_type")).isEqualTo("code");
        assertThat(query.get("client_id")).isEqualTo("tenancy-gateway");
        assertThat(query.get("redirect_uri")).isEqualTo("http://localhost:18080/api/auth/oidc/callback");
        assertThat(query.get("scope")).contains("openid").contains("profile").contains("email");
        assertThat(query.get("state")).isNotBlank().contains(".");
        assertThat(query.get("code_challenge")).hasSize(43);
        assertThat(query.get("code_challenge_method")).isEqualTo("S256");
        assertThat(IDP.requests().stream()
                .anyMatch(request -> "/.well-known/openid-configuration".equals(request.path())))
                .isTrue();
    }

    @Test
    void callbackExchangesCodeAndIssuesJwt() throws Exception {
        Map<String, String> authorizeQuery = query(authorizationUrl());
        MvcResult callback = mockMvc.perform(get("/api/auth/oidc/callback")
                        .param("code", "oidc-code")
                        .param("state", authorizeQuery.get("state")))
                .andExpect(status().isFound())
                .andExpect(header().exists("Location"))
                .andReturn();
        String location = callback.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://localhost:15173/sso-callback#token=");
        assertThat(location).contains("&username=");

        String token = tokenFromRedirect(location);
        Principal principal = jwtService.parse(token);
        assertThat(principal.userId()).isEqualTo("sso-user-1");
        assertThat(principal.username()).isEqualTo("Alice SSO");
        assertThat(principal.tenantId()).isNull();
        assertThat(principal.roles()).contains("PLATFORM_ADMIN");
        assertThat(principal.roles()).doesNotContain("unknown-role");

        // The same JWT authenticates the protected /api/auth/me endpoint.
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("Alice SSO"))
                .andExpect(jsonPath("$.data.authType").value("PLATFORM"));

        // Token exchange forwarded code/verifier/client credentials; PKCE verifier matches.
        StubServer.Recorded tokenRequest = lastRequest("/token");
        assertThat(tokenRequest).isNotNull();
        Map<String, String> form = form(tokenRequest.body());
        assertThat(form.get("grant_type")).isEqualTo("authorization_code");
        assertThat(form.get("code")).isEqualTo("oidc-code");
        assertThat(form.get("client_id")).isEqualTo("tenancy-gateway");
        assertThat(form.get("client_secret")).isEqualTo("test-client-secret");
        assertThat(form.get("redirect_uri"))
                .isEqualTo("http://localhost:18080/api/auth/oidc/callback");
        assertThat(pkceChallenge(form.get("code_verifier")))
                .isEqualTo(authorizeQuery.get("code_challenge"));

        // Userinfo was called with the exchanged bearer token.
        StubServer.Recorded userinfo = lastRequest("/userinfo");
        assertThat(userinfo).isNotNull();
        assertThat(userinfo.header("authorization")).isEqualTo("Bearer at-oidc");
    }

    @Test
    void tamperedStateRedirectsWithError() throws Exception {
        Map<String, String> authorizeQuery = query(authorizationUrl());
        String tampered = authorizeQuery.get("state") + "tampered";
        MvcResult result = mockMvc.perform(get("/api/auth/oidc/callback")
                        .param("code", "oidc-code")
                        .param("state", tampered))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:15173/sso-callback#error=invalid_state");
    }

    @Test
    void expiredStateRedirectsWithError() throws Exception {
        String nonce = "expirednonce";
        long issuedAt = Instant.now().toEpochMilli() - 11L * 60L * 1000L;
        String signature = hmacBase64Url(nonce + "." + issuedAt);
        MvcResult result = mockMvc.perform(get("/api/auth/oidc/callback")
                        .param("code", "oidc-code")
                        .param("state", nonce + "." + issuedAt + "." + signature))
                .andExpect(status().isFound())
                .andReturn();
        assertThat(result.getResponse().getHeader("Location"))
                .isEqualTo("http://localhost:15173/sso-callback#error=invalid_state");
    }

    @Test
    void unknownRolesFallBackToDefaultRole() throws Exception {
        String previous = userinfoRolesJson;
        userinfoRolesJson = "[\"some-external-group\"]";
        try {
            Map<String, String> authorizeQuery = query(authorizationUrl());
            MvcResult callback = mockMvc.perform(get("/api/auth/oidc/callback")
                            .param("code", "oidc-code")
                            .param("state", authorizeQuery.get("state")))
                    .andExpect(status().isFound())
                    .andReturn();
            Principal principal = jwtService.parse(tokenFromRedirect(callback.getResponse().getHeader("Location")));
            assertThat(principal.roles()).containsExactly("AUDITOR");
        } finally {
            userinfoRolesJson = previous;
        }
    }

    @Test
    void redirectModeReturns302ToProvider() throws Exception {
        mockMvc.perform(get("/api/auth/oidc/authorize").param("redirect", "true"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location",
                        org.hamcrest.Matchers.startsWith(IDP.baseUrl() + "/authorize")));
    }

    private String authorizationUrl() throws Exception {
        MvcResult result = mockMvc.perform(get("/api/auth/oidc/authorize"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("authorizationUrl").asText();
    }

    private Map<String, String> query(String url) {
        Map<String, String> values = new LinkedHashMap<>();
        String query = url.substring(url.indexOf('?') + 1);
        for (String pair : query.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                values.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
            }
        }
        return values;
    }

    private Map<String, String> form(String body) {
        Map<String, String> values = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            int index = pair.indexOf('=');
            if (index > 0) {
                values.put(URLDecoder.decode(pair.substring(0, index), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(index + 1), StandardCharsets.UTF_8));
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

    private StubServer.Recorded lastRequest(String path) {
        StubServer.Recorded last = null;
        for (StubServer.Recorded request : IDP.requests()) {
            if (path.equals(request.path())) {
                last = request;
            }
        }
        return last;
    }

    private static String pkceChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(verifier.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String hmacBase64Url(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(STATE_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }
}
