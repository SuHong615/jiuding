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

import java.util.List;

/**
 * List projection of a skill package (docs/02 §4 + docs/15 §2.2).
 *
 * <p>Built-ins are mirrored from {@code skills/} and are always
 * {@code source=BUILTIN, status=APPROVED, enabled=true}; uploaded packages carry
 * their review state.
 */
public record SkillView(String id, String name, String version, String type, String description,
                        List<String> triggers, List<String> tools, boolean enabled, String path,
                        String source, String status, String reviewComment, String reviewedBy,
                        String reviewedAt, String createdBy, String createdAt, String updatedAt) {

    public static SkillView of(SkillDefinition definition) {
        return of(definition, SkillPackage.SOURCE_BUILTIN, SkillPackage.STATUS_APPROVED, true);
    }

    public static SkillView of(SkillDefinition definition, String source, String status, boolean enabled) {
        SkillManifest manifest = definition.manifest();
        return new SkillView(manifest.id(), manifest.name(), manifest.version(), manifest.type(),
                manifest.description(), manifest.triggers(), manifest.tools(), enabled, definition.path(),
                source, status, null, null, null, null, null, null);
    }

    public static SkillView of(SkillRegistry.LoadedSkill loaded) {
        SkillDefinition definition = loaded.definition();
        SkillManifest manifest = definition.manifest();
        return new SkillView(manifest.id(), manifest.name(), manifest.version(), manifest.type(),
                manifest.description(), manifest.triggers(), manifest.tools(), loaded.enabled(),
                definition.path(), loaded.source(), loaded.status(), loaded.reviewComment(),
                loaded.reviewedBy(), loaded.reviewedAt(), loaded.createdBy(), loaded.createdAt(),
                loaded.updatedAt());
    }

    public static SkillView of(SkillPackage pkg) {
        SkillManifest manifest = pkg.toManifest();
        return new SkillView(manifest.id(), manifest.name(), manifest.version(), manifest.type(),
                manifest.description(), manifest.triggers(), manifest.tools(), pkg.enabled(),
                "db://skill_packages/" + pkg.id() + "@" + pkg.version(), pkg.source(), pkg.status(),
                pkg.reviewComment(), pkg.reviewedBy(), pkg.reviewedAt(), pkg.createdBy(),
                pkg.createdAt(), pkg.updatedAt());
    }
}
