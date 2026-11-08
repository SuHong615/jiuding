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

/**
 * CLI v4/v5 command mapping: account security, audit CSV export, rating delta,
 * skill center, MCP keys/audits, onboarding, imports, roles/permissions.
 */
class CliV4V5Test {

    private static HttpServer server;
    private static String baseUrl;
    private static final List<Request> REQUESTS = new ArrayList<>();

    @TempDir
    Path tempDir;

    private Path configFile;

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", CliV4V5Test::handle);
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

    // ---------------------------------------------------------------- v4

    @Test
    void accountUnlockChangePasswordAndLoginEvents() {
        Result unlock = run("--base-url", baseUrl, "--token", "tkn",
                "account", "unlock", "a-1", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, unlock.exitCode, unlock.err);
        Request unlockRequest = REQUESTS.get(0);
        assertEquals("POST", unlockRequest.method);
        assertTrue(unlockRequest.path.endsWith("/accounts/a-1/unlock"));
        assertEquals("t-1", unlockRequest.headers.get("x-tenant-id"));

        REQUESTS.clear();
        Result change = run("--base-url", baseUrl, "--token", "tkn",
                "account", "change-password", "a-1", "--tenant", "t-1",
                "--old", "OldPass#123", "--new", "NewPass#456");
        assertEquals(ExitCodes.OK, change.exitCode, change.err);
        Request changeRequest = REQUESTS.get(0);
        assertTrue(changeRequest.path.endsWith("/accounts/a-1/change-password"));
        assertTrue(changeRequest.body.contains("\"oldPassword\":\"OldPass#123\""));
        assertTrue(changeRequest.body.contains("\"newPassword\":\"NewPass#456\""));

        REQUESTS.clear();
        Result events = run("--base-url", baseUrl, "--token", "tkn",
                "account", "login-events", "a-1", "--tenant", "t-1", "--size", "5");
        assertEquals(ExitCodes.OK, events.exitCode, events.err);
        assertTrue(events.out.contains("10.0.0.8"));
        assertTrue(events.out.contains("Chrome"));
        Request eventsRequest = REQUESTS.get(0);
        assertTrue(eventsRequest.path.endsWith("/accounts/a-1/login-events"));
        assertTrue(eventsRequest.query.contains("size=5"));
    }

    @Test
    void auditExportPrintsCsvAndWritesFile() throws Exception {
        Result printed = run("--base-url", baseUrl, "--token", "tkn",
                "audit", "export", "--tenant", "t-1", "--from", "2026-11-01T00:00:00Z");
        assertEquals(ExitCodes.OK, printed.exitCode, printed.err);
        assertTrue(printed.out.contains("at,actor,action,detail"));
        assertTrue(printed.out.contains("ACCOUNT_UNLOCKED"));
        Request csvRequest = REQUESTS.get(0);
        assertTrue(csvRequest.path.endsWith("/api/tenants/t-1/audits/export"));
        assertTrue(csvRequest.query.contains("format=csv"));
        assertTrue(csvRequest.query.contains("from=2026-11-01T00%3A00%3A00Z")
                || csvRequest.query.contains("from=2026-11-01T00:00:00Z"));

        Path outFile = tempDir.resolve("audit.csv");
        REQUESTS.clear();
        Result saved = run("--base-url", baseUrl, "--token", "tkn",
                "audit", "export", "--tenant", "t-1", "--out", outFile.toString());
        assertEquals(ExitCodes.OK, saved.exitCode, saved.err);
        assertTrue(Files.exists(outFile));
        assertTrue(Files.readString(outFile, StandardCharsets.UTF_8).contains("ACCOUNT_UNLOCKED"));
        assertTrue(saved.out.contains("已导出审计日志"));
    }

    @Test
    void ratingCurrentRendersDeltaColumns() {
        Result result = run("--base-url", baseUrl, "--token", "tkn",
                "rating", "current", "--tenant", "t-1");
        assertEquals(ExitCodes.OK, result.exitCode, result.err);
        assertTrue(result.out.contains("分数变化"));
        assertTrue(result.out.contains("等级变化"));
        assertTrue(result.out.contains("上次分数"));
    }

