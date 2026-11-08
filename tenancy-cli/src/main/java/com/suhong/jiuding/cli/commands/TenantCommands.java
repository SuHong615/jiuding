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

import static com.suhong.jiuding.cli.BaseCommand.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import com.suhong.jiuding.cli.TableRenderer;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code tenancy tenant ...} - tenant lifecycle commands. */
public final class TenantCommands {

    private static final List<String> LIST_HEADERS =
            headers("ID", "编码", "名称", "计划", "状态", "风险", "区域", "创建时间");

    private TenantCommands() {
    }

    @Command(name = "tenant", mixinStandardHelpOptions = true,
            description = "租户管理：查询 / 创建 / 提交 / 停用 / 启用 / 终止 / 删除 / 导入组织 / 导入账号",
            subcommands = {
                    TenantCommands.ListCommand.class,
                    TenantCommands.GetCommand.class,
                    TenantCommands.CreateCommand.class,
                    TenantCommands.SubmitCommand.class,
                    TenantCommands.SuspendCommand.class,
                    TenantCommands.ActivateCommand.class,
                    TenantCommands.TerminateCommand.class,
                    TenantCommands.DeleteCommand.class,
                    TenantCommands.ImportOrgsCommand.class,
                    TenantCommands.ImportAccountsCommand.class
            })
    public static class Tenant extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "分页查询租户")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--status", paramLabel = "<status>", description = "按状态过滤，如 ACTIVE")
        String status;

        @Option(names = "--plan", paramLabel = "<plan>", description = "按计划过滤 FREE|PRO|ENTERPRISE")
        String plan;

        @Option(names = {"--keyword", "-k"}, paramLabel = "<text>", description = "名称/编码关键字")
        String keyword;

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数")
        int size = 20;

        @Override
        public Integer call() {
            if (page < 0 || size <= 0 || size > 200) {
                throw new CliUsageException("--page 不能为负，--size 需在 1..200 之间");
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("status", status);
            query.put("plan", plan);
            query.put("keyword", keyword);
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));

            JsonNode data = api().get("/api/tenants", query);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            Json.items(data).forEach(tenant -> rows.add(row(
                    text(tenant, "id"),
                    text(tenant, "tenantCode"),
                    text(tenant, "name"),
                    text(tenant, "plan"),
                    text(tenant, "status"),
                    text(tenant, "riskLevel"),
                    text(tenant, "region"),
                    text(tenant, "createdAt"))));
            out().println(TableRenderer.render(LIST_HEADERS, rows));
            long total = Json.intValue(data, "total", rows.size());
            out().println("共 " + total + " 条，页码 " + page + "，每页 " + size
                    + "，当前 " + rows.size() + " 条");
            return ExitCodes.OK;
        }
    }

    @Command(name = "get", mixinStandardHelpOptions = true, description = "查看租户详情")
    public static class GetCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/tenants/" + encode(tenantId), null);
            return emitDetail(data,
                    List.of("ID", "租户编码", "名称", "联系人", "邮箱", "电话", "计划", "区域",
                            "隔离模式", "状态", "风险等级", "风险分", "席位配额", "存储配额(GB)",
                            "API 配额(RPM)", "来源", "驳回原因", "创建时间", "更新时间",
                            "提交时间", "决策时间", "激活时间", "版本"),
                    List.of(
                            text(data, "id"),
                            text(data, "tenantCode"),
                            text(data, "name"),
                            text(data, "contactName"),
                            text(data, "contactEmail"),
                            text(data, "contactPhone"),
                            text(data, "plan"),
                            text(data, "region"),
                            text(data, "isolationMode"),
                            text(data, "status"),
                            text(data, "riskLevel"),
                            num(data, "riskScore"),
                            num(data, "quotaSeats"),
                            num(data, "quotaStorageGb"),
                            num(data, "quotaApiRpm"),
                            text(data, "source"),
                            text(data, "rejectReason"),
                            text(data, "createdAt"),
                            text(data, "updatedAt"),
                            text(data, "submittedAt"),
                            text(data, "decidedAt"),
                            text(data, "activatedAt"),
                            num(data, "version")));
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "创建租户")
    public static class CreateCommand extends BaseCommand {

        @Option(names = {"--code", "-c"}, paramLabel = "<tenantCode>", description = "租户编码（唯一）")
        String code;

        @Option(names = {"--name", "-n"}, paramLabel = "<name>", description = "租户名称")
        String name;

        @Option(names = "--contact-name", paramLabel = "<name>", description = "联系人")
        String contactName;

        @Option(names = {"--email", "-e"}, paramLabel = "<email>", description = "联系邮箱")
        String contactEmail;

        @Option(names = "--phone", paramLabel = "<phone>", description = "联系电话")
        String contactPhone;

        @Option(names = "--plan", paramLabel = "<plan>", description = "FREE|PRO|ENTERPRISE（默认 PRO）")
        String plan = "PRO";

        @Option(names = "--region", paramLabel = "<region>", description = "区域（默认 cn-shanghai）")
        String region = "cn-shanghai";

        @Option(names = "--isolation", paramLabel = "<mode>",
                description = "SHARED_SCHEMA|DEDICATED_SCHEMA|DEDICATED_DB")
        String isolationMode = "SHARED_SCHEMA";

        @Option(names = "--seats", paramLabel = "<n>", description = "席位配额")
        Integer quotaSeats;

        @Option(names = "--storage-gb", paramLabel = "<n>", description = "存储配额 GB")
        Integer quotaStorageGb;

        @Option(names = "--api-rpm", paramLabel = "<n>", description = "API 每分钟配额")
        Integer quotaApiRpm;

        @Option(names = "--source", paramLabel = "<source>", description = "MANUAL|IMPORT|API|DISCOVERY")
        String source = "MANUAL";

        @Option(names = "--attributes", paramLabel = "<json>", description = "attributes 对象（JSON）")
        String attributes;

        @Option(names = "--auto-submit", description = "创建后自动提交审批")
        boolean autoSubmit;

        @Option(names = "--file", paramLabel = "<path>", description = "从 JSON 文件读取完整创建请求")
        Path file;

        @Override
        public Integer call() {
            JsonNode payload;
            if (file != null) {
                if (!Files.isRegularFile(file)) {
                    throw new CliUsageException("文件不存在: " + file);
                }
                try {
                    payload = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new CliUsageException("读取文件失败: " + file + " (" + e.getMessage() + ")");
                }
                if (!payload.isObject()) {
                    throw new CliUsageException("--file 内容必须是 JSON 对象");
                }
            } else {
                if (code == null || code.isBlank()) {
                    throw new CliUsageException("缺少 --code（租户编码）");
                }
                if (name == null || name.isBlank()) {
                    throw new CliUsageException("缺少 --name（租户名称）");
                }
                Map<String, Object> body = body(
                        "tenantCode", code,
                        "name", name,
                        "contactName", contactName,
                        "contactEmail", contactEmail,
                        "contactPhone", contactPhone,
                        "plan", plan,
                        "region", region,
                        "isolationMode", isolationMode,
                        "quotaSeats", quotaSeats,
                        "quotaStorageGb", quotaStorageGb,
                        "quotaApiRpm", quotaApiRpm,
                        "source", source,
                        "autoSubmit", autoSubmit ? Boolean.TRUE : null);
                if (attributes != null && !attributes.isBlank()) {
                    body.put("attributes", Json.parse(attributes));
                }
                payload = Json.mapper().valueToTree(body);
            }
            JsonNode data = api().post("/api/tenants", payload);
            return emitDetail(data,
                    List.of("ID", "租户编码", "名称", "计划", "区域", "隔离模式", "状态", "来源", "创建时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "name"),
                            text(data, "plan"), text(data, "region"), text(data, "isolationMode"),
                            text(data, "status"), text(data, "source"), text(data, "createdAt")));
        }
    }

    @Command(name = "submit", mixinStandardHelpOptions = true, description = "提交租户审批")
    public static class SubmitCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/tenants/" + encode(tenantId) + "/submit", null);
            return emitDetail(data,
                    List.of("ID", "租户编码", "状态", "提交时间", "更新时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "status"),
                            text(data, "submittedAt"), text(data, "updatedAt")));
        }
    }

    @Command(name = "suspend", mixinStandardHelpOptions = true, description = "停用 ACTIVE 租户")
    public static class SuspendCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Option(names = "--reason", paramLabel = "<text>", description = "停用原因", defaultValue = "cli-suspend")
        String reason;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/tenants/" + encode(tenantId) + "/suspend",
                    Map.of("reason", reason));
            return emitDetail(data,
                    List.of("ID", "租户编码", "状态", "更新时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "status"),
                            text(data, "updatedAt")));
        }
    }

    @Command(name = "activate", mixinStandardHelpOptions = true, description = "启用 SUSPENDED 租户")
    public static class ActivateCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/tenants/" + encode(tenantId) + "/activate", null);
            return emitDetail(data,
                    List.of("ID", "租户编码", "状态", "更新时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "status"),
                            text(data, "updatedAt")));
        }
    }

    @Command(name = "terminate", mixinStandardHelpOptions = true, description = "终止租户")
    public static class TerminateCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Option(names = "--reason", paramLabel = "<text>", description = "终止原因", defaultValue = "cli-terminate")
        String reason;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/tenants/" + encode(tenantId) + "/terminate",
                    Map.of("reason", reason));
            return emitDetail(data,
                    List.of("ID", "租户编码", "状态", "更新时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "status"),
                            text(data, "updatedAt")));
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true, description = "软删除租户（仅平台管理员，需 --yes 确认）")
    public static class DeleteCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Option(names = "--yes", description = "确认删除（不可恢复）")
        boolean yes;

        @Override
        public Integer call() {
            if (!yes) {
                throw new CliUsageException("删除租户不可恢复，请加 --yes 确认");
            }
            JsonNode data = api().delete("/api/tenants/" + encode(tenantId), null);
            return emitDetail(data,
                    List.of("ID", "租户编码", "状态", "删除时间"),
                    List.of(text(data, "id"), text(data, "tenantCode"), text(data, "status"),
                            text(data, "deletedAt")));
        }
    }

    /** Shared rendering for CSV import results (docs/15 §5). */
    private abstract static class ImportCommandBase extends BaseCommand {

        protected int renderImport(JsonNode data, boolean includePasswords) {
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("总数", "导入", "跳过", "失败"),
                    List.of(num(data, "total"), num(data, "imported"),
                            num(data, "skipped"), num(data, "failed"))));
            JsonNode errors = Json.field(data, "errors");
            if (errors.isArray() && !errors.isEmpty()) {
                out().println();
                out().println("失败行:");
                List<List<String>> rows = new ArrayList<>();
                errors.forEach(error -> rows.add(row(num(error, "row"), text(error, "message"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(headers("行号", "原因"), rows));
            }
            JsonNode passwords = Json.field(data, "tempPasswords");
            if (includePasswords && passwords.isArray() && !passwords.isEmpty()) {
                out().println();
                out().println("一次性临时密码（仅本次显示）:");
                List<List<String>> rows = new ArrayList<>();
                passwords.forEach(entry -> rows.add(row(num(entry, "row"), text(entry, "username"),
                        text(entry, "tempPassword"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("行号", "用户名", "临时密码"), rows));
            }
            return ExitCodes.OK;
        }

        protected Path requireCsv(Path file) {
            if (file == null || !Files.isRegularFile(file)) {
                throw new CliUsageException("文件不存在: " + file);
            }
            if (!file.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".csv")) {
                throw new CliUsageException("请提供 .csv 导入文件: " + file);
            }
            return file;
        }
    }

    @Command(name = "import-orgs", mixinStandardHelpOptions = true,
            description = "从 CSV 批量导入组织（列: parentCode,code,name,type,sortOrder,managerUsername）")
    public static class ImportOrgsCommand extends ImportCommandBase {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = {"--file", "-f"}, paramLabel = "<orgs.csv>", required = true, description = "CSV 文件")
        Path file;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Path csv = requireCsv(file);
            JsonNode data = apiForTenant(tenantId).postMultipart(
                    "/api/tenants/" + encPath(tenantId) + "/org-units/import", csv, "file", Map.of());
            return renderImport(data, false);
        }
    }

    @Command(name = "import-accounts", mixinStandardHelpOptions = true,
            description = "从 CSV 批量导入账号（列: username,displayName,email,phone,orgCode,role）")
    public static class ImportAccountsCommand extends ImportCommandBase {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = {"--file", "-f"}, paramLabel = "<accounts.csv>", required = true, description = "CSV 文件")
        Path file;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Path csv = requireCsv(file);
            JsonNode data = apiForTenant(tenantId).postMultipart(
                    "/api/tenants/" + encPath(tenantId) + "/accounts/import", csv, "file", Map.of());
            return renderImport(data, true);
        }
    }

    private static String encode(String segment) {
        return java.net.URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
