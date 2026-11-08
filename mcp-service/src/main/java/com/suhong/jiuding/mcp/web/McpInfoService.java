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
package com.suhong.jiuding.mcp.web;

import com.suhong.jiuding.mcp.catalog.ToolCatalog;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.prompts.PromptService;
import com.suhong.jiuding.mcp.resources.ResourceService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** {@code GET /mcp/info} capability snapshot, used by the frontend MCP console. */
@Service
public class McpInfoService {

    private final McpProperties properties;
    private final ToolCatalog toolCatalog;
    private final ResourceService resourceService;
    private final PromptService promptService;

    public McpInfoService(McpProperties properties, ToolCatalog toolCatalog,
                          ResourceService resourceService, PromptService promptService) {
        this.properties = properties;
        this.toolCatalog = toolCatalog;
        this.resourceService = resourceService;
        this.promptService = promptService;
    }

    public Map<String, Object> snapshot() {
        Map<String, Object> info = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("name", properties.getServerName());
        server.put("version", properties.getServerVersion());
        server.put("protocolVersion", properties.getProtocolVersion());
        info.put("server", server);
        info.put("protocolVersion", properties.getProtocolVersion());
        info.put("transport", "JSON-RPC 2.0 over HTTP + SSE");
        info.put("tools", toolCatalog.asJsonArray());
        info.put("toolCount", toolCatalog.size());
        info.put("resources", resourceService.list());
        info.put("prompts", promptService.list());
        info.put("apiKeyRequired", true);

        Map<String, Object> endpoints = new LinkedHashMap<>();
        endpoints.put("rpc", "POST /mcp");
        endpoints.put("sse", "GET /mcp");
        endpoints.put("info", "GET /mcp/info");
        info.put("endpoints", endpoints);
        return info;
    }
}
