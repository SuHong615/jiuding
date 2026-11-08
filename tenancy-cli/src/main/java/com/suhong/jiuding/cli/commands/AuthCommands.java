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
package com.suhong.jiuding.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.cli.ApiException;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliConfig;
import com.suhong.jiuding.cli.CliContext;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ConfigStore;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import com.suhong.jiuding.cli.JwtToken;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.BufferedReader;
import java.io.Console;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code tenancy auth login|logout|whoami}. */
public final class AuthCommands {

    private AuthCommands() {
    }

    @Command(name = "auth", description = "认证：登录（平台用户/租户账号）/ 登出 / 当前身份",
            mixinStandardHelpOptions = true,
            subcommands = {AuthCommands.Login.class, AuthCommands.Logout.class, AuthCommands.Whoami.class})
    public static class Auth extends BaseCommand.Group {
    }

    @Command(name = "login", mixinStandardHelpOptions = true,
            description = "登录并把 token/principal 保存到配置文件")
    public static class Login extends BaseCommand {

        @Option(names = "--username", paramLabel = "<name>", required = true, description = "用户名")
        String username;

        @Option(names = "--password", paramLabel = "<pwd>", description = "密码（非交互环境必填）")
        String password;

        @Option(names = "--password-stdin", description = "从标准输入读取密码")
        boolean passwordStdin;

        @Option(names = "--tenant-code", paramLabel = "<code>",
                description = "租户代码（租户账号登录时填写；平台用户留空）")
        String tenantCode;

        @Override
        public Integer call() {
            String pwd = resolvePassword();
            if (pwd == null || pwd.isEmpty()) {
                throw new CliUsageException("缺少密码：请使用 --password 或 --password-stdin（交互终端会提示输入）");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("password", pwd);
            if (tenantCode != null && !tenantCode.isBlank()) {
                body.put("tenantCode", tenantCode.trim());
            }

            JsonNode data;
            try {
                data = api().post("/api/auth/login", body);
            } catch (ApiException e) {
                if (isDownstreamUnavailable(e)) {
                    throw new ApiException(ExitCodes.NETWORK, e.apiCode(),
                            "认证服务不可用: " + e.getMessage(), e);
                }
                // Every non-network login failure is an authentication failure (exit code 3).
                throw new ApiException(ExitCodes.AUTH, e.apiCode(), "登录失败: " + e.getMessage(), e);
            }

            String token = Json.text(data, "token", null);
            if (token == null || token.isBlank()) {
                throw new ApiException(ExitCodes.AUTH, ExitCodes.CODE_UNAUTHORIZED, "登录响应缺少 token");
            }

            JsonNode principal = Json.field(data, "principal");
            JwtToken claims = tryParseToken(token);
            String principalUsername = firstNonBlank(
                    Json.text(principal, "username", null),
                    claims == null ? null : claims.text("name"),
                    username);
            String userId = firstNonBlank(
                    Json.text(principal, "userId", null),
                    claims == null ? null : claims.text("sub"));
            String tenantId = blankToNull(firstNonBlank(
                    Json.text(principal, "tenantId", null),
                    claims == null ? null : claims.text("tid")));
            String orgUnitId = blankToNull(firstNonBlank(
                    Json.text(principal, "orgUnitId", null),
                    claims == null ? null : claims.text("oid")));
            List<String> roles = stringList(Json.field(principal, "roles"));
            if (roles.isEmpty() && claims != null) {
                roles = claims.roles();
            }
            String authType = Json.text(principal, "authType", null);
            Boolean mustChangePassword = booleanValue(principal, "mustChangePassword");

            CliContext context = ctx();
            ConfigStore store = context.configStore();
            CliConfig config = store.load();
            config.baseUrl = context.baseUrl();
            config.token = token;
            config.username = principalUsername;
            config.tenantId = tenantId;
            config.orgUnitId = orgUnitId;
            config.roles = roles.isEmpty() ? null : roles;
            config.authType = authType;
            config.mustChangePassword = mustChangePassword;
            store.save(config);

            if (jsonOutput()) {
                out().println(Json.pretty(data));
            } else {
                out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                        List.of("用户名", "用户 ID", "Token", "Token 类型", "有效期(秒)", "租户代码",
                                "租户", "组织单元", "角色", "配置文件"),
                        List.of(
                                principalUsername,
                                orDash(userId),
                                truncate(token, 36),
                                text(data, "tokenType", "Bearer"),
                                num(data, "expiresIn"),
                                blankToNull(tenantCode) == null ? "-" : tenantCode,
                                orDash(tenantId),
                                orDash(orgUnitId),
                                roles.isEmpty() ? "-" : String.join(",", roles),
                                context.configPath().toString())));
                if (Boolean.TRUE.equals(mustChangePassword)) {
                    printInfo("提示：当前账号要求修改密码（mustChangePassword=true）");
                }
            }
            return ExitCodes.OK;
        }

