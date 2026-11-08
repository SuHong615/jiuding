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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One row of {@code skill_packages} (agent_db V2, docs/15 §2.1).
 *
 * <p>A skill id may own several versions; only {@code source=UPLOADED},
 * {@code status=APPROVED} and {@code enabled=true} rows are loaded into the
 * runtime registry (built-ins are loaded from the {@code skills/} directory and
 * mirrored into this table as {@code BUILTIN}/{@code APPROVED}).
 */
public record SkillPackage(String id, String version, String source, String status,
                           Map<String, Object> manifest, String instructions, String rulesYaml,
                           String reviewComment, String reviewedBy, String reviewedAt,
                           String createdBy, String createdAt, String updatedAt, boolean enabled) {

    public static final String SOURCE_BUILTIN = "BUILTIN";
    public static final String SOURCE_UPLOADED = "UPLOADED";

    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_PENDING_REVIEW = "PENDING_REVIEW";
    public static final String STATUS_APPROVED = "APPROVED";
    public static final String STATUS_REJECTED = "REJECTED";
    public static final String STATUS_DISABLED = "DISABLED";

    public static final List<String> SOURCES = List.of(SOURCE_BUILTIN, SOURCE_UPLOADED);
    public static final List<String> STATUSES = List.of(STATUS_DRAFT, STATUS_PENDING_REVIEW,
            STATUS_APPROVED, STATUS_REJECTED, STATUS_DISABLED);

    public SkillPackage {
        manifest = manifest == null ? Map.of() : Map.copyOf(manifest);
    }

    public boolean builtin() {
        return SOURCE_BUILTIN.equals(source);
    }

    public boolean editable() {
        return SOURCE_UPLOADED.equals(source)
                && (STATUS_DRAFT.equals(status) || STATUS_REJECTED.equals(status));
    }

    /** Manifest view with strongly-typed fields and a defaulted type/version. */
    public SkillManifest toManifest() {
        Map<String, Object> map = new LinkedHashMap<>(manifest);
        map.putIfAbsent("id", id);
        map.putIfAbsent("version", version);
        map.putIfAbsent("name", id);
        Object type = map.get("type");
        map.put("type", type == null ? "RISK" : String.valueOf(type).toUpperCase(Locale.ROOT));
        if (map.get("description") == null) {
            map.put("description", id);
        }
        return SkillManifest.from(map);
    }

    public static String normalizeSource(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT);
        return SOURCES.contains(upper) ? upper : null;
    }

    public static String normalizeStatus(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String upper = value.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return STATUSES.contains(upper) ? upper : null;
    }
}
