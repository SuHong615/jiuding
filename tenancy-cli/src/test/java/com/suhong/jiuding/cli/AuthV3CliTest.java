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
package com.suhong.jiuding.cli;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * v3 auth CLI: tenant-code login (docs/13 §6), principal persistence and offline whoami.
 */
class AuthV3CliTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", AuthV3CliTest::handle);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    @BeforeEach
    void setUp() {
        REQUESTS.clear();
        configFile = tempDir.resolve("config.json");
        System.setProperty("tenancy.config", configFile.toString());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("tenancy.config");
    }

    @Test
    void loginWithTenantCodeSendsTenantCodeAndPersistsPrincipal() {
        Result result = run("--base-url", baseUrl,
                "auth", "login", "--username", "alice", "--password", "secret",
                "--tenant-code", "acme");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("alice"));
        assertTrue(result.out.contains("TENANT_ADMIN"));

        Request login = REQUESTS.get(0);
        assertEquals("POST", login.method);
        assertEquals("/api/auth/login", login.path);
        assertTrue(login.body.contains("\"tenantCode\":\"acme\""));
        assertTrue(login.body.contains("\"username\":\"alice\""));

        CliConfig saved = new ConfigStore(configFile).load();
        assertEquals("tenant-token", saved.token);
        assertEquals("t-acme", saved.tenantId);
        assertEquals("ou-root", saved.orgUnitId);
        assertEquals("alice", saved.username);
        assertTrue(saved.roles != null && saved.roles.contains("TENANT_ADMIN"));
        assertEquals("TENANT", saved.authType);
        assertEquals(Boolean.FALSE, saved.mustChangePassword);
    }

    @Test
    void loginWithoutTenantCodeOmitsFieldFromRequestBody() {
        Result result = run("--base-url", baseUrl,
                "auth", "login", "--username", "alice", "--password", "secret");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertFalse(REQUESTS.get(0).body.contains("tenantCode"));
    }

    @Test
    void loginFailureMapsToAuthenticationExitCode3() {
        Result result = run("--base-url", baseUrl,
                "auth", "login", "--username", "bad", "--password", "nope");

        assertEquals(ExitCodes.AUTH, result.exitCode);
        assertTrue(result.err.contains("认证失败"));
    }

    @Test
    void loginDownstreamUnavailableMapsToNetworkExitCode5() {
        Result result = run("--base-url", baseUrl,
                "auth", "login", "--username", "down", "--password", "secret");

        assertEquals(ExitCodes.NETWORK, result.exitCode);
        assertTrue(result.err.contains("网络错误"));
    }

    @Test
    void whoamiParsesConfiguredTokenIntoJson() {
        saveConfig(fakeJwt("""
                {"sub":"u-alice","name":"alice","tid":"t-acme",
                 "roles":["TENANT_ADMIN","AUDITOR"],"oid":"ou-root",
                 "iat":1700000000,"exp":4102444800}
                """));

        Result result = run("--base-url", baseUrl, "--output", "json", "auth", "whoami");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("\"username\""));
        assertTrue(result.out.contains("alice"));
        assertTrue(result.out.contains("u-alice"));
        assertTrue(result.out.contains("t-acme"));
        assertTrue(result.out.contains("ou-root"));
        assertTrue(result.out.contains("TENANT_ADMIN"));
        assertTrue(result.out.contains("expiresAt"));
        assertTrue(result.out.contains("\"expired\""));
        assertTrue(result.out.contains("false"));
        assertTrue(REQUESTS.isEmpty(), "whoami must not call the network");
    }

    @Test
    void whoamiRendersTableWithOrgUnitAndExpiry() {
        saveConfig(fakeJwt("""
                {"sub":"u-alice","name":"alice","tid":"t-acme","roles":["MEMBER"],
                 "oid":"ou-child","iat":1700000000,"exp":4102444800}
                """));

        Result result = run("--base-url", baseUrl, "auth", "whoami");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("组织单元"));
        assertTrue(result.out.contains("ou-child"));
        assertTrue(result.out.contains("alice"));
        assertTrue(result.out.contains("有效"));
    }

    @Test
    void whoamiWithoutTokenExitsAuthenticationCode3() {
        Result result = run("--base-url", baseUrl, "auth", "whoami");

        assertEquals(ExitCodes.AUTH, result.exitCode);
        assertTrue(result.err.contains("认证失败"));
    }

    @Test
    void whoamiExplicitTokenOverridesConfigAndRejectsMalformedTokens() {
        saveConfig("not-a-valid-token");
        Result result = run("--base-url", baseUrl, "--token",
                fakeJwt("{\"sub\":\"u-2\",\"name\":\"bob\",\"roles\":[\"PLATFORM_ADMIN\"]}"),
                "--output", "json", "auth", "whoami");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("bob"));
        assertTrue(result.out.contains("PLATFORM_ADMIN"));

        Result malformed = run("--base-url", baseUrl, "--token", "only.two", "--output", "json",
                "auth", "whoami");
        assertEquals(ExitCodes.AUTH, malformed.exitCode);
    }

    // ---------------------------------------------------------------- helpers

    private void saveConfig(String token) {
        CliConfig config = new CliConfig();
        config.baseUrl = baseUrl;
        config.token = token;
        new ConfigStore(configFile).save(config);
    }

    private Result run(String... args) {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exitCode = new CliRunner().execute(args,
                new PrintWriter(out, true), new PrintWriter(err, true));
        return new Result(exitCode, out.toString(), err.toString());
    }

    private record Result(int exitCode, String out, String err) {
    }

    private record Request(String method, String path, String query, Map<String, String> headers, String body) {
    }

    private static String fakeJwt(String payloadJson) {
        String header = base64Url("{\"alg\":\"HS256\",\"typ\":\"JWT\"}");
        String payload = base64Url(payloadJson);
        return header + "." + payload + ".signature";
    }

    private static String base64Url(String value) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String method = exchange.getRequestMethod();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        Headers rawHeaders = exchange.getRequestHeaders();
        for (String name : rawHeaders.keySet()) {
            headers.put(name.toLowerCase(Locale.ROOT), rawHeaders.getFirst(name));
        }
        REQUESTS.add(new Request(method, path,
                exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery(),
                headers, body));

        int status = 200;
        String response;
        if (path.equals("/api/auth/login") && body.contains("\"username\":\"bad\"")) {
            status = 401;
            response = "{\"code\":40100,\"message\":\"invalid username or password\",\"data\":null}";
        } else if (path.equals("/api/auth/login") && body.contains("\"username\":\"down\"")) {
            status = 503;
            response = "{\"code\":50300,\"message\":\"tenant-service unavailable\",\"data\":null}";
        } else if (path.equals("/api/auth/login")) {
            response = envelope("{\"token\":\"tenant-token\",\"tokenType\":\"Bearer\",\"expiresIn\":7200,"
                    + "\"principal\":{\"userId\":\"u-alice\",\"username\":\"alice\",\"tenantId\":\"t-acme\","
                    + "\"roles\":[\"TENANT_ADMIN\"],\"orgUnitId\":\"ou-root\",\"authType\":\"TENANT\","
                    + "\"mustChangePassword\":false}}");
        } else {
            status = 404;
            response = "{\"code\":40400,\"message\":\"not found\",\"data\":null}";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json;charset=UTF-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String envelope(String dataJson) {
        return "{\"code\":0,\"message\":\"ok\",\"data\":" + dataJson
                + ",\"traceId\":null,\"timestamp\":\"2026-11-08T04:00:00Z\"}";
    }
}
