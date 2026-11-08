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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI v7 quota/usage command (docs/17 §3/§8). */
class QuotaCliTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", QuotaCliTest::handle);
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
        System.setProperty("tenancy.config", tempDir.resolve("config.json").toString());
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        System.clearProperty("tenancy.config");
    }

    @Test
    void quotaJsonOutputMapsUsagePayload() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "tenant", "quota", "t-1");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("\"seats\""));
        assertTrue(result.out.contains("\"percent\" : 120"));
        assertTrue(result.out.contains("EXCEEDED"));

        Request request = REQUESTS.get(0);
        assertEquals("GET", request.method);
        assertEquals("/api/tenants/t-1/quota", request.path);
        assertEquals("t-1", request.headers.get("x-tenant-id"));
        assertEquals("Bearer tkn", request.headers.get("authorization"));
    }

    @Test
    void quotaTableShowsUsedLimitPercentAndStatus() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "quota", "t-1");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("席位"));
        assertTrue(result.out.contains("组织单元"));
        assertTrue(result.out.contains("120%"));
        assertTrue(result.out.contains("8"));
        assertTrue(result.out.contains("不限"), "null limit must render as unlimited");
        assertTrue(result.out.contains("EXCEEDED"));
        assertTrue(result.out.contains("API RPM"));
    }

    @Test
    void usageAliasMapsToSameQuotaEndpoint() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "tenant", "usage", "t-1");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertEquals("/api/tenants/t-1/quota", REQUESTS.get(0).path);
        assertTrue(result.out.contains("\"tenantId\" : \"t-1\""));
    }

    @Test
    void quotaRequiresTenantIdArgument() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "tenant", "quota");
        assertEquals(ExitCodes.USAGE, result.exitCode);
        assertTrue(result.err.contains("参数错误"));
    }

    // ---------------------------------------------------------------- helpers

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
        Map<String, String> headers = new LinkedHashMap<>();
        exchange.getRequestHeaders().forEach((name, values) ->
                headers.put(name.toLowerCase(Locale.ROOT), String.join(",", values)));
        REQUESTS.add(new Request(exchange.getRequestMethod(), path,
                exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery(),
                headers, ""));

        int status = 200;
        String response;
        if (path.equals("/api/tenants/t-1/quota")) {
            response = "{\"code\":0,\"message\":\"ok\",\"data\":{\"tenantId\":\"t-1\",\"plan\":\"PRO\","
                    + "\"seats\":{\"limit\":10,\"used\":12,\"remaining\":0,\"percent\":120},"
                    + "\"orgUnits\":{\"limit\":50,\"used\":8,\"remaining\":42,\"percent\":16},"
                    + "\"storageGb\":{\"limit\":100,\"used\":null,\"remaining\":null},"
                    + "\"apiRpm\":{\"limit\":null},\"status\":\"EXCEEDED\"}}";
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
}
