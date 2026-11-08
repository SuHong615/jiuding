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
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code tenancy approval ...} - approval workflow commands. */
public final class ApprovalCommands {

    private static final List<String> LIST_HEADERS =
            headers("ID", "租户", "编码", "计划", "状态", "当前节点", "风险", "自动", "创建时间");
    private static final Set<String> DECISIONS = Set.of("APPROVE", "REJECT");

    private ApprovalCommands() {
    }

    @Command(name = "approval", description = "审批工作流：列表 / 待办 / 详情 / 决策",
            subcommands = {
                    ApprovalCommands.ListCommand.class,
                    ApprovalCommands.PendingCommand.class,
                    ApprovalCommands.GetCommand.class,
                    ApprovalCommands.DecideCommand.class
            })
    public static class Approval extends BaseCommand.Group {
    }

    @Command(name = "list", description = "分页查询审批单")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--state", paramLabel = "<state>", description = "按状态过滤，如 PENDING_APPROVAL")
        String state;

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数")
        int size = 20;

        @Override
        public Integer call() {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("state", state);
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));
            JsonNode data = api().get("/api/approvals", query);
            return emitItems(data, LIST_HEADERS, ApprovalCommands::approvalRow);
        }
    }

    @Command(name = "pending", description = "待办审批列表")
    public static class PendingCommand extends BaseCommand {

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/approvals/pending", null);
            return emitItems(data, LIST_HEADERS, ApprovalCommands::approvalRow);
        }
    }

    @Command(name = "get", description = "审批详情（含节点与时间线）")
    public static class GetCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<approvalId>", description = "审批单 ID")
        String approvalId;

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/approvals/" + encode(approvalId), null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("ID", "租户", "租户编码", "计划", "状态", "当前节点", "风险等级",
                            "风险分", "自动决策", "决策人", "决策意见", "SLA 截止", "创建时间", "更新时间"),
                    List.of(
                            text(data, "id"), text(data, "tenantId"), text(data, "tenantCode"),
                            text(data, "plan"), text(data, "state"), text(data, "currentNode"),
                            text(data, "riskLevel"), num(data, "riskScore"),
                            text(data, "autoDecided", "false"), text(data, "decidedBy"),
                            text(data, "decisionComment"), text(data, "slaDueAt"),
                            text(data, "createdAt"), text(data, "updatedAt"))));

            JsonNode nodes = Json.field(data, "nodes");
            if (nodes.isArray() && !nodes.isEmpty()) {
                out().println();
                out().println("节点流程:");
                List<List<String>> nodeRows = new ArrayList<>();
                nodes.forEach(node -> nodeRows.add(row(
                        text(node, "node"), text(node, "type"), text(node, "state"),
                        text(node, "assigneeRole"), text(node, "decidedBy"),
                        text(node, "decidedAt"), text(node, "comment"), num(node, "slaHours"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("节点", "类型", "状态", "责任角色", "决策人", "决策时间", "意见", "SLA(小时)"),
                        nodeRows));
            }

            JsonNode timeline = Json.field(data, "timeline");
            if (timeline.isArray() && !timeline.isEmpty()) {
                out().println();
                out().println("时间线:");
                List<List<String>> timelineRows = new ArrayList<>();
                timeline.forEach(item -> timelineRows.add(row(
                        text(item, "at"), text(item, "actor"), text(item, "action"),
                        text(item, "detail"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("时间", "操作者", "动作", "详情"), timelineRows));
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "decide", description = "审批决策：通过或驳回")
    public static class DecideCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<approvalId>", description = "审批单 ID")
        String approvalId;

        @Option(names = {"--decision", "-d"}, paramLabel = "APPROVE|REJECT", required = true,
                description = "决策：APPROVE 或 REJECT")
        String decision;

        @Option(names = {"--comment", "-m"}, paramLabel = "<text>", description = "决策意见（REJECT 必填）")
        String comment;

        @Override
        public Integer call() {
            String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
            if (!DECISIONS.contains(normalized)) {
                throw new CliUsageException("--decision 只支持 APPROVE 或 REJECT，当前为: " + decision);
            }
            if ("REJECT".equals(normalized) && (comment == null || comment.isBlank())) {
                throw new CliUsageException("驳回必须提供 --comment 意见");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("decision", normalized);
            if (comment != null) {
                body.put("comment", comment);
            }
            JsonNode data = api().post("/api/approvals/" + encode(approvalId) + "/decisions", body);
            return emitDetail(data,
                    List.of("ID", "租户", "状态", "当前节点", "决策人", "决策意见", "更新时间"),
                    List.of(text(data, "id"), text(data, "tenantId"), text(data, "state"),
                            text(data, "currentNode"), text(data, "decidedBy"),
                            text(data, "decisionComment"), text(data, "updatedAt")));
        }
    }

    private static List<String> approvalRow(JsonNode item) {
        return row(
                text(item, "id"), text(item, "tenantId"), text(item, "tenantCode"),
                text(item, "plan"), text(item, "state"), text(item, "currentNode"),
                text(item, "riskLevel"), text(item, "autoDecided"), text(item, "createdAt"));
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
