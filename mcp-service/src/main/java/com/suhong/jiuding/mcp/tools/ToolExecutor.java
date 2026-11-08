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
package com.suhong.jiuding.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.downstream.DownstreamClient;
import com.suhong.jiuding.mcp.downstream.DownstreamException;
import com.suhong.jiuding.mcp.protocol.JsonRpcException;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Executes the 22 MCP tools by proxying to the downstream REST services
 * (docs/05 §Tools). Protocol violations throw {@link JsonRpcException}
 * ({@code -32602}); downstream failures are converted to {@code isError:true}
 * results with {@code structuredContent={errorCode,message}}.
 */
@Component
public class ToolExecutor {

    private final DownstreamClient downstream;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public ToolExecutor(DownstreamClient downstream, McpProperties properties, ObjectMapper objectMapper) {
        this.downstream = downstream;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ToolResult execute(String name, JsonNode arguments) {
        JsonNode args = arguments == null || arguments.isNull() ? objectMapper.createObjectNode() : arguments;
        try {
            JsonNode data = switch (name) {
                case "tenant.create" -> tenantCreate(args);
                case "tenant.list" -> tenantList(args);
                case "tenant.get" -> tenantGet(args);
                case "tenant.submit" -> tenantSubmit(args);
                case "tenant.suspend" -> tenantSuspend(args);
                case "tenant.activate" -> tenantActivate(args);
                case "collection.import" -> collectionImport(args);
                case "approval.list_pending" -> approvalListPending();
                case "approval.get" -> approvalGet(args);
                case "approval.decide" -> approvalDecide(args);
                case "skill.list" -> skillList(args);
                case "skill.run" -> skillRun(args);
                case "agent.run" -> agentRun(args);
                case "platform.overview" -> platformOverview();
                case "org.tree" -> orgTree(args);
                case "org.create" -> orgCreate(args);
                case "account.list" -> accountList(args);
                case "account.create" -> accountCreate(args);
                case "account.reset_password" -> accountResetPassword(args);
                case "rating.assess" -> ratingAssess(args);
                case "rating.list" -> ratingList(args);
                case "tenant.delete" -> tenantDelete(args);
                default -> throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "unknown tool: " + name);
            };
            if (data == null || data.isNull()) {
                data = objectMapper.createObjectNode();
            }
            return ToolResult.ok(data);
        } catch (DownstreamException ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("errorCode", ex.errorCode());
            error.put("message", ex.getMessage());
            return ToolResult.error(error);
        }
    }

    private JsonNode tenantCreate(JsonNode args) {
        String tenantCode = requireText(args, "tenantCode");
        String name = requireText(args, "name");
        ObjectNode body = objectMapper.createObjectNode();
        copy(args, body, "contactName", "contactEmail", "contactPhone", "plan", "region",
                "isolationMode", "quotaSeats", "quotaStorageGb", "quotaApiRpm", "source",
                "attributes", "autoSubmit");
        body.put("tenantCode", tenantCode);
        body.put("name", name);
        return downstream.post("tenant-service", url(properties.getTenantServiceUrl(), "/api/tenants"), body);
    }

    private JsonNode tenantList(JsonNode args) {
        StringBuilder query = new StringBuilder();
        append(query, "status", text(args, "status"));
        append(query, "plan", text(args, "plan"));
        append(query, "keyword", text(args, "keyword"));
        append(query, "page", text(args, "page"));
        append(query, "size", text(args, "size"));
        String suffix = "/api/tenants" + (query.length() == 0 ? "" : "?" + query);
        return downstream.get("tenant-service", url(properties.getTenantServiceUrl(), suffix));
    }

    private JsonNode tenantGet(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        return downstream.get("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId)));
    }

    private JsonNode tenantSubmit(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        return downstream.post("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId) + "/submit"),
                objectMapper.createObjectNode());
    }

    private JsonNode tenantSuspend(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("reason", text(args, "reason") == null ? "suspended by MCP tool" : text(args, "reason"));
        return downstream.post("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId) + "/suspend"), body);
    }

    private JsonNode tenantActivate(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        return downstream.post("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId) + "/activate"),
                objectMapper.createObjectNode());
    }

    private JsonNode collectionImport(JsonNode args) {
        String sourceName = requireText(args, "sourceName");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("sourceName", sourceName);
        body.put("mode", text(args, "mode") == null ? "MANUAL_BATCH" : text(args, "mode"));
        JsonNode autoSubmit = args.get("autoSubmit");
        body.put("autoSubmit", autoSubmit != null && autoSubmit.asBoolean(false));
        JsonNode records = args.get("records");
        body.set("records", records != null && records.isArray() ? records : objectMapper.createArrayNode());
        return downstream.post("tenant-service", url(properties.getTenantServiceUrl(), "/api/tenants/collect"), body);
    }

    private JsonNode approvalListPending() {
        return downstream.get("approval-service",
                url(properties.getApprovalServiceUrl(), "/api/approvals/pending"));
    }

    private JsonNode approvalGet(JsonNode args) {
        String approvalId = requireText(args, "approvalId");
        return downstream.get("approval-service",
                url(properties.getApprovalServiceUrl(), "/api/approvals/" + encodePath(approvalId)));
    }

    private JsonNode approvalDecide(JsonNode args) {
        String approvalId = requireText(args, "approvalId");
        String decision = requireText(args, "decision").toUpperCase(Locale.ROOT);
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "decision must be APPROVE or REJECT");
        }
        ObjectNode body = objectMapper.createObjectNode();
        body.put("decision", decision);
        String comment = text(args, "comment");
        if (comment != null) {
            body.put("comment", comment);
        }
        return downstream.post("approval-service",
                url(properties.getApprovalServiceUrl(), "/api/approvals/" + encodePath(approvalId) + "/decisions"),
                body);
    }

