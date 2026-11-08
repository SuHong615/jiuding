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

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * The 10 operators of the rule DSL (docs/04-skill-spec.md).
 *
 * <p>Type semantics are deliberately simple and deterministic:
 * numbers compare numerically (BigDecimal), booleans compare as booleans,
 * everything else compares as strings. No reflection, no scripting.
 */
public enum Operator {

    EQ("eq"),
    NE("ne"),
    GT("gt"),
    GTE("gte"),
    LT("lt"),
    LTE("lte"),
    IN("in"),
    NOT_IN("notIn"),
    CONTAINS("contains"),
    MATCHES("matches"),
    EXISTS("exists"),
    EMPTY("empty");

    /** Matches cap from the spec: {@code matches} regex length must stay <= 256. */
    public static final int MAX_REGEX_LENGTH = 256;

    private final String wireName;

    Operator(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    /** Parse a YAML operator token ({@code notIn}, {@code not_in}, {@code NOT-IN} are equivalent). */
    public static Operator from(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("operator is required");
        }
        String normalized = raw.trim().replace("-", "").replace("_", "")
                .toLowerCase(Locale.ROOT);
        for (Operator operator : values()) {
            String candidate = operator.wireName.replace("-", "").replace("_", "")
                    .toLowerCase(Locale.ROOT);
            if (candidate.equals(normalized)) {
                return operator;
            }
        }
        throw new IllegalArgumentException("unknown operator: " + raw);
    }

    public boolean test(Object actual, Object expected) {
        return switch (this) {
            case EQ -> equalsValue(actual, expected);
            case NE -> !equalsValue(actual, expected);
            case GT -> compareResult(actual, expected, result -> result > 0);
            case GTE -> compareResult(actual, expected, result -> result >= 0);
            case LT -> compareResult(actual, expected, result -> result < 0);
            case LTE -> compareResult(actual, expected, result -> result <= 0);
            case IN -> in(actual, expected);
            case NOT_IN -> !in(actual, expected);
            case CONTAINS -> contains(actual, expected);
            case MATCHES -> matches(actual, expected);
            case EXISTS -> exists(actual);
            case EMPTY -> !exists(actual);
        };
    }

    private static boolean equalsValue(Object actual, Object expected) {
        if (actual == null && expected == null) {
            return true;
        }
        if (actual == null || expected == null) {
            return false;
        }
        BigDecimal left = number(actual);
        BigDecimal right = number(expected);
        if (left != null && right != null) {
            return left.compareTo(right) == 0;
        }
        if (actual instanceof Boolean || expected instanceof Boolean) {
            Boolean leftBool = bool(actual);
            Boolean rightBool = bool(expected);
            if (leftBool != null && rightBool != null) {
                return leftBool.equals(rightBool);
            }
        }
        return Objects.equals(actual, expected) || String.valueOf(actual).equals(String.valueOf(expected));
    }

    private static boolean compareResult(Object actual, Object expected,
                                         java.util.function.IntPredicate predicate) {
        Integer result = compare(actual, expected);
        return result != null && predicate.test(result);
    }

    /** @return the comparison result, or {@code null} when the values are not comparable. */
    private static Integer compare(Object actual, Object expected) {
        if (actual == null || expected == null) {
            return null;
        }
        BigDecimal left = number(actual);
        BigDecimal right = number(expected);
        if (left != null && right != null) {
            return left.compareTo(right);
        }
        if (actual instanceof String s && expected instanceof String e) {
            return s.compareTo(e);
        }
        return null;
    }

    private static boolean in(Object actual, Object expected) {
        if (expected instanceof Collection<?> values) {
            return values.stream().anyMatch(candidate -> equalsValue(actual, candidate));
        }
        if (expected instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(candidate -> equalsValue(actual, candidate));
        }
        throw new IllegalArgumentException("'in' requires a collection value");
    }

    private static boolean contains(Object actual, Object expected) {
        if (actual instanceof String text) {
            return expected != null && text.contains(String.valueOf(expected));
        }
        if (actual instanceof Collection<?> values) {
            return values.stream().anyMatch(candidate -> equalsValue(candidate, expected));
        }
        if (actual instanceof Map<?, ?> map) {
            return expected != null && map.containsKey(String.valueOf(expected));
        }
        return false;
    }

    private static boolean matches(Object actual, Object expected) {
        if (!(actual instanceof String text) || expected == null) {
            return false;
        }
        String regex = String.valueOf(expected);
        if (regex.length() > MAX_REGEX_LENGTH) {
            throw new IllegalArgumentException("regex exceeds " + MAX_REGEX_LENGTH + " chars");
        }
        try {
            return Pattern.compile(regex).matcher(text).matches();
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException("invalid regex: " + e.getDescription(), e);
        }
    }

    public static boolean exists(Object value) {
        if (value == null) {
            return false;
        }
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> values) {
            return !values.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return true;
    }

    public static BigDecimal number(Object value) {
        if (value instanceof BigDecimal decimal) {
            return decimal;
        }
        if (value instanceof Number number) {
            return new BigDecimal(number.toString());
        }
        if (value instanceof String text) {
            try {
                return new BigDecimal(text.trim());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }
        return null;
    }

    static Boolean bool(Object value) {
        if (value instanceof Boolean b) {
            return b;
        }
        if ("true".equalsIgnoreCase(String.valueOf(value))) {
            return true;
        }
        if ("false".equalsIgnoreCase(String.valueOf(value))) {
            return false;
        }
        return null;
    }
}
