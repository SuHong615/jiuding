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
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Composable rule condition tree: {@code all}, {@code any}, {@code not} and a
 * leaf {@code {field, op, value}}. Nesting is unrestricted.
 *
 * <p>Evaluation returns both the boolean outcome and a human-readable reason so
 * every rule execution can be traced (docs/04: trace 每条规则 matched+reason).
 */
public sealed interface Condition permits Condition.All, Condition.Any, Condition.Not, Condition.Leaf {

    Evaluation evaluate(Map<String, Object> root);

    String describe();

    /** Outcome + explanation pair. */
    record Evaluation(boolean matched, String reason) {
    }

    /** Conjunction: empty list is vacuously true. */
    record All(List<Condition> conditions) implements Condition {
        public All {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }

        @Override
        public Evaluation evaluate(Map<String, Object> root) {
            for (Condition condition : conditions) {
                Evaluation evaluation = condition.evaluate(root);
                if (!evaluation.matched()) {
                    return new Evaluation(false, "all: " + evaluation.reason());
                }
            }
            return new Evaluation(true, "all: " + conditions.size() + " condition(s) satisfied");
        }

        @Override
        public String describe() {
            return "all[" + conditions.stream().map(Condition::describe)
                    .collect(Collectors.joining(" && ")) + "]";
        }
    }

    /** Disjunction: empty list is vacuously false. */
    record Any(List<Condition> conditions) implements Condition {
        public Any {
            conditions = conditions == null ? List.of() : List.copyOf(conditions);
        }

        @Override
        public Evaluation evaluate(Map<String, Object> root) {
            if (conditions.isEmpty()) {
                return new Evaluation(false, "any: no conditions configured");
            }
            String first = null;
            for (Condition condition : conditions) {
                Evaluation evaluation = condition.evaluate(root);
                if (evaluation.matched()) {
                    return new Evaluation(true, "any: " + evaluation.reason());
                }
                if (first == null) {
                    first = evaluation.reason();
                }
            }
            return new Evaluation(false, "any: " + first);
        }

        @Override
        public String describe() {
            return "any[" + conditions.stream().map(Condition::describe)
                    .collect(Collectors.joining(" || ")) + "]";
        }
    }

    /** Negation. */
    record Not(Condition condition) implements Condition {
        @Override
        public Evaluation evaluate(Map<String, Object> root) {
            Evaluation evaluation = condition.evaluate(root);
            return new Evaluation(!evaluation.matched(), "not(" + evaluation.reason() + ")");
        }

        @Override
        public String describe() {
            return "not(" + condition.describe() + ")";
        }
    }

    /** Leaf comparison {@code field op value}. */
    record Leaf(String field, Operator op, Object value) implements Condition {

        @Override
        public Evaluation evaluate(Map<String, Object> root) {
            Object actual = PathResolver.resolve(root, field);
            boolean matched = op.test(actual, value);
            String reason;
            if (op == Operator.EXISTS || op == Operator.EMPTY) {
                reason = field + " " + op.wireName() + " -> " + safe(actual) + " = " + matched;
            } else {
                reason = field + " " + op.wireName() + " " + safe(value)
                        + " (actual=" + safe(actual) + ") = " + matched;
            }
            return new Evaluation(matched, reason);
        }

        @Override
        public String describe() {
            if (op == Operator.EXISTS || op == Operator.EMPTY) {
                return field + " " + op.wireName();
            }
            return field + " " + op.wireName() + " " + value;
        }

        private static String safe(Object value) {
            if (value == null) {
                return "null";
            }
            String text = String.valueOf(value);
            return text.length() > 120 ? text.substring(0, 120) + "..." : text;
        }
    }
}
