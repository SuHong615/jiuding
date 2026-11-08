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
package com.suhong.jiuding.agent.rule;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * One declarative action of a rule's {@code then:} block.
 *
 * <p>Exactly seven action types exist (docs/04): {@code addScore},
 * {@code addFinding}, {@code setDecision}, {@code setRiskLevel},
 * {@code requireRole}, {@code setSlaHours}, {@code addTag}.
 */
public record RuleAction(String action, Object value, String message, String severity) {

    public static final Set<String> SUPPORTED = Set.of(
            "addScore", "addFinding", "setDecision", "setRiskLevel",
            "requireRole", "setSlaHours", "addTag");

    public RuleAction {
        if (action == null || action.isBlank()) {
            throw new IllegalArgumentException("action name is required");
        }
        action = action.trim();
        if (!SUPPORTED.contains(action)) {
            throw new IllegalArgumentException("unsupported action: " + action
                    + " (supported: " + SUPPORTED + ")");
        }
    }

    @SuppressWarnings("unchecked")
    public static RuleAction from(Object node) {
        if (node instanceof String text) {
            return new RuleAction(text, null, null, null);
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("action must be an object or string");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Object name = map.get("action");
        if (name == null) {
            throw new IllegalArgumentException("action object requires 'action': " + map.keySet());
        }
        String severity = map.get("severity") == null ? null
                : String.valueOf(map.get("severity")).toUpperCase(Locale.ROOT);
        String message = map.get("message") == null ? null : String.valueOf(map.get("message"));
        return new RuleAction(String.valueOf(name), map.get("value"), message, severity);
    }

    @SuppressWarnings("unchecked")
    public static List<RuleAction> listFrom(Object node) {
        if (node == null) {
            return List.of();
        }
        List<RuleAction> actions = new ArrayList<>();
        if (node instanceof List<?> list) {
            for (Object item : list) {
                actions.add(from(item));
            }
            return actions;
        }
        actions.add(from(node));
        return actions;
    }
}