    // ---------------------------------------------------------------- v5 skill / mcp

    @Test
    void skillUploadSubmitReviewEnableDisable() throws Exception {
        Path zip = tempDir.resolve("my-skill.zip");
        Files.writeString(zip, "PK-fake-skill-package", StandardCharsets.UTF_8);

        Result upload = run("--base-url", baseUrl, "--token", "tkn",
                "skill", "upload", "--file", zip.toString(), "--submit-for-review");
        assertEquals(ExitCodes.OK, upload.exitCode, upload.err);
        Request uploadRequest = REQUESTS.get(0);
        assertEquals("POST", uploadRequest.method);
        assertEquals("/api/skills/upload", uploadRequest.path);
        assertTrue(uploadRequest.headers.get("content-type").startsWith("multipart/form-data"));
        assertTrue(uploadRequest.body.contains("submitForReview"));
        assertTrue(uploadRequest.body.contains("true"));

        REQUESTS.clear();
        Result submit = run("--base-url", baseUrl, "--token", "tkn", "skill", "submit", "my-skill");
        assertEquals(ExitCodes.OK, submit.exitCode, submit.err);
        assertTrue(REQUESTS.get(0).path.equals("/api/skills/my-skill/submit-review"));

        REQUESTS.clear();
        Result review = run("--base-url", baseUrl, "--token", "tkn",
                "skill", "review", "my-skill", "--decision", "APPROVE", "--comment", "ok");
        assertEquals(ExitCodes.OK, review.exitCode, review.err);
        assertTrue(REQUESTS.get(0).path.equals("/api/skills/my-skill/review"));
        assertTrue(REQUESTS.get(0).body.contains("\"decision\":\"APPROVE\""));

        REQUESTS.clear();
        Result rejectMissingComment = run("--base-url", baseUrl, "--token", "tkn",
                "skill", "review", "my-skill", "--decision", "REJECT");
        assertEquals(ExitCodes.USAGE, rejectMissingComment.exitCode);

        REQUESTS.clear();
        Result disable = run("--base-url", baseUrl, "--token", "tkn", "skill", "disable", "my-skill");
        assertEquals(ExitCodes.OK, disable.exitCode, disable.err);
        assertTrue(REQUESTS.get(0).path.equals("/api/skills/my-skill/disable"));

        REQUESTS.clear();
        Result enable = run("--base-url", baseUrl, "--token", "tkn", "skill", "enable", "my-skill");
        assertEquals(ExitCodes.OK, enable.exitCode, enable.err);
        assertTrue(REQUESTS.get(0).path.equals("/api/skills/my-skill/enable"));
    }

    @Test
    void mcpConfigKeysAndAudits() {
        Result config = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "mcp", "config");
        assertEquals(ExitCodes.OK, config.exitCode, config.err);
        assertTrue(config.out.contains("http://gateway:8080/mcp"));
        assertEquals("/api/mcp/config", REQUESTS.get(0).path);

