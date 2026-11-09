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
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Deterministic workflow definition validator: required fields, node types,
 * {@code next} reference integrity, duplicate ids and cycle detection (docs/23 §3).
 */
@Component
public class WorkflowDefinitionValidator {

    private static final Set<String> SUPPORTED_TYPES = Set.of(
            WorkflowNodeDefinition.TYPE_AUTO_CHECK,
            WorkflowNodeDefinition.TYPE_HUMAN,
            WorkflowNodeDefinition.TYPE_PARALLEL,
            WorkflowNodeDefinition.TYPE_SLA);
    /** Mirrors approval_nodes.node VARCHAR(32). */
    private static final int MAX_NODE_ID_LENGTH = 32;

    public WorkflowValidationResult validate(JsonNode definition) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (definition == null || definition.isNull()) {
            return WorkflowValidationResult.failed(List.of("definition is required"), warnings, 0);
        }
        if (!definition.isObject()) {
            return WorkflowValidationResult.failed(List.of("definition must be a JSON object"), warnings, 0);
        }
        JsonNode nodesNode = definition.path("nodes");
        if (!nodesNode.isArray() || nodesNode.isEmpty()) {
            return WorkflowValidationResult.failed(
                    List.of("definition.nodes must be a non-empty array"), warnings, 0);
        }
        checkObjectEntries(nodesNode, "nodes", errors);

        WorkflowDefinitionModel model;
        try {
            model = WorkflowDefinitionModel.parse(definition);
        } catch (IllegalArgumentException ex) {
            return WorkflowValidationResult.failed(List.of(ex.getMessage()), warnings, 0);
        }
        List<WorkflowNodeDefinition> nodes = model.flatten();
        if (nodes.isEmpty()) {
            errors.add("definition.nodes must contain at least one object node");
            return WorkflowValidationResult.failed(errors, warnings, 0);
        }

        Map<String, WorkflowNodeDefinition> byId = new LinkedHashMap<>();
        for (int i = 0; i < nodes.size(); i++) {
            WorkflowNodeDefinition node = nodes.get(i);
            if (node.id() == null || node.id().isBlank()) {
                errors.add("node at index " + i + " requires an id");
                continue;
            }
            if (node.id().length() > MAX_NODE_ID_LENGTH) {
                errors.add("node id '" + node.id() + "' exceeds " + MAX_NODE_ID_LENGTH + " characters");
            }
            if (byId.containsKey(node.id())) {
                errors.add("duplicate node id: " + node.id());
            } else {
                byId.put(node.id(), node);
            }
            String type = node.normalizedType();
            if (type == null) {
                errors.add("node '" + node.id() + "' requires a type");
            } else if (!SUPPORTED_TYPES.contains(type)) {
                errors.add("node '" + node.id() + "' has unsupported type '" + node.type()
                        + "' (expected AUTO_CHECK, HUMAN, PARALLEL or SLA)");
            } else {
                validateTypedNode(node, type, warnings, errors);
            }
        }

        // reference + cycle checks
        Map<String, String> edges = new LinkedHashMap<>();
        for (WorkflowNodeDefinition node : byId.values()) {
            String next = node.next();
            if (next == null || next.isBlank()) {
                continue;
            }
            if (next.equals(node.id())) {
                errors.add("node '" + node.id() + "' cannot reference itself as next");
                continue;
            }
            if (!byId.containsKey(next)) {
                errors.add("node '" + node.id() + "' references unknown next node '" + next + "'");
                continue;
            }
            edges.put(node.id(), next);
        }
        detectCycles(edges, errors);
        reachabilityWarnings(model, byId, warnings);

