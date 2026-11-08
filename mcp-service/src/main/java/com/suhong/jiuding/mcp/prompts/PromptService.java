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
package com.suhong.jiuding.mcp.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.downstream.DownstreamClient;
import com.suhong.jiuding.mcp.downstream.DownstreamException;
import com.suhong.jiuding.mcp.protocol.JsonRpcException;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** MCP prompts (docs/05 §Prompts): ready-made review briefs for humans / agents. */
@Component
public class PromptService {

    private final DownstreamClient downstream;
    private final McpProperties properties;
    private final ObjectMapper objectMapper;

    public PromptService(DownstreamClient downstream, McpProperties properties, ObjectMapper objectMapper) {
        this.downstream = downstream;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public ArrayNode list() {
        ArrayNode prompts = objectMapper.createArrayNode();
        prompts.add(prompt("tenant_review", "生成租户审查简报（注入 risk-review 技能指令 + 租户快照）",
                argument("tenantId", "租户 ID", true)));
        prompts.add(prompt("approval_brief", "生成审批摘要供人工决策",
                argument("approvalId", "审批单 ID", true)));
        return prompts;
    }

    /** {@code prompts/get}: returns {@code {description,messages:[{role,content}]}}. */
    public ObjectNode get(String name, JsonNode arguments) {
        if (name == null || name.isBlank()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "missing required argument: name");
        }
        JsonNode args = arguments == null || arguments.isNull() ? objectMapper.createObjectNode() : arguments;
        return switch (name) {
            case "tenant_review" -> tenantReview(args);
            case "approval_brief" -> approvalBrief(args);
            default -> throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "unknown prompt: " + name);
        };
    }

    private ObjectNode tenantReview(JsonNode args) {
        String tenantId = required(args, "tenantId");
        String snapshot = snapshot(() -> downstream.get("tenant-service",
                url(properties.getTenantServiceUrl(), "/api/tenants/" + encodePath(tenantId))));
        StringBuilder text = new StringBuilder();
        text.append("你是多租户平台的租户准入审查助手。请严格按 risk-review 技能的规则审查该租户，输出：\n")
                .append("1) riskLevel（LOW/MEDIUM/HIGH）与 riskScore（0-100）；\n")
                .append("2) findings 列表（命中规则、严重级别、原因）；\n")
                .append("3) 建议 decision（AUTO_APPROVE / NEEDS_REVIEW / REJECT）与 requireRole。\n")
                .append("重点关注：行业风险、region 合规、配额合理性（quotaSeats / quotaStorageGb / quotaApiRpm）、\n")
                .append("contactEmail 可信度，以及 attributes 中的黑名单/负面标记。\n\n")
                .append("租户快照（JSON）：\n")
                .append(snapshot);
        return render("租户审查简报（tenant_review）", text.toString());
    }

    private ObjectNode approvalBrief(JsonNode args) {
        String approvalId = required(args, "approvalId");
        String snapshot = snapshot(() -> downstream.get("approval-service",
                url(properties.getApprovalServiceUrl(), "/api/approvals/" + encodePath(approvalId))));
        StringBuilder text = new StringBuilder();
        text.append("你是审批助手。请基于以下审批单生成人类审批人可快速决策的摘要：\n")
                .append("1) 风险等级与风险评分、触发的规则/原因；\n")
                .append("2) 节点进度（当前节点、已完成节点、剩余 SLA）；\n")
                .append("3) 建议决策（APPROVE / REJECT）与一句理由。\n\n")
                .append("审批单（JSON）：\n")
                .append(snapshot);
        return render("审批摘要（approval_brief）", text.toString());
    }

    private ObjectNode render(String description, String text) {
        ObjectNode result = objectMapper.createObjectNode();
        result.put("description", description);
        ObjectNode message = result.putArray("messages").addObject();
        message.put("role", "user");
        ObjectNode content = message.putObject("content");
        content.put("type", "text");
        content.put("text", text);
        return result;
    }

    private String snapshot(SnapshotLoader loader) {
        try {
            JsonNode data = loader.load();
            return data == null ? "（下游数据暂不可用）" : data.toString();
        } catch (DownstreamException ex) {
            return "（下游数据暂不可用：" + ex.getMessage() + "）";
        }
    }

    @FunctionalInterface
    private interface SnapshotLoader {
        JsonNode load();
    }

    private ObjectNode prompt(String name, String description, ObjectNode argument) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.putArray("arguments").add(argument);
        return node;
    }

    private ObjectNode argument(String name, String description, boolean required) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("description", description);
        node.put("required", required);
        return node;
    }

    private static String required(JsonNode args, String field) {
        JsonNode node = args.get(field);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new JsonRpcException(McpProtocol.INVALID_PARAMS, "missing required argument: " + field);
        }
        return node.asText();
    }

    private static String url(String baseUrl, String suffix) {
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + suffix;
    }

    private static String encodePath(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