        REQUESTS.clear();
        Result create = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "mcp", "keys", "create", "--name", "laptop", "--expires-days", "30");
        assertEquals(ExitCodes.OK, create.exitCode, create.err);
        assertTrue(create.out.contains("tk_test_plain"));
        Request createRequest = REQUESTS.get(0);
        assertEquals("POST", createRequest.method);
        assertEquals("/api/mcp/keys", createRequest.path);
        assertTrue(createRequest.body.contains("\"name\":\"laptop\""));
        assertTrue(createRequest.body.contains("\"expiresInDays\":30"));

        REQUESTS.clear();
        Result list = run("--base-url", baseUrl, "--token", "tkn", "mcp", "keys", "list");
        assertEquals(ExitCodes.OK, list.exitCode, list.err);
        assertTrue(list.out.contains("tk_test"));
        assertEquals("/api/mcp/keys", REQUESTS.get(0).path);

        REQUESTS.clear();
        Result revoke = run("--base-url", baseUrl, "--token", "tkn", "mcp", "keys", "revoke", "k-1");
        assertEquals(ExitCodes.OK, revoke.exitCode, revoke.err);
        assertEquals("DELETE", REQUESTS.get(0).method);
        assertEquals("/api/mcp/keys/k-1", REQUESTS.get(0).path);

        REQUESTS.clear();
        Result audits = run("--base-url", baseUrl, "--token", "tkn", "mcp", "audits",
                "--page", "1", "--size", "5", "--tool", "tenant.list");
        assertEquals(ExitCodes.OK, audits.exitCode, audits.err);
        assertTrue(audits.out.contains("tenant.list"));
        assertTrue(REQUESTS.get(0).query.contains("tool=tenant.list"));
        assertTrue(REQUESTS.get(0).query.contains("page=1"));
    }

    // ---------------------------------------------------------------- v5 onboarding / imports / rbac

    @Test
    void onboardingApplyIsPublicAndStatusQueries() {
        Result apply = run("--base-url", baseUrl, "onboarding", "apply",
                "--company-name", "Acme Cloud", "--contact-email", "ops@acme.test",
                "--plan", "PRO", "--industry", "saas");
        assertEquals(ExitCodes.OK, apply.exitCode, apply.err);
        assertTrue(apply.out.contains("APP-2026-0001"));
        Request applyRequest = REQUESTS.get(0);
        assertEquals("POST", applyRequest.method);
        assertEquals("/api/public/onboarding", applyRequest.path);
        assertFalse(applyRequest.headers.containsKey("authorization"),
                "public onboarding must not require a token");
        assertTrue(applyRequest.body.contains("\"companyName\":\"Acme Cloud\""));
        assertTrue(applyRequest.body.contains("\"industry\":\"saas\""));

        REQUESTS.clear();
        Result status = run("--base-url", baseUrl, "onboarding", "status", "APP-2026-0001");
        assertEquals(ExitCodes.OK, status.exitCode, status.err);
        assertTrue(status.out.contains("ACTIVATED"));
        assertEquals("/api/public/onboarding/APP-2026-0001", REQUESTS.get(0).path);
    }

    @Test
    void tenantImportOrgsAndAccountsFromCsv() throws Exception {
        Path orgs = tempDir.resolve("orgs.csv");
        Files.writeString(orgs, "parentCode,code,name,type\n,root,总部,COMPANY\n", StandardCharsets.UTF_8);
        Result orgsResult = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "import-orgs", "--tenant", "t-1", "--file", orgs.toString());
        assertEquals(ExitCodes.OK, orgsResult.exitCode, orgsResult.err);
        assertTrue(orgsResult.out.contains("导入"));
        assertTrue(REQUESTS.get(0).path.endsWith("/org-units/import"));
        assertTrue(REQUESTS.get(0).headers.get("content-type").startsWith("multipart/form-data"));

        Path accounts = tempDir.resolve("accounts.csv");
        Files.writeString(accounts,
                "username,displayName,email,phone,orgCode,role\nbob,Bob,bob@acme.test,,root,MEMBER\n",
                StandardCharsets.UTF_8);
        REQUESTS.clear();
        Result accountsResult = run("--base-url", baseUrl, "--token", "tkn",
                "tenant", "import-accounts", "--tenant", "t-1", "--file", accounts.toString());
        assertEquals(ExitCodes.OK, accountsResult.exitCode, accountsResult.err);
        assertTrue(accountsResult.out.contains("TmpPass#123"));
        assertTrue(REQUESTS.get(0).path.endsWith("/accounts/import"));
    }

    @Test
    void rolesPermissionsAndMePermissions() {
        Result roles = run("--base-url", baseUrl, "--token", "tkn",
                "roles", "list", "--scope", "TENANT");
        assertEquals(ExitCodes.OK, roles.exitCode, roles.err);
        assertTrue(roles.out.contains("TENANT_ADMIN"));
        assertTrue(REQUESTS.get(0).path.equals("/api/platform/roles"));
        assertTrue(REQUESTS.get(0).query.contains("scope=TENANT"));

        REQUESTS.clear();
        Result permissions = run("--base-url", baseUrl, "--token", "tkn", "roles", "permissions");
        assertEquals(ExitCodes.OK, permissions.exitCode, permissions.err);
        assertTrue(permissions.out.contains("org.read"));
        assertEquals("/api/platform/permissions", REQUESTS.get(0).path);

        REQUESTS.clear();
        Result me = run("--base-url", baseUrl, "--token", "tkn", "--output", "json",
                "me", "permissions");
        assertEquals(ExitCodes.OK, me.exitCode, me.err);
        assertTrue(me.out.contains("TENANT_ADMIN"));
        assertTrue(me.out.contains("org.read"));
        assertEquals("/api/me/permissions", REQUESTS.get(0).path);
    }

    @Test
    void helpListsV4V5CommandGroups() {
        Result result = run("--help");
        assertEquals(ExitCodes.OK, result.exitCode);
        for (String group : new String[]{"audit", "roles", "me", "onboarding", "skill", "mcp", "account"}) {
            assertTrue(result.out.contains(group), "missing help entry: " + group);
        }
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
        if (path.startsWith("/api/tenants/t-1/audits/export")) {
            contentType = "text/csv;charset=UTF-8";
            response = "at,actor,action,detail\n2026-11-08T01:00:00Z,admin,ACCOUNT_UNLOCKED,alice\n";
        } else if (path.endsWith("/accounts/a-1/unlock")) {
            response = envelope("{\"account\":{\"id\":\"a-1\",\"username\":\"alice\",\"status\":\"ACTIVE\"}}");
        } else if (path.endsWith("/accounts/a-1/change-password")) {
            response = envelope("{\"account\":{\"id\":\"a-1\",\"username\":\"alice\","
                    + "\"status\":\"ACTIVE\",\"mustChangePassword\":false}}");
        } else if (path.endsWith("/accounts/a-1/login-events")) {
            response = envelope("{\"items\":[{\"at\":\"2026-11-08T01:00:00Z\",\"ip\":\"10.0.0.8\","
                    + "\"userAgent\":\"Chrome/120\",\"success\":true}],\"total\":1,\"page\":0,\"size\":20}");
        } else if (path.equals("/api/tenants/t-1/ratings/current")) {
            response = envelope("{\"id\":\"r-2\",\"grade\":\"A\",\"score\":77,"
                    + "\"previousGrade\":\"B\",\"previousScore\":72,\"scoreDelta\":5,"
                    + "\"gradeChanged\":true,\"source\":\"SKILL\",\"assessedBy\":\"system\","
                    + "\"createdAt\":\"2026-11-08T01:00:00Z\"}");
        } else if (path.equals("/api/skills/upload")) {
            response = envelope("{\"id\":\"my-skill\",\"version\":\"1.0.0\",\"type\":\"RISK\","
                    + "\"source\":\"UPLOADED\",\"status\":\"PENDING_REVIEW\",\"enabled\":false}");
        } else if (path.equals("/api/skills/my-skill/submit-review")) {
            response = envelope("{\"id\":\"my-skill\",\"version\":\"1.0.0\",\"status\":\"PENDING_REVIEW\"}");
        } else if (path.equals("/api/skills/my-skill/review")) {
            response = envelope("{\"id\":\"my-skill\",\"version\":\"1.0.0\",\"status\":\"APPROVED\","
                    + "\"enabled\":true,\"reviewedBy\":\"admin\"}");
        } else if (path.equals("/api/skills/my-skill/enable")
                || path.equals("/api/skills/my-skill/disable")) {
            response = envelope("{\"id\":\"my-skill\",\"version\":\"1.0.0\",\"status\":\"APPROVED\","
                    + "\"enabled\":true}");
        } else if (path.equals("/api/mcp/config")) {
            response = envelope("{\"endpoint\":\"http://gateway:8080/mcp\",\"transport\":\"STREAMABLE_HTTP\","
                    + "\"protocolVersion\":\"2025-06-18\",\"keyHint\":\"tk_\",\"docs\":\"/docs/mcp\","
                    + "\"sampleConfig\":{\"mcpServers\":{\"tenancy\":{\"url\":\"http://gateway:8080/mcp\"}}}}");
        } else if (path.equals("/api/mcp/keys") && "POST".equals(method)) {
            response = envelope("{\"id\":\"k-2\",\"name\":\"laptop\",\"keyPrefix\":\"tk_test\","
                    + "\"apiKey\":\"tk_test_plain_secret\",\"expiresAt\":\"2026-12-08T00:00:00Z\"}");
        } else if (path.equals("/api/mcp/keys") && "GET".equals(method)) {
            response = envelope("[{\"id\":\"k-1\",\"name\":\"ci\",\"keyPrefix\":\"tk_test\","
                    + "\"status\":\"ACTIVE\",\"createdAt\":\"2026-11-01T00:00:00Z\"}]");
        } else if (path.equals("/api/mcp/keys/k-1") && "DELETE".equals(method)) {
            response = envelope("{\"id\":\"k-1\",\"status\":\"REVOKED\"}");
        } else if (path.equals("/api/mcp/audits")) {
            response = envelope("{\"items\":[{\"at\":\"2026-11-08T01:00:00Z\",\"tool\":\"tenant.list\","
                    + "\"success\":true,\"durationMs\":12,\"keyPrefix\":\"tk_test\"}],"
                    + "\"total\":1,\"page\":0,\"size\":20}");
        } else if (path.equals("/api/public/onboarding") && "POST".equals(method)) {
            response = envelope("{\"applicationNo\":\"APP-2026-0001\",\"tenantCode\":\"acme-cloud\","
                    + "\"status\":\"ACTIVATED\",\"message\":\"tenant activated\"}");
        } else if (path.startsWith("/api/public/onboarding/")) {
            response = envelope("{\"applicationNo\":\"APP-2026-0001\",\"tenantCode\":\"acme-cloud\","
                    + "\"status\":\"ACTIVATED\",\"message\":\"tenant activated\"}");
        } else if (path.endsWith("/org-units/import")) {
            response = envelope("{\"total\":2,\"imported\":2,\"skipped\":0,\"failed\":0,\"errors\":[]}");
        } else if (path.endsWith("/accounts/import")) {
            response = envelope("{\"total\":1,\"imported\":1,\"skipped\":0,\"failed\":0,\"errors\":[],"
                    + "\"tempPasswords\":[{\"row\":2,\"username\":\"bob\",\"tempPassword\":\"TmpPass#123\"}]}");
        } else if (path.equals("/api/platform/roles")) {
            response = envelope("[{\"code\":\"TENANT_ADMIN\",\"name\":\"租户管理员\",\"scope\":\"TENANT\","
                    + "\"builtin\":true,\"status\":\"ACTIVE\"},"
                    + "{\"code\":\"MEMBER\",\"name\":\"成员\",\"scope\":\"TENANT\",\"builtin\":true}]");
        } else if (path.equals("/api/platform/permissions")) {
            response = envelope("[{\"code\":\"org.read\",\"name\":\"查看组织\",\"groupCode\":\"ORG\"},"
                    + "{\"code\":\"account.write\",\"name\":\"管理账号\",\"groupCode\":\"ACCOUNT\"}]");
        } else if (path.equals("/api/me/permissions")) {
            response = envelope("{\"roles\":[\"TENANT_ADMIN\"],"
                    + "\"permissions\":[\"org.read\",\"account.write\"]}");
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

    private static String envelope(String dataJson) {
        return "{\"code\":0,\"message\":\"ok\",\"data\":" + dataJson
                + ",\"traceId\":null,\"timestamp\":\"2026-11-08T04:00:00Z\"}";
    }
}
