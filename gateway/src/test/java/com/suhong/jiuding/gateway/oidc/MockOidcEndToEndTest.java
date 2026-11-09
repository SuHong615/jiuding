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
import com.suhong.jiuding.gateway.auth.LoginRateLimiter;
import com.suhong.jiuding.gateway.support.StubServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * v12 dev-only Mock OIDC provider (docs/23 §4): discovery/authorize/token/userinfo and the
 * complete gateway SSO flow authorize → callback → JWT → protected API, plus the
 * {@code tenancy.auth.preset-fallback-enabled} switch being on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "tenancy.auth.mock-oidc.enabled=true",
        "tenancy.auth.preset-fallback-enabled=true",
        "tenancy.auth.oidc.enabled=false",
        "tenancy.auth.oidc.providers-cache-seconds=60"
})
class MockOidcEndToEndTest {

    private static final AtomicReference<String> MOCK_ISSUER = new AtomicReference<>("http://127.0.0.1:0/mock-oidc");
    private static final AtomicReference<String> CALLBACK_URI = new AtomicReference<>("http://127.0.0.1:0/api/auth/oidc/callback");
    private static final String FRONTEND_REDIRECT = "http://localhost:15173/sso-callback";

    private static final StubServer TENANT = new StubServer((request, exchange) -> {
        switch (request.path()) {
            case "/internal/sso-providers/enabled" -> StubServer.respond(exchange, 200,
                    "application/json;charset=UTF-8",
                    "{\"code\":0,\"message\":\"ok\",\"data\":[{\"code\":\"mock\",\"name\":\"Mock IdP\","
                            + "\"issuer\":\"" + MOCK_ISSUER.get() + "\",\"clientId\":\"mock-client\","
                            + "\"clientSecret\":\"mock-secret\",\"scopes\":\"openid profile email\","
                            + "\"redirectUri\":\"" + CALLBACK_URI.get() + "\","
                            + "\"frontendRedirect\":\"" + FRONTEND_REDIRECT + "\","
                            + "\"roleClaim\":\"roles\",\"defaultRole\":\"AUDITOR\"}]}");
            case "/internal/platform-users/authenticate" -> StubServer.respond(exchange, 401,
                    "application/json;charset=UTF-8", "{\"code\":40100,\"message\":\"invalid credentials\"}");
            case "/internal/auth/authenticate" -> StubServer.respond(exchange, 401,
                    "application/json;charset=UTF-8", "{\"code\":40100,\"message\":\"invalid credentials\"}");
            default -> StubServer.respond(exchange, 404, "application/json;charset=UTF-8",
                    "{\"code\":40400,\"message\":\"not found\"}");
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
    static void stopStub() {
        TENANT.close();
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    @Autowired
    private SsoProviderClient ssoProviderClient;

    @Autowired
    private LoginRateLimiter loginRateLimiter;

    private final HttpClient http = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    @BeforeEach
    void setUp() {
        String gateway = "http://127.0.0.1:" + port;
        MOCK_ISSUER.set(gateway + "/mock-oidc");
        CALLBACK_URI.set(gateway + "/api/auth/oidc/callback");
        ssoProviderClient.clearCache();
        loginRateLimiter.reset();
    }

    @Test
    void mockOidcEndpointsSupportAuthorizationCodeWithPkce() throws Exception {
        String base = MOCK_ISSUER.get();
        Response discovery = get(base + "/.well-known/openid-configuration");
        assertThat(discovery.status()).isEqualTo(200);
        JsonNode document = objectMapper.readTree(discovery.body());
        assertThat(document.path("issuer").asText()).isEqualTo(base);
        assertThat(document.path("authorization_endpoint").asText()).isEqualTo(base + "/authorize");
        assertThat(document.path("token_endpoint").asText()).isEqualTo(base + "/token");
        assertThat(document.path("userinfo_endpoint").asText()).isEqualTo(base + "/userinfo");

        String verifier = "verifier-" + UUID.randomUUID();
        String challenge = base64Url(sha256(verifier));
        String authorizeUrl = base + "/authorize?response_type=code&client_id=mock-client"
                + "&redirect_uri=" + encode(CALLBACK_URI.get())
                + "&state=st-1&scope=openid"
                + "&code_challenge=" + encode(challenge)
                + "&code_challenge_method=S256";
        Response authorize = get(authorizeUrl);
        assertThat(authorize.status()).isEqualTo(302);
        assertThat(authorize.location()).startsWith(CALLBACK_URI.get() + "?code=");
        assertThat(authorize.location()).contains("state=st-1");
        String code = queryParam(authorize.location(), "code");

        String form = "grant_type=authorization_code&code=" + encode(code)
                + "&redirect_uri=" + encode(CALLBACK_URI.get())
                + "&client_id=mock-client&client_secret=mock-secret";
        Response wrongVerifier = postForm(base + "/token", form + "&code_verifier=wrong-verifier");
        assertThat(wrongVerifier.status()).isEqualTo(400);
        assertThat(objectMapper.readTree(wrongVerifier.body()).path("error").asText())
                .isEqualTo("invalid_grant");

        Response token = postForm(base + "/token", form + "&code_verifier=" + encode(verifier));
        assertThat(token.status()).isEqualTo(200);
        JsonNode tokenBody = objectMapper.readTree(token.body());
        assertThat(tokenBody.path("token_type").asText()).isEqualTo("Bearer");
        String accessToken = tokenBody.path("access_token").asText();
        assertThat(accessToken).isNotBlank();

        Response userinfo = getWithBearer(base + "/userinfo", accessToken);
        assertThat(userinfo.status()).isEqualTo(200);
        JsonNode claims = objectMapper.readTree(userinfo.body());
        assertThat(claims.path("sub").asText()).isEqualTo("mock-user");
        assertThat(claims.path("name").asText()).isEqualTo("Mock User");
        assertThat(claims.path("roles").get(0).asText()).isEqualTo("TENANT_ADMIN");

        Response missingRedirect = get(base + "/authorize?response_type=code&client_id=x");
        assertThat(missingRedirect.status()).isEqualTo(400);
    }

    @Test
    void gatewaySsoFlowThroughMockProviderIssuesJwt() throws Exception {
        String gateway = "http://127.0.0.1:" + port;
        Response providers = get(gateway + "/api/auth/oidc/providers");
        assertThat(providers.status()).isEqualTo(200);
        JsonNode providerList = objectMapper.readTree(providers.body()).path("data");
        assertThat(providerList.get(0).path("code").asText()).isEqualTo("mock");

        Response authorize = get(gateway + "/api/auth/oidc/authorize?provider=mock");
        assertThat(authorize.status()).isEqualTo(200);
        String authorizationUrl = objectMapper.readTree(authorize.body())
                .path("data").path("authorizationUrl").asText();
        assertThat(authorizationUrl).startsWith(MOCK_ISSUER.get() + "/authorize");

        Response idpAuthorize = get(authorizationUrl);
        assertThat(idpAuthorize.status()).isEqualTo(302);
        String code = queryParam(idpAuthorize.location(), "code");
        String state = queryParam(idpAuthorize.location(), "state");
        assertThat(code).isNotBlank();
        assertThat(state).isNotBlank();

        Response callback = get(gateway + "/api/auth/oidc/callback?code=" + encode(code)
                + "&state=" + encode(state));
        assertThat(callback.status()).isEqualTo(302);
        assertThat(callback.location()).startsWith(FRONTEND_REDIRECT + "#token=");
        String token = fragmentParam(callback.location(), "token");

        Principal principal = jwtService.parse(token);
        assertThat(principal.userId()).isEqualTo("mock-user");
        assertThat(principal.username()).isEqualTo("Mock User");
        assertThat(principal.roles()).contains("TENANT_ADMIN");

        Response me = getWithBearer(gateway + "/api/auth/me", token);
        assertThat(me.status()).isEqualTo(200);
        JsonNode meBody = objectMapper.readTree(me.body()).path("data");
        assertThat(meBody.path("username").asText()).isEqualTo("Mock User");
        assertThat(meBody.path("authType").asText()).isEqualTo("PLATFORM");
        assertThat(meBody.path("roles").get(0).asText()).isEqualTo("TENANT_ADMIN");
    }

    @Test
    void presetFallbackCanBeEnabledForLocalDevelopment() throws Exception {
        String gateway = "http://127.0.0.1:" + port;
        Response login = postJson(gateway + "/api/auth/login",
                "{\"username\":\"admin\",\"password\":\"Admin@123\"}");
        assertThat(login.status()).isEqualTo(200);
        assertThat(objectMapper.readTree(login.body()).path("data").path("token").asText())
                .isNotBlank();
    }

    private Response get(String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response(response);
    }

    private Response getWithBearer(String url, String token) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10)).header("Authorization", "Bearer " + token).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response(response);
    }

    private Response postForm(String url, String form) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response(response);
    }

    private Response postJson(String url, String json) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json;charset=UTF-8")
                .POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return response(response);
    }

    private static Response response(HttpResponse<String> response) {
        return new Response(response.statusCode(), response.body(),
                response.headers().firstValue("Location").orElse(null));
    }

    private static String queryParam(String url, String name) {
        int index = url.indexOf('?');
        if (index < 0) {
            return null;
        }
        for (String pair : url.substring(index + 1).split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && name.equals(pair.substring(0, split))) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String fragmentParam(String url, String name) {
        int index = url.indexOf('#');
        if (index < 0) {
            return null;
        }
        for (String pair : url.substring(index + 1).split("&")) {
            int split = pair.indexOf('=');
            if (split > 0 && name.equals(pair.substring(0, split))) {
                return URLDecoder.decode(pair.substring(split + 1), StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String sha256Text(String value) {
        return base64Url(sha256(value));
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Url(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private record Response(int status, String body, String location) {
    }
}