        private String resolvePassword() {
            if (password != null) {
                return password;
            }
            if (passwordStdin) {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                    String line = reader.readLine();
                    return line == null ? null : line.trim();
                } catch (IOException e) {
                    throw new CliUsageException("读取标准输入密码失败: " + e.getMessage());
                }
            }
            Console console = System.console();
            if (console != null) {
                char[] chars = console.readPassword("密码: ");
                return chars == null ? null : new String(chars);
            }
            return null;
        }
    }

    @Command(name = "logout", mixinStandardHelpOptions = true, description = "清除已保存的 token")
    public static class Logout extends BaseCommand {

        @Option(names = "--purge", description = "删除整个配置文件（含 base-url 等设置）")
        boolean purge;

        @Override
        public Integer call() {
            CliContext context = ctx();
            ConfigStore store = context.configStore();
            boolean existed = java.nio.file.Files.exists(store.path());
            if (purge) {
                store.delete();
                printInfo("已删除配置文件: " + store.path());
            } else {
                CliConfig config = store.load();
                boolean hadToken = config.token != null && !config.token.isBlank();
                config.token = null;
                config.roles = null;
                config.orgUnitId = null;
                config.tenantId = null;
                if (existed || hadToken) {
                    store.save(config);
                    printInfo(hadToken ? "已登出，token 已从配置文件移除" : "配置文件已存在，但未找到 token");
                } else {
                    printInfo("未登录（配置文件不存在）: " + store.path());
                }
            }
            if (jsonOutput()) {
                out().println(Json.pretty(Json.object()
                        .put("loggedOut", true)
                        .put("purged", purge)
                        .put("configPath", store.path().toString())));
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "whoami", mixinStandardHelpOptions = true,
            description = "显示当前 token 的身份（离线解析 JWT payload）")
    public static class Whoami extends BaseCommand {

        @Override
        public Integer call() {
            String token = ctx().token();
            if (token == null || token.isBlank()) {
                throw new ApiException(ExitCodes.AUTH, ExitCodes.CODE_UNAUTHORIZED,
                        "未登录：请先执行 tenancy auth login，或通过 --token 传入 JWT");
            }
            JwtToken jwt;
            try {
                jwt = JwtToken.parse(token);
            } catch (CliUsageException e) {
                throw new ApiException(ExitCodes.AUTH, ExitCodes.CODE_UNAUTHORIZED,
                        "token 无效: " + e.getMessage(), e);
            }

            String userId = blankToNull(jwt.text("sub"));
            String username = firstNonBlank(jwt.text("name"), userId);
            String tenantId = blankToNull(jwt.text("tid"));
            String orgUnitId = blankToNull(jwt.text("oid"));
            List<String> roles = jwt.roles();
            String issuedAt = jwt.issuedAt();
            String expiresAt = jwt.expiresAt();
            boolean hasExpiry = jwt.epochSeconds("exp") != null;
            boolean expired = jwt.expired();

            if (jsonOutput()) {
                ObjectNode node = Json.object();
                node.put("username", username);
                node.put("userId", userId);
                node.put("tenantId", tenantId);
                node.put("orgUnitId", orgUnitId);
                ArrayNode roleArray = node.putArray("roles");
                roles.forEach(roleArray::add);
                node.put("issuedAt", issuedAt);
                node.put("expiresAt", expiresAt);
                if (hasExpiry) {
                    node.put("expired", expired);
                }
                out().println(Json.pretty(node));
                return ExitCodes.OK;
            }

            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("用户名", "用户 ID", "租户", "组织单元", "角色", "签发时间", "过期时间", "状态"),
                    List.of(
                            orDash(username),
                            orDash(userId),
                            orDash(tenantId),
                            orDash(orgUnitId),
                            roles.isEmpty() ? "-" : String.join(",", roles),
                            orDash(issuedAt),
                            orDash(expiresAt),
                            hasExpiry ? (expired ? "已过期" : "有效") : "未知")));
            return ExitCodes.OK;
        }
    }

    // ---------------------------------------------------------------- helpers

    /** Downstream/reachability failures keep exit code 5; everything else during login is 3. */
    private static boolean isDownstreamUnavailable(ApiException e) {
        int code = e.apiCode();
        return e.exitCode() == ExitCodes.NETWORK
                || code == 502 || code == 503 || code == 504
                || code == 50200 || code == 50300;
    }

    private static JwtToken tryParseToken(String token) {
        try {
            return JwtToken.parse(token);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static List<String> stringList(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(value -> {
                if (!value.isNull()) {
                    values.add(value.asText());
                }
            });
        }
        return values;
    }

    private static Boolean booleanValue(JsonNode node, String field) {
        JsonNode value = Json.field(node, field);
        return value.isBoolean() ? value.asBoolean() : null;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private static String orDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }
}
