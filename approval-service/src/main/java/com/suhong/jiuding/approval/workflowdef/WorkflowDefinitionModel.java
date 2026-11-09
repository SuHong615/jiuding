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
package com.suhong.jiuding.approval.workflowdef;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parsed workflow definition: an ordered node list plus nested PARALLEL children.
 * The first node is the entry point; execution follows {@code next} links.
 */
public final class WorkflowDefinitionModel {

    private final List<WorkflowNodeDefinition> nodes;

    public WorkflowDefinitionModel(List<WorkflowNodeDefinition> nodes) {
        this.nodes = nodes == null ? List.of() : List.copyOf(nodes);
    }

    /**
     * Structural parse. Missing/blank optional fields are tolerated; missing arrays
     * or non-object payloads raise {@link IllegalArgumentException}. Semantic rules
     * (required values, references, cycles) belong to {@link WorkflowDefinitionValidator}.
     */
    public static WorkflowDefinitionModel parse(JsonNode raw) {
        if (raw == null || !raw.isObject()) {
            throw new IllegalArgumentException("definition must be a JSON object");
        }
        JsonNode nodes = raw.path("nodes");
        if (!nodes.isArray()) {
            throw new IllegalArgumentException("definition.nodes must be an array");
        }
        List<WorkflowNodeDefinition> parsed = new ArrayList<>();
        for (JsonNode node : nodes) {
            if (node.isObject()) {
                parsed.add(parseNode(node));
            }
        }
        return new WorkflowDefinitionModel(parsed);
    }

    private static WorkflowNodeDefinition parseNode(JsonNode node) {
        List<WorkflowNodeDefinition> children = new ArrayList<>();
        JsonNode nested = node.path("nodes");
        if (!nested.isArray()) {
            nested = node.path("children");
        }
        if (nested.isArray()) {
            for (JsonNode child : nested) {
                if (child.isObject()) {
                    children.add(parseNode(child));
                }
            }
        }
        return new WorkflowNodeDefinition(
                text(node, "id"),
                text(node, "type"),
                text(node, "skill"),
                text(node, "role"),
                integer(node, "slaHours", "hours"),
                text(node, "next"),
                List.copyOf(children));
    }

    public List<WorkflowNodeDefinition> nodes() {
        return nodes;
    }

    public WorkflowNodeDefinition entry() {
        return nodes.isEmpty() ? null : nodes.get(0);
    }

    public Map<String, WorkflowNodeDefinition> byId() {
        Map<String, WorkflowNodeDefinition> map = new LinkedHashMap<>();
        for (WorkflowNodeDefinition node : flatten()) {
            if (node.id() != null) {
                map.put(node.id(), node);
            }
        }
        return map;
    }

    /** All nodes (top level first, then nested children) in declaration order. */
    public List<WorkflowNodeDefinition> flatten() {
        List<WorkflowNodeDefinition> all = new ArrayList<>();
        for (WorkflowNodeDefinition node : nodes) {
            collect(node, all);
        }
        return all;
    }

    private void collect(WorkflowNodeDefinition node, List<WorkflowNodeDefinition> target) {
        target.add(node);
        for (WorkflowNodeDefinition child : node.children()) {
            collect(child, target);
        }
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        if (value.isTextual()) {
            String text = value.asText();
            return text.isBlank() ? null : text.trim();
        }
        if (value.isNumber() || value.isBoolean()) {
            return value.asText();
        }
        return null;
    }

    private static Integer integer(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }
}
