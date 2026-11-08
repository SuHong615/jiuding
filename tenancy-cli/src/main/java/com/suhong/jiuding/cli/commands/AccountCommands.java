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

import static com.suhong.jiuding.cli.BaseCommand.encPath;
import static com.suhong.jiuding.cli.BaseCommand.headers;
import static com.suhong.jiuding.cli.BaseCommand.num;
import static com.suhong.jiuding.cli.BaseCommand.row;
import static com.suhong.jiuding.cli.BaseCommand.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code tenancy account ...} - tenant account directory commands (docs/11 §6.3). */
public final class AccountCommands {

    private static final Set<String> ROLES = Set.of("TENANT_ADMIN", "ORG_ADMIN", "MEMBER", "AUDITOR");
    private static final List<String> LIST_HEADERS =
            headers("ID", "用户名", "姓名", "组织", "角色", "状态", "邮箱", "最近登录");

    private AccountCommands() {
    }

    @Command(name = "account", mixinStandardHelpOptions = true,
            description = "账号目录：列表 / 创建 / 启停 / 解锁 / 改密 / 登录历史 / 重置密码 / 删除",
            subcommands = {
                    AccountCommands.ListCommand.class,
                    AccountCommands.CreateCommand.class,
                    AccountCommands.DisableCommand.class,
                    AccountCommands.EnableCommand.class,
                    AccountCommands.UnlockCommand.class,
                    AccountCommands.ChangePasswordCommand.class,
                    AccountCommands.LoginEventsCommand.class,
                    AccountCommands.ResetPasswordCommand.class,
                    AccountCommands.DeleteCommand.class
            })
    public static class Account extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "分页查询租户内账号")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--org", paramLabel = "<orgUnitId>", description = "按组织节点过滤")
        String orgUnitId;

        @Option(names = "--role", paramLabel = "<role>", description = "TENANT_ADMIN|ORG_ADMIN|MEMBER|AUDITOR")
        String role;

        @Option(names = "--status", paramLabel = "<status>", description = "INVITED|ACTIVE|DISABLED|LOCKED|DELETED")
        String status;

        @Option(names = "--keyword", paramLabel = "<text>", description = "用户名/姓名关键字")
        String keyword;

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数（默认 20）")
        int size = 20;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("orgUnitId", orgUnitId);
            query.put("role", role);
            query.put("status", status);
            query.put("keyword", keyword);
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));
            JsonNode data = apiForTenant(tenantId).get(accountsPath(tenantId), query);
            return emitItems(data, LIST_HEADERS, AccountCommands::listRow);
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "创建租户账号（返回一次性临时密码）")
    public static class CreateCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = {"--username", "-u"}, paramLabel = "<username>", required = true, description = "账号名")
        String username;

        @Option(names = {"--display-name", "-n"}, paramLabel = "<name>", required = true, description = "显示名")
        String displayName;

        @Option(names = "--email", paramLabel = "<email>", description = "邮箱")
        String email;

        @Option(names = "--phone", paramLabel = "<phone>", description = "手机号")
        String phone;

        @Option(names = "--org", paramLabel = "<orgUnitId>", description = "所属组织节点")
        String orgUnitId;

        @Option(names = "--role", paramLabel = "<role>", required = true, description = "角色（默认 MEMBER）")
        String role = "MEMBER";

        @Option(names = "--password", paramLabel = "<pwd>", description = "初始密码（缺省由服务端生成临时密码）")
        String initialPassword;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            String normalizedRole = role == null ? "MEMBER" : role.trim().toUpperCase(Locale.ROOT);
            if (!ROLES.contains(normalizedRole)) {
                throw new com.suhong.jiuding.cli.CliUsageException("--role 只支持 " + ROLES + "，当前为: " + role);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("username", username);
            body.put("displayName", displayName);
            body.put("role", normalizedRole);
            if (email != null && !email.isBlank()) {
                body.put("email", email);
            }
            if (phone != null && !phone.isBlank()) {
                body.put("phone", phone);
            }
            if (orgUnitId != null && !orgUnitId.isBlank()) {
                body.put("orgUnitId", orgUnitId);
            }
            if (initialPassword != null && !initialPassword.isBlank()) {
                body.put("initialPassword", initialPassword);
            }
            JsonNode data = apiForTenant(tenantId).post(accountsPath(tenantId), body);
            JsonNode account = data != null && data.isObject() && data.has("account")
                    ? data.get("account") : data;
            return emitDetail(data,
                    List.of("账号 ID", "用户名", "姓名", "角色", "状态", "组织", "临时密码"),
                    List.of(text(account, "id"), text(account, "username"), text(account, "displayName"),
                            text(account, "role"), text(account, "status"), text(account, "orgUnitId"),
                            text(data, "tempPassword")));
        }
    }

    /** Shared status-change plumbing for disable/enable. */
    private abstract static class AccountStatusCommand extends BaseCommand {

        protected int setStatus(String tenantId, String accountId, String status, String reason) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("status", status);
            if (reason != null && !reason.isBlank()) {
                body.put("reason", reason);
            }
            JsonNode data = apiForTenant(tenantId)
                    .post(accountsPath(tenantId) + "/" + encPath(accountId) + "/status", body);
            JsonNode account = data != null && data.isObject() && data.has("account")
                    ? data.get("account") : data;
            if (jsonOutput()) {
                out().println(Json.pretty(account));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("账号 ID", "用户名", "姓名", "角色", "状态", "更新时间"),
                    List.of(text(account, "id"), text(account, "username"), text(account, "displayName"),
                            text(account, "role"), text(account, "status"), text(account, "updatedAt"))));
            return ExitCodes.OK;
        }
    }

    @Command(name = "disable", mixinStandardHelpOptions = true, description = "禁用账号")
    public static class DisableCommand extends AccountStatusCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--reason", paramLabel = "<text>", description = "禁用原因", defaultValue = "cli-disable")
        String reason;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            return setStatus(tenantId, accountId, "DISABLED", reason);
        }
    }

    @Command(name = "enable", mixinStandardHelpOptions = true, description = "启用账号")
    public static class EnableCommand extends AccountStatusCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--reason", paramLabel = "<text>", description = "启用原因", defaultValue = "cli-enable")
        String reason;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            return setStatus(tenantId, accountId, "ACTIVE", reason);
        }
    }

    @Command(name = "reset-password", mixinStandardHelpOptions = true, description = "重置账号密码（返回一次性临时密码）")
    public static class ResetPasswordCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--password", paramLabel = "<pwd>", description = "指定新密码（缺省由服务端生成临时密码）")
        String newPassword;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, Object> body = new LinkedHashMap<>();
            if (newPassword != null && !newPassword.isBlank()) {
                body.put("newPassword", newPassword);
            }
            JsonNode data = apiForTenant(tenantId)
                    .post(accountsPath(tenantId) + "/" + encPath(accountId) + "/reset-password", body);
            return emitDetail(data,
                    List.of("账号 ID", "临时密码", "提示"),
                    List.of(accountId, text(data, "tempPassword"), "临时密码仅返回一次，请尽快转交用户"));
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true, description = "删除账号（软删）")
    public static class DeleteCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--yes", description = "确认删除")
        boolean yes;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            JsonNode data = apiForTenant(tenantId)
                    .delete(accountsPath(tenantId) + "/" + encPath(accountId), null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
            } else {
                out().println("已删除账号: " + accountId);
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "unlock", mixinStandardHelpOptions = true, description = "解锁被锁定账号（LOCKED → ACTIVE）")
    public static class UnlockCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            JsonNode data = apiForTenant(tenantId)
                    .post(accountsPath(tenantId) + "/" + encPath(accountId) + "/unlock", new LinkedHashMap<>());
            JsonNode account = data != null && data.isObject() && data.has("account")
                    ? data.get("account") : data;
            return emitDetail(data,
                    List.of("账号 ID", "用户名", "姓名", "角色", "状态", "更新时间"),
                    List.of(text(account, "id"), text(account, "username"), text(account, "displayName"),
                            text(account, "role"), text(account, "status"), text(account, "updatedAt")));
        }
    }

    @Command(name = "change-password", mixinStandardHelpOptions = true,
            description = "修改账号密码（仅本人；校验旧密码与密码策略）")
    public static class ChangePasswordCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--old", paramLabel = "<pwd>", required = true, description = "旧密码")
        String oldPassword;

        @Option(names = "--new", paramLabel = "<pwd>", required = true, description = "新密码（≥10 位，含字母+数字）")
        String newPassword;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("oldPassword", oldPassword);
            body.put("newPassword", newPassword);
            JsonNode data = apiForTenant(tenantId)
                    .post(accountsPath(tenantId) + "/" + encPath(accountId) + "/change-password", body);
            JsonNode account = data != null && data.isObject() && data.has("account")
                    ? data.get("account") : data;
            return emitDetail(data,
                    List.of("账号 ID", "用户名", "状态", "是否需改密", "更新时间"),
                    List.of(text(account, "id"), text(account, "username"), text(account, "status"),
                            text(account, "mustChangePassword"), text(account, "updatedAt")));
        }
    }

    @Command(name = "login-events", mixinStandardHelpOptions = true, description = "账号登录历史（分页）")
    public static class LoginEventsCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<accountId>", description = "账号 ID")
        String accountId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数（默认 20）")
        int size = 20;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));
            JsonNode data = apiForTenant(tenantId).get(
                    accountsPath(tenantId) + "/" + encPath(accountId) + "/login-events", query);
            return emitItems(data,
                    List.of("时间", "IP", "User-Agent", "结果", "原因"),
                    node -> row(text(node, "at"), text(node, "ip"), text(node, "userAgent"),
                            text(node, "success"), text(node, "reason")));
        }
    }

    private static List<String> listRow(JsonNode node) {
        return row(
                text(node, "id"),
                text(node, "username"),
                text(node, "displayName"),
                text(node, "orgUnitId"),
                text(node, "role"),
                text(node, "status"),
                text(node, "email"),
                text(node, "lastLoginAt"));
    }

    private static String accountsPath(String tenantId) {
        return "/api/tenants/" + encPath(tenantId) + "/accounts";
    }
}
