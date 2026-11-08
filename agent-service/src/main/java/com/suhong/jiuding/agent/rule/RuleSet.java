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
import java.util.Map;

/** Parsed {@code rules.yaml} of one skill package. */
public record RuleSet(String skillId, String version, int initialScore,
                      List<Rule> rules, DecisionPolicy decision) {

    public RuleSet {
        rules = rules == null ? List.of() : List.copyOf(rules);
        decision = decision == null ? DecisionPolicy.NONE : decision;
    }

    public static RuleSet empty(String skillId, String version) {
        return new RuleSet(skillId, version, 0, List.of(), DecisionPolicy.NONE);
    }

    public boolean hasRules() {
        return !rules.isEmpty();
    }

    @SuppressWarnings("unchecked")
    public static RuleSet from(Object node, String skillIdFallback, String versionFallback) {
        if (node == null) {
            return empty(skillIdFallback, versionFallback);
        }
        if (!(node instanceof Map<?, ?> raw)) {
            throw new IllegalArgumentException("rules.yaml root must be an object");
        }
        Map<String, Object> map = (Map<String, Object>) raw;
        String skillId = map.get("skillId") == null ? skillIdFallback : String.valueOf(map.get("skillId"));
        String version = map.get("version") == null ? versionFallback : String.valueOf(map.get("version"));
        int initialScore = 0;
        java.math.BigDecimal score = Operator.number(map.get("initialScore"));
        if (score != null) {
            initialScore = score.intValue();
        }
        List<Rule> rules = new ArrayList<>();
        if (map.get("rules") instanceof List<?> list) {
            for (Object item : list) {
                rules.add(Rule.from(item));
            }
        }
        return new RuleSet(skillId, version, initialScore, rules, DecisionPolicy.from(map.get("decision")));
    }
}
