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

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Condition tree + all 10 operators of the rule DSL (docs/04-skill-spec.md). */
class RuleEngineConditionTest {

    @Test
    void nestedAllAnyNotIsEvaluated() {
        // all( any(industry in [...], plan eq ENTERPRISE), not(region in [iran]) )
        Map<String, Object> when = all(
                any(
                        leaf("attributes.industry", "in", List.of("gambling", "crypto-exchange")),
                        leaf("plan", "eq", "ENTERPRISE")),
                not(leaf("region", "in", List.of("iran"))));

        Condition condition = ConditionParser.parse(when);

        Map<String, Object> gambling = Map.of(
                "plan", "PRO", "region", "cn-shanghai",
                "attributes", Map.of("industry", "gambling"));
        assertThat(condition.evaluate(gambling).matched()).isTrue();

        Map<String, Object> enterprise = Map.of(
                "plan", "ENTERPRISE", "region", "cn-shanghai",
                "attributes", Map.of("industry", "software"));
        assertThat(condition.evaluate(enterprise).matched()).isTrue();

        Map<String, Object> sanctioned = Map.of(
                "plan", "ENTERPRISE", "region", "iran",
                "attributes", Map.of("industry", "software"));
        assertThat(condition.evaluate(sanctioned).matched()).isFalse();

        Map<String, Object> other = Map.of(
                "plan", "PRO", "region", "cn-shanghai",
                "attributes", Map.of("industry", "retail"));
        assertThat(condition.evaluate(other).matched()).isFalse();
        assertThat(condition.evaluate(other).reason()).isNotBlank();
    }

    @Test
    void everyOperatorMatchesItsContract() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("seats", 50);
        root.put("name", "Acme 云服务");
        root.put("plan", "PRO");
        root.put("enabled", true);
        root.put("tags", List.of("risk", "compliance"));
        root.put("email", "ops@acme.com");
        root.put("blank", "");
        root.put("emptyList", List.of());

        assertThat(check(root, leaf("seats", "eq", 50))).isTrue();
        assertThat(check(root, leaf("seats", "eq", "50"))).isTrue(); // numeric coercion
        assertThat(check(root, leaf("seats", "ne", 51))).isTrue();
        assertThat(check(root, leaf("seats", "gt", 10))).isTrue();
        assertThat(check(root, leaf("seats", "gte", 50))).isTrue();
        assertThat(check(root, leaf("seats", "lt", 100))).isTrue();
        assertThat(check(root, leaf("seats", "lte", 50))).isTrue();
        assertThat(check(root, leaf("plan", "in", List.of("FREE", "PRO")))).isTrue();
        assertThat(check(root, leaf("plan", "notIn", List.of("ENTERPRISE")))).isTrue();
        assertThat(check(root, leaf("name", "contains", "云服务"))).isTrue();
        assertThat(check(root, leaf("tags", "contains", "risk"))).isTrue();
        assertThat(check(root, leaf("email", "matches", "^[^@\\s]+@[^@\\s]+\\.[a-z]{2,}$"))).isTrue();
        assertThat(check(root, leaf("plan", "exists", null))).isTrue();
        assertThat(check(root, leaf("missing", "exists", null))).isFalse();
        assertThat(check(root, leaf("blank", "empty", null))).isTrue();
        assertThat(check(root, leaf("emptyList", "empty", null))).isTrue();
        assertThat(check(root, leaf("enabled", "eq", true))).isTrue();
        assertThat(check(root, leaf("enabled", "eq", "true"))).isTrue();
    }

    @Test
    void comparisonOperatorsAreTypeAware() {
        Map<String, Object> root = Map.of("seats", 50, "plan", "PRO", "flag", false);
        assertThat(check(root, leaf("seats", "gt", "10"))).isTrue();
        assertThat(check(root, leaf("plan", "gt", "AAA"))).isTrue(); // lexical fallback
        assertThat(check(root, leaf("seats", "lt", "abc"))).isFalse(); // not comparable
        assertThat(check(root, leaf("flag", "eq", false))).isTrue();
        assertThat(check(root, leaf("flag", "eq", "false"))).isTrue();
    }

    @Test
    void dotPathsAndListIndexesResolve() {
        Map<String, Object> root = Map.of(
                "attributes", Map.of("industry", "gambling"),
                "records", List.of(Map.of("plan", "PRO")));
        assertThat(PathResolver.resolve(root, "attributes.industry")).isEqualTo("gambling");
        assertThat(PathResolver.resolve(root, "records.0.plan")).isEqualTo("PRO");
        assertThat(PathResolver.resolve(root, "records.9.plan")).isNull();
        assertThat(PathResolver.resolve(root, "attributes.missing")).isNull();
    }

    @Test
    void unsupportedOperatorAndMalformedLeafAreRejected() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> Operator.from("between"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> ConditionParser.parse(Map.of("op", "eq")))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> ConditionParser.parse(leaf("plan", "eq", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean check(Map<String, Object> root, Map<String, Object> leaf) {
        return ConditionParser.parse(leaf).evaluate(root).matched();
    }

    private static Map<String, Object> leaf(String field, String op, Object value) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("field", field);
        map.put("op", op);
        if (value != null) {
            map.put("value", value);
        }
        return map;
    }

    private static Map<String, Object> all(Object... conditions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("all", List.of(conditions));
        return map;
    }

    private static Map<String, Object> any(Object... conditions) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("any", List.of(conditions));
        return map;
    }

    private static Map<String, Object> not(Object condition) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("not", condition);
        return map;
    }
}
