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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end-ish CLI tests against a local JDK HttpServer stub. No network access and no
 * dependency on the real services: verifies envelope unwrapping, exit codes, auth headers,
 * MCP JSON-RPC plumbing and that login persists the token.
 */
class CliStubServerTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", CliStubServerTest::handle);
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

    @AfterEach
    void tearDown() {
        System.clearProperty("tenancy.config");
    }

    @Test
    void loginPersistsTokenAndPrintsJson() throws Exception {
        Result result = run("--base-url", baseUrl, "--output", "json",
                "auth", "login", "--username", "admin", "--password", "Admin@123");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("jwt-test-token"));
        assertTrue(result.out.contains("PLATFORM_ADMIN"));

        CliConfig saved = new ConfigStore(configFile).load();
        assertEquals("jwt-test-token", saved.token);
        assertEquals("admin", saved.username);
        assertEquals(baseUrl, saved.baseUrl);

        Request login = REQUESTS.stream()
                .filter(request -> request.path.equals("/api/auth/login"))
                .findFirst().orElseThrow();
        assertTrue(login.body.contains("\"username\":\"admin\""));
        assertTrue(login.body.contains("Admin@123"));
    }

    @Test
    void tenantListUnwrapsPageResultAndSendsAuthHeaders() {
        Result result = run("--base-url", baseUrl, "--token", "tkn-123", "--output", "json",
                "tenant", "list", "--status", "ACTIVE");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("t-001"));
        assertTrue(result.out.contains("ACME"));

        Request list = REQUESTS.get(0);
        assertEquals("/api/tenants", list.path);
        assertTrue(list.query.contains("status=ACTIVE"));
        assertEquals("Bearer tkn-123", list.headers.get("authorization"));
        assertTrue(list.headers.containsKey("x-request-id"));
    }

    @Test
    void tenantListTableOutputRendersRows() {
        Result result = run("--base-url", baseUrl, "--token", "tkn-123",
                "tenant", "list");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("t-001"));
        assertTrue(result.out.contains("ACME"));
        assertTrue(result.out.contains("|"));
        assertTrue(result.out.contains("共 1 条"));
    }

    @Test
    void unauthorizedResponseMapsToExitCode3() {
        Result result = run("--base-url", baseUrl, "--token", "bad-token",
                "tenant", "list");

        assertEquals(ExitCodes.AUTH, result.exitCode);
        assertTrue(result.err.contains("认证失败"));
    }

    @Test
    void businessErrorMapsToExitCode4() {
        Result result = run("--base-url", baseUrl, "--token", "tkn-123",
                "tenant", "get", "conflict");

        assertEquals(ExitCodes.BUSINESS, result.exitCode);
        assertTrue(result.err.contains("业务错误"));
    }

    @Test
    void networkFailureMapsToExitCode5() {
        Result result = run("--base-url", "http://127.0.0.1:1", "tenant", "list");
        assertEquals(ExitCodes.NETWORK, result.exitCode);
        assertTrue(result.err.contains("网络错误"));
    }

    @Test
    void mcpToolsCallSendsApiKeyAndPrintsStructuredContent() {
        Result result = run("--base-url", baseUrl, "--api-key", "secret-key", "--output", "json",
                "mcp", "tools", "call", "--name", "tenant.list", "--args", "{\"size\":5}");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("total"));

        Request rpc = REQUESTS.get(0);
        assertEquals("/mcp", rpc.path);
        assertEquals("secret-key", rpc.headers.get("x-api-key"));
        assertTrue(rpc.body.contains("\"method\":\"tools/call\""));
        assertTrue(rpc.body.contains("tenant.list"));
    }

    @Test
    void mcpToolErrorMapsToBusinessExitCode() {
        Result result = run("--base-url", baseUrl, "--output", "json",
                "mcp", "tools", "call", "--name", "tenant.get", "--args", "{}");

        assertEquals(ExitCodes.BUSINESS, result.exitCode);
        assertTrue(result.err.contains("NOT_FOUND"));
    }

    @Test
    void collectImportRejectsUnsupportedFileType() throws Exception {
        Path file = tempDir.resolve("data.txt");
        Files.writeString(file, "hello", StandardCharsets.UTF_8);
        Result result = run("--base-url", baseUrl, "collect", "import", "--file", file.toString());
        assertEquals(ExitCodes.USAGE, result.exitCode);
        assertTrue(result.err.contains("参数错误"));
    }

    // ------------------------------------------------------------------

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

    private static void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        Headers rawHeaders = exchange.getRequestHeaders();
        for (String name : rawHeaders.keySet()) {
            headers.put(name.toLowerCase(java.util.Locale.ROOT), rawHeaders.getFirst(name));
        }
        REQUESTS.add(new Request(exchange.getRequestMethod(), path, query, headers, body));

        int status = 200;
        String response;
        if (path.equals("/api/auth/login")) {
            response = envelope("{\"token\":\"jwt-test-token\",\"tokenType\":\"Bearer\","
                    + "\"expiresIn\":7200,\"principal\":{\"userId\":\"u-admin\",\"username\":\"admin\","
                    + "\"tenantId\":null,\"roles\":[\"PLATFORM_ADMIN\"]}}");
        } else if (path.equals("/api/tenants")) {
            String authorization = headers.get("authorization");
            if ("Bearer bad-token".equals(authorization)) {
                status = 401;
                response = "{\"code\":40100,\"message\":\"token expired\",\"data\":null}";
            } else {
                response = envelope("{\"items\":[{\"id\":\"t-001\",\"tenantCode\":\"ACME\","
                        + "\"name\":\"ACME 公司\",\"plan\":\"PRO\",\"status\":\"ACTIVE\","
                        + "\"riskLevel\":\"LOW\",\"region\":\"cn-shanghai\","
                        + "\"createdAt\":\"2026-11-07T00:00:00Z\"}],\"total\":1,\"page\":0,\"size\":20}");
            }
        } else if (path.startsWith("/api/tenants/")) {
            String id = URLDecoder.decode(path.substring("/api/tenants/".length()), StandardCharsets.UTF_8);
            if ("conflict".equals(id)) {
                response = "{\"code\":40900,\"message\":\"tenant already exists\",\"data\":null}";
            } else {
                response = envelope("{\"id\":\"" + id + "\",\"tenantCode\":\"ACME\",\"name\":\"ACME 公司\","
                        + "\"plan\":\"PRO\",\"status\":\"ACTIVE\"}");
            }
        } else if (path.equals("/mcp")) {
            response = handleRpc(body);
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

    private static String handleRpc(String body) {
        JsonNodeHolder holder = JsonNodeHolder.parse(body);
        String method = holder.method;
        if ("tools/list".equals(method)) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                    + "{\"name\":\"tenant.list\",\"description\":\"分页查询租户\",\"inputSchema\":{\"type\":\"object\"}},"
                    + "{\"name\":\"tenant.get\",\"description\":\"租户详情\",\"inputSchema\":{\"type\":\"object\"}}]}}";
        }
        if ("tools/call".equals(method) && holder.body.contains("\"tenant.get\"")) {
            return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\","
                    + "\"text\":\"{\\\"ok\\\":false}\"}],\"structuredContent\":{\"errorCode\":\"NOT_FOUND\","
                    + "\"message\":\"tenant missing\"},\"isError\":true}}";
        }
        return "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{\"type\":\"text\","
                + "\"text\":\"{\\\"total\\\":1}\"}],\"structuredContent\":{\"total\":1,\"items\":[]},"
                + "\"isError\":false}}";
    }

    /** Minimal JSON extraction to keep the stub free of extra dependencies. */
    private static final class JsonNodeHolder {
        private final String method;
        private final String body;

        private JsonNodeHolder(String method, String body) {
            this.method = method;
            this.body = body;
        }

        static JsonNodeHolder parse(String raw) {
            String method = "";
            int index = raw.indexOf("\"method\"");
            if (index >= 0) {
                int start = raw.indexOf('"', raw.indexOf(':', index)) + 1;
                int end = raw.indexOf('"', start);
                method = raw.substring(start, end);
            }
            return new JsonNodeHolder(method, raw);
        }
    }

    private static String envelope(String dataJson) {
        return "{\"code\":0,\"message\":\"ok\",\"data\":" + dataJson
                + ",\"traceId\":null,\"timestamp\":\"2026-11-07T12:00:00Z\"}";
    }
}