        if (errors.isEmpty()) {
            return WorkflowValidationResult.ok(warnings, nodes.size());
        }
        return WorkflowValidationResult.failed(errors, warnings, nodes.size());
    }

    private void validateTypedNode(WorkflowNodeDefinition node, String type,
                                   List<String> warnings, List<String> errors) {
        switch (type) {
            case WorkflowNodeDefinition.TYPE_HUMAN -> {
                if (node.role() == null || node.role().isBlank()) {
                    warnings.add("node '" + node.id() + "' has no role, defaults to APPROVER");
                }
                if (node.slaHours() != null && node.slaHours() <= 0) {
                    errors.add("node '" + node.id() + "' slaHours must be positive");
                }
            }
            case WorkflowNodeDefinition.TYPE_SLA -> {
                if (node.slaHours() == null) {
                    errors.add("SLA node '" + node.id() + "' requires slaHours");
                } else if (node.slaHours() <= 0) {
                    errors.add("SLA node '" + node.id() + "' slaHours must be positive");
                }
            }
            case WorkflowNodeDefinition.TYPE_PARALLEL -> {
                if (node.children() == null || node.children().isEmpty()) {
                    warnings.add("PARALLEL node '" + node.id() + "' has no children; executed as a placeholder");
                }
            }
            case WorkflowNodeDefinition.TYPE_AUTO_CHECK -> {
                if (node.skill() == null || node.skill().isBlank()) {
                    warnings.add("AUTO_CHECK node '" + node.id() + "' has no skill, defaults to approval-policy");
                }
            }
            default -> {
                // unreachable: type is validated before this call
            }
        }
    }

    private void checkObjectEntries(JsonNode array, String path, List<String> errors) {
        for (int i = 0; i < array.size(); i++) {
            JsonNode entry = array.get(i);
            if (!entry.isObject()) {
                errors.add(path + "[" + i + "] must be a JSON object");
                continue;
            }
            JsonNode nested = entry.path("nodes");
            if (!nested.isArray()) {
                nested = entry.path("children");
            }
            if (nested.isArray()) {
                checkObjectEntries(nested, path + "[" + i + "].nodes", errors);
            }
        }
    }

    private void detectCycles(Map<String, String> edges, List<String> errors) {
        Map<String, Integer> color = new HashMap<>();
        List<String> stack = new ArrayList<>();
        Set<String> reported = new LinkedHashSet<>();
        for (String id : edges.keySet()) {
            if (color.getOrDefault(id, 0) == 0) {
                dfs(id, edges, color, stack, reported, errors);
            }
        }
    }

    private void dfs(String id, Map<String, String> edges, Map<String, Integer> color,
                     List<String> stack, Set<String> reported, List<String> errors) {
        color.put(id, 1);
        stack.add(id);
        String next = edges.get(id);
        if (next != null) {
            int nextColor = color.getOrDefault(next, 0);
            if (nextColor == 1) {
                int start = stack.indexOf(next);
                List<String> cycle = new ArrayList<>(stack.subList(start < 0 ? 0 : start, stack.size()));
                cycle.add(next);
                String key = String.join("->", cycle);
                if (reported.add(key)) {
                    errors.add("cycle detected in next references: " + key);
                }
            } else if (nextColor == 0) {
                dfs(next, edges, color, stack, reported, errors);
            }
        }
        stack.remove(stack.size() - 1);
        color.put(id, 2);
    }

    private void reachabilityWarnings(WorkflowDefinitionModel model, Map<String, WorkflowNodeDefinition> byId,
                                      List<String> warnings) {
        WorkflowNodeDefinition entry = model.entry();
        if (entry == null || entry.id() == null) {
            return;
        }
        Set<String> reachable = new LinkedHashSet<>();
        collectReachable(entry, byId, reachable);
        for (String id : byId.keySet()) {
            if (!reachable.contains(id)) {
                warnings.add("node '" + id + "' is not reachable from the entry node '" + entry.id() + "'");
            }
        }
    }

    private void collectReachable(WorkflowNodeDefinition node, Map<String, WorkflowNodeDefinition> byId,
                                  Set<String> reachable) {
        if (node == null || node.id() == null || !reachable.add(node.id())) {
            return;
        }
        for (WorkflowNodeDefinition child : node.children()) {
            collectReachable(child, byId, reachable);
        }
        if (node.next() != null) {
            collectReachable(byId.get(node.next()), byId, reachable);
        }
    }
}
