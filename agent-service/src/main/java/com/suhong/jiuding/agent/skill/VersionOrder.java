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
package com.suhong.jiuding.agent.skill;

import java.util.Comparator;

/**
 * Semantic-ish version ordering used by the skill version API.
 *
 * <p>Dotted numeric segments compare numerically ({@code 1.0.10 > 1.0.9}), any
 * non-numeric segment falls back to string ordering, and the full string is the
 * final tie-breaker. Deterministic and dependency-free.
 */
public final class VersionOrder {

    private VersionOrder() {
    }

    /** Descending comparator (newest version first). */
    public static final Comparator<String> DESCENDING = (left, right) -> compare(right, left);

    public static int compare(String left, String right) {
        String a = left == null ? "" : left.trim();
        String b = right == null ? "" : right.trim();
        String[] leftParts = a.split("[.\\-_+]");
        String[] rightParts = b.split("[.\\-_+]");
        int length = Math.max(leftParts.length, rightParts.length);
        for (int i = 0; i < length; i++) {
            String leftPart = i < leftParts.length ? leftParts[i] : "";
            String rightPart = i < rightParts.length ? rightParts[i] : "";
            Long leftNumber = number(leftPart);
            Long rightNumber = number(rightPart);
            int result;
            if (leftNumber != null && rightNumber != null) {
                result = Long.compare(leftNumber, rightNumber);
            } else {
                result = leftPart.compareToIgnoreCase(rightPart);
            }
            if (result != 0) {
                return result;
            }
        }
        return a.compareToIgnoreCase(b);
    }

    private static Long number(String part) {
        if (part == null || part.isEmpty() || part.length() > 18) {
            return null;
        }
        for (int i = 0; i < part.length(); i++) {
            if (!Character.isDigit(part.charAt(i))) {
                return null;
            }
        }
        try {
            return Long.parseLong(part);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
