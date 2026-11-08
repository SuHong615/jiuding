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

/**
 * One entry of {@code GET /api/skills/{id}/versions} (docs/15 §12).
 *
 * <p>{@code latest} marks the version the runtime currently prefers: for
 * uploaded skills the newest {@code APPROVED && enabled} version, otherwise the
 * newest version overall. Built-in skills expose a single entry.
 */
public record SkillVersionView(String version, String status, boolean enabled, String source,
                               String reviewComment, String reviewedBy, String reviewedAt,
                               String createdBy, String createdAt, String updatedAt, boolean latest) {

    public static SkillVersionView of(SkillPackage pkg, boolean latest) {
        return new SkillVersionView(pkg.version(), pkg.status(), pkg.enabled(), pkg.source(),
                pkg.reviewComment(), pkg.reviewedBy(), pkg.reviewedAt(), pkg.createdBy(),
                pkg.createdAt(), pkg.updatedAt(), latest);
    }

    public static SkillVersionView of(SkillRegistry.LoadedSkill loaded, boolean latest) {
        return new SkillVersionView(loaded.definition().manifest().version(), loaded.status(),
                loaded.enabled(), loaded.source(), loaded.reviewComment(), loaded.reviewedBy(),
                loaded.reviewedAt(), loaded.createdBy(), loaded.createdAt(), loaded.updatedAt(), latest);
    }
}
