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
package com.suhong.jiuding.mcp.resources;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.downstream.DownstreamClient;
import com.suhong.jiuding.mcp.protocol.JsonRpcException;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** MCP resources (docs/05 §Resources): read-only JSON snapshots of platform state. */
@Component
public class ResourceService {

    private static final String TENANTS_URI = "tenancy://tenants";
    private static final String TENANTS_PREFIX = "tenancy://tenants/";
    private static final String SKILLS_URI = "tenancy://skills";
    private static final String APPROVALS_PENDING_URI = "tenancy://approvals/pending";

    private final DownstreamClient downstream;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public ResourceService(DownstreamClient downstream, McpProperties properties, ObjectMapper objectMapper) {
        this.downstream = downstream;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ArrayNode list() {
        ArrayNode resources = objectMapper.createArrayNode();
        resources.add(resource(TENANTS_URI, "租户列表", "全部租户的 JSON 快照", "application/json"));
        resources.add(resource(TENANTS_PREFIX + "{id}", "租户详情", "按租户 ID 读取详情（tenancy://tenants/{id}）", "application/json"));
        resources.add(resource(SKILLS_URI, "Skill 清单", "agent-service 已加载的技能", "application/json"));
        resources.add(resource(APPROVALS_PENDING_URI, "待办审批", "当前待人工处理的审批单", "application/json"));
        return resources;
    }

    /** {@code resources/read}: returns {@code {contents:[{uri,mimeType,text}]}}. */
    public ObjectNode read(String uri) {
        if (uri == null || uri.isBlank()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "missing required argument: uri");
        }
        JsonNode data;
        if (TENANTS_URI.equals(uri)) {
            data = downstream.get("tenant-service", url(properties.getTenantServiceUrl(), "/api/tenants?size=50"));
        } else if (uri.startsWith(TENANTS_PREFIX) && uri.length() > TENANTS_PREFIX.length()) {
            String tenantId = uri.substring(TENANTS_PREFIX.length());
            data = downstream.get("tenant-service",
                    url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId)));
        } else if (SKILLS_URI.equals(uri)) {
            data = downstream.get("agent-service", url(properties.getAgentServiceUrl(), "/api/skills"));
        } else if (APPROVALS_PENDING_URI.equals(uri)) {
            data = downstream.get("approval-service",
                    url(properties.getApprovalServiceUrl(), "/api/approvals/pending"));
        } else {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "unknown resource uri: " + uri);
        }

        ObjectNode contents = objectMapper.createObjectNode();
        ObjectNode item = contents.putArray("contents").addObject();
        item.put("uri", uri);
        item.put("mimeType", "application/json");
        item.put("text", data == null ? "{}" : data.toString());
        return contents;
    }

    private ObjectNode resource(String uri, String name, String description, String mimeType) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("uri", uri);
        node.put("name", name);
        node.put("description", description);
        node.put("mimeType", mimeType);
        return node;
    }

    private static String url(String baseUrl, String suffix) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + suffix;
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
