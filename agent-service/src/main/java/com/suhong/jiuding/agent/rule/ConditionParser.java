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

/** Parses the {@code when:} node of a YAML rule into a {@link Condition} tree. */
public final class ConditionParser {

    private ConditionParser() {
    }

    public static Condition parse(Object node) {
        if (node == null) {
            throw new IllegalArgumentException("condition node is null");
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("condition must be an object, got: " + node.getClass().getSimpleName());
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) raw;

        if (map.containsKey("all")) {
            return new Condition.All(parseList(map.get("all"), "all"));
        }
        if (map.containsKey("any")) {
            return new Condition.Any(parseList(map.get("any"), "any"));
        }
        if (map.containsKey("not")) {
            return new Condition.Not(parse(map.get("not")));
        }
        return parseLeaf(map);
    }

    private static List<Condition> parseList(Object value, String wrapper) {
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("'" + wrapper + "' requires a list");
        }
        List<Condition> conditions = new ArrayList<>(list.size());
        for (Object item : list) {
            conditions.add(parse(item));
        }
        return conditions;
    }

    private static Condition parseLeaf(Map<String, Object> map) {
        Object field = map.get("field");
        if (field == null || String.valueOf(field).isBlank()) {
            throw new IllegalArgumentException("leaf condition requires 'field': " + map.keySet());
        }
        Object op = map.get("op");
        if (op == null) {
            throw new IllegalArgumentException("leaf condition requires 'op' for field " + field);
        }
        Operator operator = Operator.from(String.valueOf(op));
        String name = String.valueOf(field);
        if (!name.matches("[A-Za-z0-9_.\\[\\]-]+")) {
            throw new IllegalArgumentException("illegal field path: " + name);
        }
        if ((operator == Operator.EXISTS || operator == Operator.EMPTY)) {
            return new Condition.Leaf(name, operator, null);
        }
        if (!map.containsKey("value")) {
            throw new IllegalArgumentException("operator " + operator.wireName()
                    + " requires 'value' for field " + name);
        }
        return new Condition.Leaf(name, operator, map.get("value"));
    }

    /** Renders a condition as a compact reason string (used by trace of skipped rules). */
    public static String describe(Condition condition) {
        return condition == null ? "none" : condition.describe().toLowerCase(Locale.ROOT);
    }
}
