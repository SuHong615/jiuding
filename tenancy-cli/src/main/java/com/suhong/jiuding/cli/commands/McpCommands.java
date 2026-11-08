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
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.cli.ApiException;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code tenancy mcp tools list|call} - MCP console commands (JSON-RPC 2.0 over POST /mcp). */
public final class McpCommands {

    private static final List<String> TOOL_HEADERS = headers("工具", "说明");

    private McpCommands() {
    }

    @Command(name = "mcp", mixinStandardHelpOptions = true,
            description = "MCP 控制台：配置 / 工具目录 / 调用 / API Key / 调用审计",
            subcommands = {
                    McpCommands.ConfigCommand.class,
                    McpCommands.Tools.class,
                    McpCommands.Keys.class,
                    McpCommands.AuditsCommand.class
            })
    public static class Mcp extends BaseCommand.Group {
    }

    @Command(name = "tools", mixinStandardHelpOptions = true, description = "MCP 工具：list / call",
            subcommands = {McpCommands.ToolsList.class, McpCommands.ToolsCall.class})
    public static class Tools extends BaseCommand.Group {
    }

    /** {@code tenancy mcp config} - endpoint + copy-paste client config (docs/15 §4.2). */
    @Command(name = "config", mixinStandardHelpOptions = true, description = "获取 MCP 客户端配置")
    public static class ConfigCommand extends BaseCommand {

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/mcp/config", null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("端点", "传输", "协议版本", "Key 提示", "文档", "示例配置(JSON)"),
                    List.of(text(data, "endpoint"), text(data, "transport"), text(data, "protocolVersion"),
                            text(data, "keyHint"), text(data, "docs"),
                            truncate(Json.field(data, "sampleConfig").toString(), 60))));
            JsonNode sample = Json.field(data, "sampleConfig");
            if (!sample.isNull() && !sample.isMissingNode()) {
                out().println();
                out().println("sampleConfig:");
                out().println(sample.isTextual() ? sample.asText() : Json.pretty(sample));
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "keys", mixinStandardHelpOptions = true, description = "MCP API Key：list / create / revoke",
            subcommands = {McpCommands.KeysList.class, McpCommands.KeysCreate.class, McpCommands.KeysRevoke.class})
    public static class Keys extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "列出我的 MCP API Key")
    public static class KeysList extends BaseCommand {

        @Option(names = "--all", description = "平台管理员：列出全部用户的 key")
        boolean all;

        @Override
        public Integer call() {
            Map<String, String> query = new LinkedHashMap<>();
            if (all) {
                query.put("all", "true");
            }
            JsonNode data = api().get("/api/mcp/keys", query);
            return emitItems(data,
                    List.of("ID", "名称", "前缀", "状态", "创建时间", "过期时间", "最近使用"),
                    item -> row(text(item, "id"), text(item, "name"), text(item, "keyPrefix"),
                            text(item, "status"), text(item, "createdAt"),
                            text(item, "expiresAt"), text(item, "lastUsedAt")));
        }
    }

    @Command(name = "create", mixinStandardHelpOptions = true,
            description = "创建 MCP API Key（明文仅本次返回）")
    public static class KeysCreate extends BaseCommand {

        @Option(names = {"--name", "-n"}, paramLabel = "<name>", required = true, description = "Key 名称")
        String name;

        @Option(names = "--expires-days", paramLabel = "<days>", description = "有效天数（缺省永不过期）")
        Integer expiresInDays;

        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("name", name);
            if (expiresInDays != null) {
                body.put("expiresInDays", expiresInDays);
            }
            JsonNode data = api().post("/api/mcp/keys", body);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("ID", "名称", "前缀", "API Key（仅本次显示）", "过期时间", "提示"),
                    List.of(text(data, "id"), text(data, "name"), text(data, "keyPrefix"),
                            text(data, "apiKey"), text(data, "expiresAt"),
                            "请立即保存，服务端仅存 hash")));
            return ExitCodes.OK;
        }
    }

    @Command(name = "revoke", mixinStandardHelpOptions = true, description = "撤销 MCP API Key")
    public static class KeysRevoke extends BaseCommand {

        @picocli.CommandLine.Parameters(index = "0", paramLabel = "<keyId>", description = "Key ID")
        String keyId;

        @Override
        public Integer call() {
            JsonNode data = api().delete("/api/mcp/keys/" + encPath(keyId), null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
            } else {
                out().println("已撤销 MCP Key: " + keyId);
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "audits", mixinStandardHelpOptions = true, description = "MCP 调用审计（分页）")
    public static class AuditsCommand extends BaseCommand {

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数（默认 20）")
        int size = 20;

        @Option(names = "--tool", paramLabel = "<tool>", description = "按工具名过滤")
        String tool;

        @Option(names = "--success", paramLabel = "true|false", description = "按调用结果过滤")
        String success;

        @Override
        public Integer call() {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));
            query.put("tool", tool);
            query.put("success", success);
            JsonNode data = api().get("/api/mcp/audits", query);
            return emitItems(data,
                    List.of("时间", "工具", "结果", "错误码", "耗时(ms)", "Key 前缀", "请求 ID"),
                    item -> row(text(item, "at"), text(item, "tool"), text(item, "success"),
                            num(item, "errorCode"), num(item, "durationMs"),
                            text(item, "keyPrefix"), text(item, "requestId")));
        }
    }

    /** Shared JSON-RPC plumbing for the MCP sub-commands. */
    private abstract static class McpBaseCommand extends BaseCommand {

        protected JsonNode rpc(String method, JsonNode params) {
            ObjectNode request = Json.object();
            request.put("jsonrpc", "2.0");
            request.put("id", 1);
            request.put("method", method);
            request.set("params", params);
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("X-API-Key", ctx().apiKey());
            JsonNode response = api().postRpc("/mcp", Json.compact(request), headers);
            if (response != null && response.hasNonNull("error")) {
                JsonNode error = response.get("error");
                int code = Json.intValue(error, "code", -32603);
                String message = Json.text(error, "message", "MCP 调用失败");
                throw new ApiException(ExitCodes.BUSINESS, code, "JSON-RPC 错误 " + code + ": " + message);
            }
            return response;
        }

        /** Returns {@code result}, failing the command when the server omitted it. */
        protected JsonNode expectResult(JsonNode response) {
            if (response == null || !response.has("result")) {
                throw new ApiException(ExitCodes.BUSINESS, -1, "MCP 响应缺少 result 字段");
            }
            return response.get("result");
        }

        /** tools/call returns content:[{type:"text",text:"<json>"}]; parse it when possible. */
        protected JsonNode extractContent(JsonNode result) {
            JsonNode content = Json.field(result, "content");
            if (!content.isArray() || content.isEmpty()) {
                return Json.object();
            }
            JsonNode first = content.get(0);
            String text = Json.text(first, "text", null);
            if (text == null) {
                return first;
            }
            try {
                return Json.parse(text);
            } catch (CliUsageException e) {
                return Json.mapper().getNodeFactory().textNode(text);
            }
        }
    }

    @Command(name = "list", description = "列出 MCP 工具")
    public static class ToolsList extends McpBaseCommand {

        @Override
        public Integer call() {
            JsonNode response = rpc("tools/list", Json.object());
            JsonNode result = expectResult(response);
            JsonNode tools = Json.field(result, "tools");
            JsonNode data = tools.isArray() ? tools : Json.object();
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            tools.forEach(tool -> rows.add(row(text(tool, "name"), truncate(text(tool, "description"), 70))));
            out().println(com.suhong.jiuding.cli.TableRenderer.render(TOOL_HEADERS, rows));
            out().println("共 " + tools.size() + " 个工具");
            return ExitCodes.OK;
        }
    }

    @Command(name = "call", description = "调用 MCP 工具")
    public static class ToolsCall extends McpBaseCommand {

        @Option(names = {"--name", "-n"}, paramLabel = "<toolName>", required = true,
                description = "工具名，如 tenant.list")
        String name;

        @Option(names = {"--args", "-a"}, paramLabel = "<json>", description = "工具参数 JSON（默认 {}）")
        String args;

        @Override
        public Integer call() {
            JsonNode arguments = args == null || args.isBlank() ? Json.object() : Json.parse(args);
            if (!arguments.isObject()) {
                throw new CliUsageException("--args 必须是 JSON 对象");
            }
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("name", name);
            params.put("arguments", arguments);
            JsonNode response = rpc("tools/call", Json.mapper().valueToTree(params));
            JsonNode result = expectResult(response);

            boolean isError = result.path("isError").asBoolean(false);
            JsonNode structured = Json.field(result, "structuredContent");
            if (isError) {
                String errorCode = text(structured, "errorCode", "UPSTREAM_ERROR");
                String message = text(structured, "message", "工具调用失败");
                throw new ApiException(ExitCodes.BUSINESS, -1, errorCode + ": " + message);
            }
            JsonNode data = structured.isObject() && !structured.isEmpty() ? structured : extractContent(result);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            if (data.isObject() && !data.isEmpty()) {
                List<String> labels = new ArrayList<>();
                List<String> values = new ArrayList<>();
                data.fields().forEachRemaining(entry -> {
                    labels.add(entry.getKey());
                    JsonNode value = entry.getValue();
                    values.add(value.isTextual() ? value.asText()
                            : value.isValueNode() ? value.asText() : truncate(value.toString(), 80));
                });
                out().println(com.suhong.jiuding.cli.TableRenderer.detail(labels, values));
            } else if (data.isArray()) {
                out().println(Json.pretty(data));
            } else {
                out().println(data.isTextual() ? data.asText() : data.toString());
            }
            return ExitCodes.OK;
        }
    }
}
