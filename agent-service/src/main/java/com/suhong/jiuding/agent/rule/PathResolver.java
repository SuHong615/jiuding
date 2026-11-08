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

/** Resolves dot-path field references ({@code attributes.industry}) against a nested map. */
public final class PathResolver {

    private PathResolver() {
    }

    /**
     * @return the value at the path, or {@code null} when any segment is missing.
     *         Numeric segments address list positions ({@code records.0.plan}).
     */
    public static Object resolve(Map<String, Object> root, String path) {
        if (root == null || path == null || path.isBlank()) {
            return null;
        }
        Object current = root;
        for (String segment : path.trim().split("\\.")) {
            if (current == null) {
                return null;
            }
            if (current instanceof Map<?, ?> map) {
                current = map.get(segment);
            } else if (current instanceof List<?> list) {
                try {
                    int index = Integer.parseInt(segment);
                    current = index >= 0 && index < list.size() ? list.get(index) : null;
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                return null;
            }
        }
        return current;
    }
}
