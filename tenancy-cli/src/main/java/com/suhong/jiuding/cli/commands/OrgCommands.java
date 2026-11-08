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
import static com.suhong.jiuding.cli.BaseCommand.row;
import static com.suhong.jiuding.cli.BaseCommand.text;
import static com.suhong.jiuding.cli.BaseCommand.num;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code tenancy org ...} - organization tree commands (docs/11 §6.2). */
public final class OrgCommands {

    private static final Set<String> ORG_TYPES = Set.of("COMPANY", "BRANCH", "DEPARTMENT", "TEAM");
    private static final List<String> LIST_HEADERS =
            headers("ID", "编码", "名称", "类型", "父节点", "深度", "成员数", "子节点", "状态");

    private OrgCommands() {
    }

    @Command(name = "org", mixinStandardHelpOptions = true, description = "组织架构：列表 / 树 / 创建 / 移动 / 删除",
            subcommands = {
                    OrgCommands.ListCommand.class,
                    OrgCommands.TreeCommand.class,
                    OrgCommands.CreateCommand.class,
                    OrgCommands.MoveCommand.class,
                    OrgCommands.DeleteCommand.class
            })
    public static class Org extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "组织节点平铺列表")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--status", paramLabel = "<status>", description = "ACTIVE|DISABLED")
        String status;

        @Option(names = "--keyword", paramLabel = "<text>", description = "编码/名称关键字")
        String keyword;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("format", "flat");
            query.put("status", status);
            query.put("keyword", keyword);
            JsonNode data = apiForTenant(tenantId).get(orgPath(tenantId), query);
            return emitItems(data, LIST_HEADERS, OrgCommands::listRow);
        }
    }

    @Command(name = "tree", mixinStandardHelpOptions = true, description = "组织树（层级展示）")
    public static class TreeCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--format", paramLabel = "tree|flat", description = "tree 层级或 flat 平铺（默认 tree）")
        String format = "tree";

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            String normalized = format == null ? "tree" : format.toLowerCase(Locale.ROOT);
            if (!"tree".equals(normalized) && !"flat".equals(normalized)) {
                throw new CliUsageException("--format 只支持 tree 或 flat，当前为: " + format);
            }
            Map<String, String> query = new LinkedHashMap<>();
            query.put("format", normalized);
            JsonNode data = apiForTenant(tenantId).get(orgPath(tenantId), query);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            Json.items(data).forEach(node -> rows.add(row(
                    text(node, "id"),
                    text(node, "code"),
                    indentName(node),
                    text(node, "type"),
                    num(node, "depth"),
                    text(node, "status"))));
            out().println(com.suhong.jiuding.cli.TableRenderer.render(
                    headers("ID", "编码", "名称", "类型", "深度", "状态"), rows));
            out().println("共 " + rows.size() + " 个节点（format=" + normalized + "）");
            return ExitCodes.OK;
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true, description = "创建组织节点")
    public static class CreateCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--parent", paramLabel = "<orgUnitId>", description = "父节点 ID（缺省为根节点）")
        String parent;

        @Option(names = {"--code", "-c"}, paramLabel = "<code>", required = true, description = "组织编码（租户内唯一）")
        String code;

        @Option(names = {"--name", "-n"}, paramLabel = "<name>", required = true, description = "组织名称")
        String name;

        @Option(names = {"--type", "-t"}, paramLabel = "<type>", required = true,
                description = "COMPANY|BRANCH|DEPARTMENT|TEAM")
        String type;

        @Option(names = "--sort-order", paramLabel = "<n>", description = "排序值")
        Integer sortOrder;

        @Option(names = "--manager", paramLabel = "<accountId>", description = "负责人账号 ID")
        String managerAccountId;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            String normalizedType = type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
            if (!ORG_TYPES.contains(normalizedType)) {
                throw new CliUsageException("--type 只支持 " + ORG_TYPES + "，当前为: " + type);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("code", code);
            body.put("name", name);
            body.put("type", normalizedType);
            if (parent != null && !parent.isBlank()) {
                body.put("parentId", parent);
            }
            if (sortOrder != null) {
                body.put("sortOrder", sortOrder);
            }
            if (managerAccountId != null && !managerAccountId.isBlank()) {
                body.put("managerAccountId", managerAccountId);
            }
            JsonNode data = apiForTenant(tenantId).post(orgPath(tenantId), body);
            return emitDetail(data,
                    List.of("ID", "编码", "名称", "类型", "父节点", "路径", "深度", "状态", "创建时间"),
                    List.of(text(data, "id"), text(data, "code"), text(data, "name"),
                            text(data, "type"), text(data, "parentId"), text(data, "path"),
                            num(data, "depth"), text(data, "status"), text(data, "createdAt")));
        }
    }

    @Command(name = "move", mixinStandardHelpOptions = true, description = "移动组织节点到新父节点")
    public static class MoveCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<orgUnitId>", description = "组织节点 ID")
        String orgUnitId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--parent", paramLabel = "<orgUnitId>", description = "新父节点 ID")
        String parent;

        @Option(names = "--root", description = "移动到根节点（parentId=null）")
        boolean root;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            if (!root && (parent == null || parent.isBlank())) {
                throw new CliUsageException("请提供 --parent <orgUnitId>，或使用 --root 移动到根节点");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("parentId", root ? null : parent);
            JsonNode data = apiForTenant(tenantId)
                    .post(orgPath(tenantId) + "/" + encPath(orgUnitId) + "/move", body);
            return emitDetail(data,
                    List.of("ID", "编码", "名称", "父节点", "路径", "深度", "更新时间"),
                    List.of(text(data, "id"), text(data, "code"), text(data, "name"),
                            text(data, "parentId"), text(data, "path"), num(data, "depth"),
                            text(data, "updatedAt")));
        }
    }

    @Command(name = "delete", mixinStandardHelpOptions = true, description = "删除组织节点（需无子节点且无成员）")
    public static class DeleteCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<orgUnitId>", description = "组织节点 ID")
        String orgUnitId;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--yes", description = "确认删除")
        boolean yes;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            JsonNode data = apiForTenant(tenantId)
                    .delete(orgPath(tenantId) + "/" + encPath(orgUnitId), null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
            } else {
                out().println("已删除组织节点: " + orgUnitId);
            }
            return ExitCodes.OK;
        }
    }

    private static String orgPath(String tenantId) {
        return "/api/tenants/" + encPath(tenantId) + "/org-units";
    }

    private static List<String> listRow(JsonNode node) {
        return row(
                text(node, "id"),
                text(node, "code"),
                text(node, "name"),
                text(node, "type"),
                text(node, "parentId"),
                num(node, "depth"),
                num(node, "accountCount"),
                num(node, "childCount"),
                text(node, "status"));
    }

    private static String indentName(JsonNode node) {
        int depth = 0;
        JsonNode depthNode = node == null ? null : node.get("depth");
        if (depthNode != null && depthNode.isNumber()) {
            depth = Math.max(0, Math.min(6, depthNode.asInt()));
        }
        return "  ".repeat(depth) + text(node, "name", "");
    }
}