    private JsonNode skillList(JsonNode args) {
        String type = text(args, "type");
        StringBuilder suffix = new StringBuilder("/api/skills");
        if (type != null && !type.isBlank()) {
            suffix.append("?type=").append(URLEncoder.encode(type, StandardCharsets.UTF_8));
        }
        return downstream.get("agent-service", url(properties.getAgentServiceUrl(), suffix.toString()));
    }

    private JsonNode skillRun(JsonNode args) {
        String skillId = requireText(args, "skillId");
        ObjectNode body = objectMapper.createObjectNode();
        JsonNode input = args.get("input");
        body.set("input", input != null && !input.isNull() ? input : objectMapper.createObjectNode());
        String tenantId = text(args, "tenantId");
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        return downstream.post("agent-service",
                url(properties.getAgentServiceUrl(), "/api/skills/" + encodePath(skillId) + "/run"),
                body, properties.getSkillTimeoutMs());
    }

    private JsonNode agentRun(JsonNode args) {
        String agentType = requireText(args, "agentType");
        ObjectNode body = objectMapper.createObjectNode();
        body.put("agentType", agentType);
        String tenantId = text(args, "tenantId");
        if (tenantId != null) {
            body.put("tenantId", tenantId);
        }
        JsonNode input = args.get("input");
        body.set("input", input != null && !input.isNull() ? input : objectMapper.createObjectNode());
        JsonNode maxSteps = args.get("maxSteps");
        if (maxSteps != null && maxSteps.isNumber()) {
            body.put("maxSteps", maxSteps.asInt());
        }
        return downstream.post("agent-service",
                url(properties.getAgentServiceUrl(), "/api/agents/runs"), body);
    }

    // ------------------------------------------------ org / account / rating (docs/11 §6)

