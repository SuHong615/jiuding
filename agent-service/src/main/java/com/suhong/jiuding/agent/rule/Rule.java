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

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** A single deterministic rule: {@code when} condition tree and {@code then} actions. */
public record Rule(String id, String description, String severity, Condition when, List<RuleAction> then) {

    public Rule {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("rule id is required");
        }
        severity = severity == null || severity.isBlank()
                ? "LOW" : severity.toUpperCase(Locale.ROOT);
        then = then == null ? List.of() : List.copyOf(then);
    }

    @SuppressWarnings("unchecked")
    public static Rule from(Object node) {
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("rule must be an object");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        Object id = map.get("id");
        String description = map.get("description") == null ? null : String.valueOf(map.get("description"));
        String severity = map.get("severity") == null ? null : String.valueOf(map.get("severity"));
        Condition when = ConditionParser.parse(map.get("when"));
        List<RuleAction> then = RuleAction.listFrom(map.get("then"));
        return new Rule(id == null ? null : String.valueOf(id), description, severity, when, then);
    }
}
