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
package com.suhong.jiuding.mcp.catalog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The 22 tools of docs/05-mcp-spec.md, with their JSON-Schema argument contracts.
 * Tool names are the stable public API exposed to MCP clients; execution lives in
 * {@code com.suhong.jiuding.mcp.tools.ToolExecutor}.
 */
@Component
public class ToolCatalog {

    private final ObjectMapper objectMapper;
    private final Map<String, ToolDefinition> tools;

    public ToolCatalog(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.tools = build();
    }

    public List<ToolDefinition> all() {
        return List.copyOf(tools.values());
    }

    public Optional<ToolDefinition> find(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(tools.get(name));
    }

    public boolean contains(String name) {
        return name != null && tools.containsKey(name);
    }

    public int size() {
        return tools.size();
    }

    public ArrayNode asJsonArray() {
        ArrayNode array = objectMapper.createArrayNode();
        tools.values().forEach(tool -> array.add(tool.toJson(objectMapper)));
        return array;
    }

    private Map<String, ToolDefinition> build() {
        Map<String, ToolDefinition> registry = new LinkedHashMap<>();

        register(registry, "tenant.create", "创建租户（写入 tenant-service，DRAFT 状态）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantCode": {"type": "string", "description": "租户编码（唯一）"},
                    "name": {"type": "string", "description": "租户名称"},
                    "contactEmail": {"type": "string", "description": "联系人邮箱"},
                    "plan": {"type": "string", "enum": ["FREE", "PRO", "ENTERPRISE"]},
                    "region": {"type": "string"},
                    "isolationMode": {"type": "string", "enum": ["SHARED_SCHEMA", "DEDICATED_SCHEMA", "DEDICATED_DB"]},
                    "quotaSeats": {"type": "integer"},
                    "quotaStorageGb": {"type": "integer"},
                    "quotaApiRpm": {"type": "integer"},
                    "attributes": {"type": "object"},
                    "autoSubmit": {"type": "boolean"}
                  },
                  "required": ["tenantCode", "name"]
                }
                """);

        register(registry, "tenant.list", "分页查询租户", """
                {
                  "type": "object",
                  "properties": {
                    "status": {"type": "string"},
                    "plan": {"type": "string"},
                    "keyword": {"type": "string"},
                    "page": {"type": "integer", "minimum": 0, "default": 0},
                    "size": {"type": "integer", "minimum": 1, "maximum": 200, "default": 20}
                  }
                }
                """);

        register(registry, "tenant.get", "查询租户详情", """
                {
                  "type": "object",
                  "properties": {"tenantId": {"type": "string"}},
                  "required": ["tenantId"]
                }
                """);

        register(registry, "tenant.submit", "提交租户审批（同步调用 approval-service）", """
                {
                  "type": "object",
                  "properties": {"tenantId": {"type": "string"}},
                  "required": ["tenantId"]
                }
                """);

        register(registry, "tenant.suspend", "停用租户（仅 ACTIVE）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string"},
                    "reason": {"type": "string"}
                  },
                  "required": ["tenantId"]
                }
                """);

        register(registry, "tenant.activate", "启用已停用租户", """
                {
                  "type": "object",
                  "properties": {"tenantId": {"type": "string"}},
                  "required": ["tenantId"]
                }
                """);

        register(registry, "collection.import", "批量采集租户记录", """
                {
                  "type": "object",
                  "properties": {
                    "sourceName": {"type": "string"},
                    "mode": {"type": "string", "enum": ["MANUAL_BATCH", "API_PULL"]},
                    "autoSubmit": {"type": "boolean"},
                    "records": {"type": "array", "items": {"type": "object"}}
                  },
                  "required": ["sourceName"]
                }
                """);

        register(registry, "approval.list_pending", "查询待办审批列表", """
                {"type": "object", "properties": {}}
                """);

        register(registry, "approval.get", "查询审批详情（含节点与时间线）", """
                {
                  "type": "object",
                  "properties": {"approvalId": {"type": "string"}},
                  "required": ["approvalId"]
                }
                """);

        register(registry, "approval.decide", "提交审批决策", """
                {
                  "type": "object",
                  "properties": {
                    "approvalId": {"type": "string"},
                    "decision": {"type": "string", "enum": ["APPROVE", "REJECT"]},
                    "comment": {"type": "string"}
                  },
                  "required": ["approvalId", "decision"]
                }
                """);

        register(registry, "skill.list", "查询 Skill 清单", """
                {
                  "type": "object",
                  "properties": {"type": {"type": "string"}}
                }
                """);

        register(registry, "skill.run", "执行 Skill 规则引擎", """
                {
                  "type": "object",
                  "properties": {
                    "skillId": {"type": "string"},
                    "tenantId": {"type": "string"},
                    "input": {"type": "object"}
                  },
                  "required": ["skillId"]
                }
                """);

        register(registry, "agent.run", "发起 Agent 运行", """
                {
                  "type": "object",
                  "properties": {
                    "agentType": {"type": "string", "enum": ["tenant-intake", "risk-review", "approval", "provisioning"]},
                    "tenantId": {"type": "string"},
                    "input": {"type": "object"},
                    "maxSteps": {"type": "integer", "minimum": 1, "maximum": 20}
                  },
                  "required": ["agentType"]
                }
                """);

        register(registry, "platform.overview", "平台总览（租户/审批/技能/Agent 聚合）", """
                {"type": "object", "properties": {}}
                """);

        register(registry, "org.tree", "查询租户组织树（tree 层级或 flat 列表）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string", "description": "租户 ID（同时注入 X-Tenant-Id）"},
                    "format": {"type": "string", "enum": ["tree", "flat"], "default": "tree"}
                  },
                  "required": ["tenantId"]
                }
                """);

        register(registry, "org.create", "创建组织节点（公司/分支/部门/团队）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string", "description": "租户 ID"},
                    "parentId": {"type": "string", "description": "父节点 ID，空表示根节点"},
                    "code": {"type": "string", "description": "组织编码（租户内唯一）"},
                    "name": {"type": "string", "description": "组织名称"},
                    "type": {"type": "string", "enum": ["COMPANY", "BRANCH", "DEPARTMENT", "TEAM"]},
                    "sortOrder": {"type": "integer"},
                    "managerAccountId": {"type": "string"}
                  },
                  "required": ["tenantId", "code", "name", "type"]
                }
                """);

        register(registry, "account.list", "分页查询租户内账号目录", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string", "description": "租户 ID"},
                    "orgUnitId": {"type": "string"},
                    "role": {"type": "string", "enum": ["TENANT_ADMIN", "ORG_ADMIN", "MEMBER", "AUDITOR"]},
                    "status": {"type": "string", "enum": ["INVITED", "ACTIVE", "DISABLED", "LOCKED", "DELETED"]},
                    "keyword": {"type": "string"},
                    "page": {"type": "integer", "minimum": 0, "default": 0},
                    "size": {"type": "integer", "minimum": 1, "maximum": 200, "default": 20}
                  },
                  "required": ["tenantId"]
                }
                """);

        register(registry, "account.create", "创建租户内账号（未给初始密码时返回一次性临时密码）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string", "description": "租户 ID"},
                    "username": {"type": "string"},
                    "displayName": {"type": "string"},
                    "email": {"type": "string"},
                    "phone": {"type": "string"},
                    "orgUnitId": {"type": "string"},
                    "role": {"type": "string", "enum": ["TENANT_ADMIN", "ORG_ADMIN", "MEMBER", "AUDITOR"]},
                    "initialPassword": {"type": "string"}
                  },
                  "required": ["tenantId", "username", "displayName", "role"]
                }
                """);

        register(registry, "account.reset_password", "重置账号密码并返回一次性临时密码", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string"},
                    "accountId": {"type": "string"},
                    "newPassword": {"type": "string"}
                  },
                  "required": ["tenantId", "accountId"]
                }
                """);

        register(registry, "rating.assess", "评估租户评级（写历史并刷新租户当前评级）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string"},
                    "note": {"type": "string"}
                  },
                  "required": ["tenantId"]
                }
                """);

        register(registry, "rating.list", "分页查询租户评级历史（倒序）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string"},
                    "page": {"type": "integer", "minimum": 0, "default": 0},
                    "size": {"type": "integer", "minimum": 1, "maximum": 200, "default": 20}
                  },
                  "required": ["tenantId"]
                }
                """);

        register(registry, "tenant.delete", "软删除租户（仅平台管理员，且租户不能是 ACTIVE）", """
                {
                  "type": "object",
                  "properties": {
                    "tenantId": {"type": "string"}
                  },
                  "required": ["tenantId"]
                }
                """);

        return registry;
    }

    private void register(Map<String, ToolDefinition> registry, String name, String description, String schemaJson) {
        registry.put(name, new ToolDefinition(name, description, parseSchema(name, schemaJson)));
    }

    private JsonNode parseSchema(String tool, String schemaJson) {
        try {
            return objectMapper.readTree(schemaJson);
        } catch (Exception ex) {
            throw new IllegalStateException("invalid JSON schema for tool " + tool, ex);
        }
    }
}