    private JsonNode orgTree(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        String format = text(args, "format");
        String normalized = format == null ? "tree" : format.toLowerCase(Locale.ROOT);
        if (!"tree".equals(normalized) && !"flat".equals(normalized)) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "format must be tree or flat");
        }
        StringBuilder suffix = new StringBuilder(orgBasePath(tenantId));
        appendQuery(suffix, "format", normalized);
        return downstream.getForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), suffix.toString()), tenantId);
    }

    private JsonNode orgCreate(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        String code = requireText(args, "code");
        String name = requireText(args, "name");
        String type = requireEnum(args, "type", List.of("COMPANY", "BRANCH", "DEPARTMENT", "TEAM"));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("code", code);
        body.put("name", name);
        body.put("type", type);
        copy(args, body, "parentId", "sortOrder", "managerAccountId");
        return downstream.postForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), orgBasePath(tenantId)), body, tenantId);
    }

    private JsonNode accountList(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        StringBuilder suffix = new StringBuilder(accountsBasePath(tenantId));
        appendQuery(suffix, "orgUnitId", text(args, "orgUnitId"));
        appendQuery(suffix, "role", text(args, "role"));
        appendQuery(suffix, "status", text(args, "status"));
        appendQuery(suffix, "keyword", text(args, "keyword"));
        appendQuery(suffix, "page", text(args, "page"));
        appendQuery(suffix, "size", text(args, "size"));
        return downstream.getForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), suffix.toString()), tenantId);
    }

    private JsonNode accountCreate(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        String username = requireText(args, "username");
        String displayName = requireText(args, "displayName");
        String role = requireEnum(args, "role", List.of("TENANT_ADMIN", "ORG_ADMIN", "MEMBER", "AUDITOR"));
        ObjectNode body = objectMapper.createObjectNode();
        body.put("username", username);
        body.put("displayName", displayName);
        body.put("role", role);
        copy(args, body, "email", "phone", "orgUnitId", "initialPassword");
        return downstream.postForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), accountsBasePath(tenantId)), body, tenantId);
    }

    private JsonNode accountResetPassword(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        String accountId = requireText(args, "accountId");
        ObjectNode body = objectMapper.createObjectNode();
        String newPassword = text(args, "newPassword");
        if (newPassword != null) {
            body.put("newPassword", newPassword);
        }
        return downstream.postForTenant("tenant-service",
                url(properties.getTenantServiceUrl(),
                        accountsBasePath(tenantId) + "/" + encodePath(accountId) + "/reset-password"),
                body, tenantId);
    }

    private JsonNode ratingAssess(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        ObjectNode body = objectMapper.createObjectNode();
        String note = text(args, "note");
        if (note != null) {
            body.put("note", note);
        }
        return downstream.postForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), ratingsBasePath(tenantId) + "/assess"), body, tenantId);
    }

    private JsonNode ratingList(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        StringBuilder suffix = new StringBuilder(ratingsBasePath(tenantId));
        appendQuery(suffix, "page", text(args, "page"));
        appendQuery(suffix, "size", text(args, "size"));
        return downstream.getForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), suffix.toString()), tenantId);
    }

    private JsonNode tenantDelete(JsonNode args) {
        String tenantId = requireText(args, "tenantId");
        return downstream.deleteForTenant("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId)), tenantId);
    }

    private static String orgBasePath(String tenantId) {
        return "/api/tenants/" + encodePath(tenantId) + "/org-units";
    }

    private static String accountsBasePath(String tenantId) {
        return "/api/tenants/" + encodePath(tenantId) + "/accounts";
    }

    private static String ratingsBasePath(String tenantId) {
        return "/api/tenants/" + encodePath(tenantId) + "/ratings";
    }

    private static String requireEnum(JsonNode args, String field, List<String> allowed) {
        String value = requireText(args, field).toUpperCase(Locale.ROOT);
        if (!allowed.contains(value)) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS,
                    field + " must be one of " + allowed);
        }
        return value;
    }

    /**
     * Aggregated snapshot. Each section degrades independently; the tool only
     * fails when every downstream service is unreachable.
     */
    private JsonNode platformOverview() {
        int[] available = {0};
        JsonNode tenants = safeGet("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/overview"), available);
        JsonNode approvals = safeGet("approval-service",
                url(properties.getApprovalServiceUrl(), "/api/approvals/overview"), available);
        JsonNode agents = safeGet("agent-service",
                url(properties.getAgentServiceUrl(), "/api/agents/overview"), available);
        JsonNode skills = safeGet("agent-service",
                url(properties.getAgentServiceUrl(), "/api/skills"), available);
        if (available[0] == 0) {
            throw new DownstreamException(ErrorCode.UPSTREAM_ERROR.code(),
                    "all downstream services are unavailable");
        }
        ObjectNode result = objectMapper.createObjectNode();
        result.set("tenants", tenants);
        result.set("approvals", approvals);
        result.set("agents", agents);
        ObjectNode skillSection = objectMapper.createObjectNode();
        skillSection.put("total", skills != null && skills.isArray() ? skills.size() : 0);
        result.set("skills", skillSection);
        return result;
    }

    private JsonNode safeGet(String service, String url, int[] available) {
        try {
            JsonNode data = downstream.get(service, url);
            available[0]++;
            return data == null || data.isNull() ? objectMapper.createObjectNode() : data;
        } catch (DownstreamException ex) {
            ObjectNode error = objectMapper.createObjectNode();
            error.put("errorCode", ex.errorCode());
            error.put("message", ex.getMessage());
            return error;
        }
    }

    private static String url(String baseUrl, String suffix) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + suffix;
    }

    private static void append(StringBuilder query, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (query.length() > 0) {
            query.append('&');
        }
        query.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    /** Appends a query parameter to a complete URL, adding {@code ?} or {@code &} as needed. */
    private static void appendQuery(StringBuilder url, String key, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        url.append(url.indexOf("?") >= 0 ? '&' : '?');
        url.append(key).append('=').append(URLEncoder.encode(value, StandardCharsets.UTF_8));
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static void copy(JsonNode from, ObjectNode to, String... fields) {
        for (String field : fields) {
            JsonNode value = from.get(field);
            if (value != null && !value.isNull()) {
                to.set(field, value);
            }
        }
    }

    private static String requireText(JsonNode args, String field) {
        String value = text(args, field);
        if (value == null || value.isBlank()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "missing required argument: " + field);
        }
        return value;
    }

    private static String text(JsonNode args, String field) {
        JsonNode node = args == null ? null : args.get(field);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asText();
        return value == null || value.isEmpty() ? null : value;
    }
}
