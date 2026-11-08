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
package com.suhong.jiuding.mcp.protocol;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.mcp.catalog.ToolCatalog;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.prompts.PromptService;
import com.suhong.jiuding.mcp.resources.ResourceService;
import com.suhong.jiuding.mcp.tools.ToolExecutor;
import com.suhong.jiuding.mcp.tools.ToolResult;
import org.springframework.stereotype.Component;

/** Method table of the MCP server (docs/05 §方法). Protocol errors throw {@link JsonRpcException}. */
@Component
public class McpDispatcher {

    private final ObjectMapper objectMapper;
    private final McpProperties properties;
    private final ToolCatalog toolCatalog;
    private final ToolExecutor toolExecutor;
    private final ResourceService resourceService;
    private final PromptService promptService;

    public McpDispatcher(ObjectMapper objectMapper, McpProperties properties, ToolCatalog toolCatalog,
                         ToolExecutor toolExecutor, ResourceService resourceService, PromptService promptService) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.toolCatalog = toolCatalog;
        this.toolExecutor = toolExecutor;
        this.resourceService = resourceService;
        this.promptService = promptService;
    }

    public JsonNode dispatch(String method, JsonNode params) {
        return switch (method) {
            case "initialize" -> initialize();
            case "ping" -> objectMapper.createObjectNode();
            case "tools/list" -> toolsList();
            case "tools/call" -> toolsCall(params);
            case "resources/list" -> resourcesList();
            case "resources/read" -> resourcesRead(params);
            case "prompts/list" -> promptsList();
            case "prompts/get" -> promptsGet(params);
            default -> throw new JsonRpcException(McpProtocol.METHOD_NOT_FOUND, "method not found: " + method);
        };
    }

    private ObjectNode initialize() {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("protocolVersion", properties.getProtocolVersion());

        ObjectNode capabilities = result.putObject("capabilities");
        capabilities.putObject("tools").put("listChanged", false);
        capabilities.putObject("resources").put("subscribe", false);
        capabilities.putObject("prompts");

        ObjectNode serverInfo = result.putObject("serverInfo");
        serverInfo.put("name", properties.getServerName());
        serverInfo.put("version", properties.getServerVersion());
        result.put("instructions", "多租户中心 MCP 服务：租户、组织、账号、评级、审批、Skill、Agent、采集");
        return result;
    }

    private ObjectNode toolsList() {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tools", toolCatalog.asJsonArray());
        return result;
    }

    private JsonNode toolsCall(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "params must be an object");
        }
        JsonNode nameNode = params.get("name");
        if (nameNode == null || nameNode.asText().isBlank()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "missing required argument: name");
        }
        String name = nameNode.asText();
        if (!toolCatalog.contains(name)) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "unknown tool: " + name);
        }
        ToolResult result = toolExecutor.execute(name, params.get("arguments"));
        return result.toJson(objectMapper);
    }

    private ObjectNode resourcesList() {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("resources", resourceService.list());
        return result;
    }

    private JsonNode resourcesRead(JsonNode params) {
        String uri = params == null ? null : params.path("uri").asText(null);
        return resourceService.read(uri);
    }

    private ObjectNode promptsList() {
        ObjectNode result = objectMapper.createObjectNode();
        result.set("prompts", promptService.list());
        return result;
    }

    private JsonNode promptsGet(JsonNode params) {
        if (params == null || !params.isObject()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "params must be an object");
        }
        return promptService.get(params.path("name").asText(null), params.get("arguments"));
    }
}
