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
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Org / account / rating CLI commands against a local JDK HttpServer stub.
 * Verifies request mapping (paths, query, bodies, X-Tenant-Id), table/JSON output,
 * exit codes and the {@code tenant delete --yes} confirmation gate.
 */
class OrgAccountRatingCliTest {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", OrgAccountRatingCliTest::handle);
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

    // ---------------------------------------------------------------- org

    @Test
    void orgTreeSendsTenantHeaderAndFormat() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "org", "tree", "--tenant", "t-1");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("ou-root"));
        Request recorded = REQUESTS.get(0);
        assertEquals("GET", recorded.method);
        assertEquals("/api/tenants/t-1/org-units", recorded.path);
        assertTrue(recorded.query.contains("format=tree"));
        assertEquals("Bearer tkn", recorded.headers.get("authorization"));
        assertEquals("t-1", recorded.headers.get("x-tenant-id"));
    }

    @Test
    void orgListTableRendersIndentedTree() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "org", "list", "--tenant", "t-1", "--status", "ACTIVE");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("HQ"));
        assertTrue(result.out.contains("|"));
        assertTrue(REQUESTS.get(0).query.contains("format=flat"));
        assertTrue(REQUESTS.get(0).query.contains("status=ACTIVE"));
    }

    @Test
    void orgCreatePostsBodyAndTenantHeader() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "org", "create", "--tenant", "t-1", "--parent", "ou-root",
                "--code", "RD", "--name", "研发部", "--type", "team");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        Request recorded = REQUESTS.get(0);
        assertEquals("POST", recorded.method);
        assertEquals("/api/tenants/t-1/org-units", recorded.path);
        assertEquals("t-1", recorded.headers.get("x-tenant-id"));
        assertTrue(recorded.body.contains("\"code\":\"RD\""));
        assertTrue(recorded.body.contains("\"type\":\"TEAM\""));
        assertTrue(recorded.body.contains("\"parentId\":\"ou-root\""));
    }

    @Test
    void orgCreateRejectsInvalidType() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "org", "create", "--tenant", "t-1", "--code", "X", "--name", "X", "--type", "DIVISION");
        assertEquals(ExitCodes.USAGE, result.exitCode);
        assertTrue(result.err.contains("参数错误"));
    }

    @Test
    void orgMoveAndDeleteMapToRestEndpoints() {
        Result move = run("--base-url", baseUrl, "--token", "tkn",
                "org", "move", "ou-1", "--tenant", "t-1", "--parent", "ou-root");
        assertEquals(ExitCodes.OK, move.exitCode, move.err);
        assertTrue(REQUESTS.get(0).path.contains("/org-units/ou-1/move"));
        assertTrue(REQUESTS.get(0).body.contains("\"parentId\":\"ou-root\""));

        REQUESTS.clear();
        Result delete = run("--base-url", baseUrl, "--token", "tkn",
                "org", "delete", "ou-1", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, delete.exitCode, delete.err);
        assertEquals("DELETE", REQUESTS.get(0).method);
        assertEquals("/api/tenants/t-1/org-units/ou-1", REQUESTS.get(0).path);
    }

    // ---------------------------------------------------------------- account

    @Test
    void accountListForwardsFilters() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "account", "list", "--tenant", "t-1", "--role", "MEMBER", "--page", "0", "--size", "10");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("alice"));
        Request recorded = REQUESTS.get(0);
        assertEquals("/api/tenants/t-1/accounts", recorded.path);
        assertTrue(recorded.query.contains("role=MEMBER"));
        assertTrue(recorded.query.contains("size=10"));
        assertEquals("t-1", recorded.headers.get("x-tenant-id"));
    }

    @Test
    void accountCreateRendersOneTimePassword() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "account", "create", "--tenant", "t-1", "--username", "bob",
                "--display-name", "Bob", "--role", "member", "--email", "bob@example.com");

        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("Tmp#123456"));
        Request recorded = REQUESTS.get(0);
        assertTrue(recorded.body.contains("\"username\":\"bob\""));
        assertTrue(recorded.body.contains("\"role\":\"MEMBER\""));
        assertEquals("t-1", recorded.headers.get("x-tenant-id"));
    }

    @Test
    void accountDisableEnableAndResetPassword() {
        Result disable = run("--base-url", baseUrl, "--token", "tkn",
                "account", "disable", "a-1", "--tenant", "t-1", "--reason", "left company");
        assertEquals(ExitCodes.OK, disable.exitCode, disable.err);
        assertEquals("POST", REQUESTS.get(0).method);
        assertTrue(REQUESTS.get(0).path.endsWith("/accounts/a-1/status"));
        assertTrue(REQUESTS.get(0).body.contains("\"status\":\"DISABLED\""));

        REQUESTS.clear();
        Result enable = run("--base-url", baseUrl, "--token", "tkn",
                "account", "enable", "a-1", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, enable.exitCode, enable.err);
        assertTrue(REQUESTS.get(0).body.contains("\"status\":\"ACTIVE\""));

        REQUESTS.clear();
        Result reset = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "account", "reset-password", "a-1", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, reset.exitCode, reset.err);
        assertTrue(reset.out.contains("Reset#654321"));
        assertTrue(REQUESTS.get(0).path.endsWith("/accounts/a-1/reset-password"));
    }

    @Test
    void accountDeleteUsesDeleteMethod() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "account", "delete", "a-1", "--tenant", "t-1", "--yes");
        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertEquals("DELETE", REQUESTS.get(0).method);
        assertEquals("/api/tenants/t-1/accounts/a-1", REQUESTS.get(0).path);
    }

    // ---------------------------------------------------------------- rating

    @Test
    void ratingCurrentHistoryAssessOverride() {
        Result current = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "rating", "current", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, current.exitCode, current.err);
        assertTrue(current.out.contains("\"grade\""));
        assertEquals("/api/tenants/t-1/ratings/current", REQUESTS.get(0).path);

        REQUESTS.clear();
        Result history = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "rating", "history", "--tenant", "t-1", "--page", "0", "--size", "5");
        assertEquals(ExitCodes.OK, history.exitCode, history.err);
        assertEquals("/api/tenants/t-1/ratings", REQUESTS.get(0).path);
        assertTrue(REQUESTS.get(0).query.contains("size=5"));

        REQUESTS.clear();
        Result assess = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "assess", "--tenant", "t-1", "--note", "quarterly");
        assertEquals(ExitCodes.OK, assess.exitCode, assess.err);
        assertEquals("/api/tenants/t-1/ratings/assess", REQUESTS.get(0).path);
        assertTrue(REQUESTS.get(0).body.contains("quarterly"));

        REQUESTS.clear();
        Result override = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "override", "--tenant", "t-1", "--grade", "aa", "--score", "85",
                "--reason", "board decision");
        assertEquals(ExitCodes.OK, override.exitCode, override.err);
        assertEquals("/api/tenants/t-1/ratings/override", REQUESTS.get(0).path);
        assertTrue(REQUESTS.get(0).body.contains("\"grade\":\"AA\""));
        assertTrue(REQUESTS.get(0).body.contains("\"reason\":\"board decision\""));
    }

    @Test
    void ratingOverrideRequiresGradeReasonAndValidScore() {
        Result missingReason = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "override", "--tenant", "t-1", "--grade", "A", "--score", "75");
        assertEquals(ExitCodes.USAGE, missingReason.exitCode);

        Result badGrade = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "override", "--tenant", "t-1", "--grade", "S", "--score", "75", "--reason", "x");
        assertEquals(ExitCodes.USAGE, badGrade.exitCode);

        Result badScore = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "override", "--tenant", "t-1", "--grade", "A", "--score", "150", "--reason", "x");
        assertEquals(ExitCodes.USAGE, badScore.exitCode);
    }

    // ---------------------------------------------------------------- tenant delete + usage

    @Test
    void tenantDeleteRequiresYesConfirmation() {
        Result refused = run("--base-url", baseUrl, "--token", "tkn", "tenant", "delete", "t-2");
        assertEquals(ExitCodes.USAGE, refused.exitCode);
        assertTrue(refused.err.contains("--yes"));
        assertTrue(REQUESTS.isEmpty());
    }

    @Test
    void tenantDeleteSendsDeleteWithConfirmation() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "delete", "t-2", "--yes");
        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertEquals("DELETE", REQUESTS.get(0).method);
        assertEquals("/api/tenants/t-2", REQUESTS.get(0).path);
    }

    @Test
    void orgCommandWithoutTenantFailsWithUsageCode() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "org", "list");
        assertEquals(ExitCodes.USAGE, result.exitCode);
        assertTrue(result.err.contains("缺少租户 ID"));
    }

    @Test
    void globalTenantOptionIsUsedWhenCommandTenantMissing() {
        Result result = run("--base-url", baseUrl, "--token", "tkn", "--tenant", "t-1",
                "--output", "json", "account", "list");
        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertEquals("t-1", REQUESTS.get(0).headers.get("x-tenant-id"));
    }

    @Test
    void helpListsNewCommandGroups() {
        Result result = run("--help");
        assertEquals(ExitCodes.OK, result.exitCode);
        assertTrue(result.out.contains("org"));
        assertTrue(result.out.contains("account"));
        assertTrue(result.out.contains("rating"));
    }

    @Test
    void parsesNestedOrgAccountRatingCommands() {
        CommandLine commandLine = TenancyCli.commandLine();
        ParseResult root = commandLine.parseArgs(
                "org", "create", "--tenant", "t-1", "--code", "RD", "--name", "研发部",
                "--type", "TEAM");
        ParseResult org = root.subcommand();
        ParseResult create = org.subcommand();
        assertInstanceOf(com.suhong.jiuding.cli.commands.OrgCommands.CreateCommand.class,
                create.commandSpec().userObject());
        assertEquals("RD", create.matchedOption("--code").getValue());

        ParseResult accountRoot = commandLine.parseArgs(
                "account", "reset-password", "a-1", "--tenant", "t-1");
        ParseResult account = accountRoot.subcommand();
        ParseResult reset = account.subcommand();
        assertInstanceOf(com.suhong.jiuding.cli.commands.AccountCommands.ResetPasswordCommand.class,
                reset.commandSpec().userObject());

        ParseResult ratingRoot = commandLine.parseArgs(
                "rating", "override", "--tenant", "t-1", "--grade", "AA", "--score", "85", "--reason", "x");
        ParseResult rating = ratingRoot.subcommand();
        ParseResult override = rating.subcommand();
        assertInstanceOf(com.suhong.jiuding.cli.commands.RatingCommands.OverrideCommand.class,
                override.commandSpec().userObject());
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
        String query = exchange.getRequestURI().getRawQuery() == null ? "" : exchange.getRequestURI().getRawQuery();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        Headers rawHeaders = exchange.getRequestHeaders();
        for (String name : rawHeaders.keySet()) {
            headers.put(name.toLowerCase(Locale.ROOT), rawHeaders.getFirst(name));
        }
        String method = exchange.getRequestMethod();
        REQUESTS.add(new Request(method, path, query, headers, body));

        int status = 200;
        String response;
        if (path.equals("/api/tenants/t-1/org-units")) {
            response = "POST".equals(method)
                    ? envelope("{\"id\":\"ou-new\",\"code\":\"RD\",\"name\":\"研发部\",\"type\":\"TEAM\","
                    + "\"parentId\":\"ou-root\",\"path\":\"/ou-root/ou-new\",\"depth\":1,\"status\":\"ACTIVE\"}")
                    : envelope("[{\"id\":\"ou-root\",\"code\":\"HQ\",\"name\":\"总部\",\"type\":\"COMPANY\","
                    + "\"depth\":0,\"status\":\"ACTIVE\",\"accountCount\":2,\"childCount\":1}]");
        } else if (path.equals("/api/tenants/t-1/org-units/ou-1/move")) {
            response = envelope("{\"id\":\"ou-1\",\"code\":\"RD\",\"name\":\"研发部\",\"parentId\":\"ou-root\","
                    + "\"path\":\"/ou-root/ou-1\",\"depth\":2,\"updatedAt\":\"2026-11-08T02:00:00Z\"}");
        } else if (path.equals("/api/tenants/t-1/org-units/ou-1")) {
            response = envelope("{\"deleted\":true}");
        } else if (path.equals("/api/tenants/t-1/accounts")) {
            response = "POST".equals(method)
                    ? envelope("{\"account\":{\"id\":\"a-new\",\"username\":\"bob\",\"displayName\":\"Bob\","
                    + "\"role\":\"MEMBER\",\"status\":\"INVITED\",\"orgUnitId\":\"ou-root\"},"
                    + "\"tempPassword\":\"Tmp#123456\"}")
                    : envelope("{\"items\":[{\"id\":\"a-1\",\"username\":\"alice\",\"displayName\":\"Alice\","
                    + "\"role\":\"MEMBER\",\"status\":\"ACTIVE\",\"email\":\"alice@example.com\"}],"
                    + "\"total\":1,\"page\":0,\"size\":20}");
        } else if (path.equals("/api/tenants/t-1/accounts/a-1/status")) {
            String target = body.contains("DISABLED") ? "DISABLED" : "ACTIVE";
            response = envelope("{\"account\":{\"id\":\"a-1\",\"username\":\"alice\",\"displayName\":\"Alice\","
                    + "\"role\":\"MEMBER\",\"status\":\"" + target + "\",\"updatedAt\":\"2026-11-08T02:00:00Z\"}}");
        } else if (path.equals("/api/tenants/t-1/accounts/a-1/reset-password")) {
            response = envelope("{\"tempPassword\":\"Reset#654321\"}");
        } else if (path.equals("/api/tenants/t-1/accounts/a-1")) {
            response = envelope("{\"deleted\":true}");
        } else if (path.equals("/api/tenants/t-1/ratings/current")) {
            response = envelope("{\"id\":\"r-1\",\"tenantId\":\"t-1\",\"grade\":\"A\",\"score\":72,"
                    + "\"dims\":{\"business\":70,\"compliance\":80,\"financial\":60,\"security\":75,\"service\":65},"
                    + "\"source\":\"SKILL\",\"assessedBy\":\"system\",\"createdAt\":\"2026-11-08T01:00:00Z\"}");
        } else if (path.equals("/api/tenants/t-1/ratings") && "GET".equals(method)) {
            response = envelope("{\"items\":[{\"id\":\"r-1\",\"grade\":\"A\",\"score\":72,"
                    + "\"source\":\"SKILL\",\"assessedBy\":\"system\",\"createdAt\":\"2026-11-08T01:00:00Z\"}],"
                    + "\"total\":1,\"page\":0,\"size\":20}");
        } else if (path.equals("/api/tenants/t-1/ratings/assess")) {
            response = envelope("{\"id\":\"r-2\",\"grade\":\"AA\",\"score\":83,\"source\":\"SKILL\","
                    + "\"dims\":{\"business\":80,\"compliance\":85,\"financial\":80,\"security\":85,\"service\":80},"
                    + "\"assessedBy\":\"system\",\"createdAt\":\"2026-11-08T02:00:00Z\"}");
        } else if (path.equals("/api/tenants/t-1/ratings/override")) {
            response = envelope("{\"id\":\"r-3\",\"grade\":\"AA\",\"score\":85,\"source\":\"MANUAL\","
                    + "\"assessedBy\":\"admin\",\"note\":\"board decision\",\"createdAt\":\"2026-11-08T03:00:00Z\"}");
        } else if (path.equals("/api/tenants/t-2") && "DELETE".equals(method)) {
            response = envelope("{\"id\":\"t-2\",\"tenantCode\":\"old\",\"status\":\"TERMINATED\","
                    + "\"deletedAt\":\"2026-11-08T03:00:00Z\"}");
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
                + ",\"traceId\":null,\"timestamp\":\"2026-11-08T02:00:00Z\"}";
    }
}
