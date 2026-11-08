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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * YAML frontmatter of a {@code SKILL.md} file (docs/04-skill-spec.md).
 *
 * <p>Required: {@code id/name/version/type/description}; optional:
 * {@code triggers/tools/tags}.
 */
public record SkillManifest(String id, String name, String version, String type, String description,
                            List<String> triggers, List<String> tools, List<String> tags) {

    public static final Set<String> TYPES = Set.of("INTAKE", "RISK", "APPROVAL", "PROVISIONING", "RATING");

    public SkillManifest {
        triggers = triggers == null ? List.of() : List.copyOf(triggers);
        tools = tools == null ? List.of() : List.copyOf(tools);
        tags = tags == null ? List.of() : List.copyOf(tags);
    }

    public static SkillManifest from(Map<String, Object> frontmatter) {
        String id = require(frontmatter, "id");
        String name = require(frontmatter, "name");
        String version = require(frontmatter, "version");
        String type = require(frontmatter, "type").toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new IllegalArgumentException("invalid skill type: " + type + " (expected " + TYPES + ")");
        }
        String description = require(frontmatter, "description");
        return new SkillManifest(id, name, version, type, description,
                strings(frontmatter.get("triggers")),
                strings(frontmatter.get("tools")),
                strings(frontmatter.get("tags")));
    }

    private static String require(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value == null || String.valueOf(value).isBlank()) {
            throw new IllegalArgumentException("frontmatter missing required field: " + key);
        }
        return String.valueOf(value).trim();
    }

    public static List<String> strings(Object value) {
        if (value == null) {
            return List.of();
        }
        if (value instanceof List<?> list) {
            List<String> result = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item != null) {
                    result.add(String.valueOf(item));
                }
            }
            return result;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? List.of() : List.of(text);
    }
}
