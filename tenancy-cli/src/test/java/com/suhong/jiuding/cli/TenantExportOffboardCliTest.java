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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** CLI v8 tenant export / offboard commands (docs/18 §5). */
class TenantExportOffboardCliTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", TenantExportOffboardCliTest::handle);
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
    void exportDefaultsToMaskAndSavesJsonFile() throws Exception {
        Path outFile = tempDir.resolve("export/tenant-t-1.json");
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "export", "t-1", "--out", outFile.toString());

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(Files.exists(outFile), "export file must be created");
        String saved = Files.readString(outFile, StandardCharsets.UTF_8);
        assertTrue(saved.contains("\"exportedAt\""));
        assertTrue(saved.contains("\"tenant\""));
        assertTrue(saved.contains("\"accounts\""));
        assertTrue(result.out.contains("已导出租户数据"));

        Request request = REQUESTS.get(0);
        assertEquals("GET", request.method);
        assertEquals("/api/tenants/t-1/export", request.path);
        assertTrue(request.query.contains("mask=true"), "mask defaults to true");
        assertEquals("t-1", request.headers.get("x-tenant-id"));
        assertEquals("Bearer tkn", request.headers.get("authorization"));
    }

    @Test
    void exportNoMaskPrintsJsonDocument() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "tenant", "export", "t-1", "--no-mask");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("exportedAt"));
        assertTrue(REQUESTS.get(0).query.contains("mask=false"));
    }

    @Test
    void exportNoMaskForbiddenShowsFriendlyHint() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "export", "t-403", "--no-mask");

        assertEquals(ExitCodes.AUTH, result.exitCode);
        assertTrue(result.err.contains("平台管理员"));
        assertTrue(result.err.contains("--mask"), "hint should suggest masked export: " + result.err);
    }

    @Test
    void exportMaskFlagsAreMutuallyExclusive() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "export", "t-1", "--mask", "--no-mask");
        assertEquals(ExitCodes.USAGE, result.exitCode);
    }

    @Test
    void offboardPostsReasonAndPrintsStatusAndDeletedAt() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "offboard", "t-1", "--reason", "contract ended");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("TERMINATED"));
        assertTrue(result.out.contains("删除时间"));
        assertTrue(result.out.contains("2026-11-08T12:00:00Z"));

        Request request = REQUESTS.get(0);
        assertEquals("POST", request.method);
        assertEquals("/api/tenants/t-1/offboard", request.path);
        assertEquals("t-1", request.headers.get("x-tenant-id"));
        assertTrue(request.body.contains("\"reason\":\"contract ended\""));
    }

    @Test
    void offboardRequiresReason() {
        Result missing = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "offboard", "t-1");
        assertEquals(ExitCodes.USAGE, missing.exitCode);
        assertTrue(missing.err.contains("参数错误"));

        Result blank = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "offboard", "t-1", "--reason", " ");
        assertEquals(ExitCodes.USAGE, blank.exitCode);
        assertTrue(blank.err.contains("不能为空"));
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
        String method = exchange.getRequestMethod();
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        Headers rawHeaders = exchange.getRequestHeaders();
        for (String name : rawHeaders.keySet()) {
            headers.put(name.toLowerCase(Locale.ROOT), rawHeaders.getFirst(name));
        }
        REQUESTS.add(new Request(method, path, query, headers, body));

        int status = 200;
        String contentType = "application/json;charset=UTF-8";
        String response;
        if (path.startsWith("/api/tenants/") && path.endsWith("/export")) {
            if (path.startsWith("/api/tenants/t-403/") && query.contains("mask=false")) {
                status = 403;
                response = "{\"code\":40300,\"message\":\"only platform admin can export unmasked data\","
                        + "\"data\":null}";
            } else {
                response = "{\"exportedAt\":\"2026-11-08T12:00:00Z\","
                        + "\"tenant\":{\"id\":\"t-1\",\"tenantCode\":\"acme\",\"name\":\"Acme\"},"
                        + "\"orgUnits\":[{\"id\":\"ou-1\",\"code\":\"HQ\",\"name\":\"HQ\"}],"
                        + "\"accounts\":[{\"id\":\"a-1\",\"username\":\"alice\",\"email\":\"a***@acme.test\"}],"
                        + "\"rating\":{\"current\":null,\"history\":[]},"
                        + "\"audits\":[{\"at\":\"2026-11-08T11:00:00Z\",\"actor\":\"admin\","
                        + "\"action\":\"TENANT_EXPORTED\",\"detail\":\"mask=true\"}]}";
            }
        } else if (path.equals("/api/tenants/t-1/offboard")) {
            response = "{\"code\":0,\"message\":\"ok\",\"data\":{\"id\":\"t-1\","
                    + "\"tenantCode\":\"acme\",\"status\":\"TERMINATED\","
                    + "\"deletedAt\":\"2026-11-08T12:00:00Z\","
                    + "\"updatedAt\":\"2026-11-08T12:00:00Z\"}}";
        } else {
            status = 404;
            response = "{\"code\":40400,\"message\":\"not found\",\"data\":null}";
        }

        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
