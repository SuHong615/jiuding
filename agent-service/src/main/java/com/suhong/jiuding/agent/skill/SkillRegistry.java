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

import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import com.suhong.jiuding.agent.repository.SkillPackageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Runtime skill registry (docs/15 §2.1).
 *
 * <p>Loads two sources and swaps them in atomically:
 * <ul>
 *   <li><b>built-ins</b>: every package under {@code tenancy.skills-dir}, always
 *       {@code BUILTIN/APPROVED/enabled} and read-only through the API;</li>
 *   <li><b>uploaded</b>: {@code skill_packages} rows with
 *       {@code source=UPLOADED, status=APPROVED, enabled=TRUE}.</li>
 * </ul>
 * A reload is triggered by startup and by every skill-center mutation
 * (approve / enable / disable / update), so agents and {@code skill.run} see
 * changes immediately without a restart.
 */
@Component
public class SkillRegistry {

    private static final Logger log = LoggerFactory.getLogger(SkillRegistry.class);

    private final SkillLoader loader;
    private final SkillPackageRepository packageRepository;
    private final TenancyAgentProperties properties;

    private volatile Map<String, LoadedSkill> skills = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    public SkillRegistry(SkillLoader loader, SkillPackageRepository packageRepository,
                         TenancyAgentProperties properties) {
        this.loader = loader;
        this.packageRepository = packageRepository;
        this.properties = properties;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        try {
            reload();
        } catch (Exception e) {
            log.warn("initial skill load failed: {}", e.toString());
        }
    }

    /** Rescan built-ins, reload approved uploads and mirror built-ins into the DB. */
    public synchronized ReloadResult reload() {
        Map<String, LoadedSkill> next = new LinkedHashMap<>();
        List<SkillDefinition> builtins = loader.loadAll();

        String now = Instant.now().toString();
        for (SkillDefinition definition : builtins) {
            next.put(definition.id(), new LoadedSkill(definition, SkillPackage.SOURCE_BUILTIN,
                    SkillPackage.STATUS_APPROVED, true, null, null, null, "system", now, now));
            try {
                packageRepository.upsertBuiltin(new SkillPackage(
                        definition.id(), definition.manifest().version(),
                        SkillPackage.SOURCE_BUILTIN, SkillPackage.STATUS_APPROVED,
                        SkillLoader.manifestMap(definition.manifest()), definition.instructions(),
                        definition.rawRulesYaml(), null, null, null, "system", now, now, true));
                packageRepository.deleteBuiltinVersionsExcept(definition.id(),
                        definition.manifest().version());
            } catch (Exception e) {
                log.warn("failed to mirror built-in skill {}: {}", definition.id(), e.toString());
            }
        }
        try {
            packageRepository.deleteBuiltinNotIn(next.keySet());
        } catch (Exception e) {
            log.warn("failed to clean stale built-in skills: {}", e.toString());
        }

        try {
            for (SkillPackage pkg : packageRepository.findLoadableUploaded()) {
                if (next.containsKey(pkg.id())) {
                    // built-in ids always win; upload conflicts are rejected earlier
                    continue;
                }
                try {
                    SkillDefinition definition = SkillLoader.fromPackage(pkg);
                    next.put(pkg.id(), new LoadedSkill(definition, pkg.source(), pkg.status(),
                            pkg.enabled(), pkg.reviewComment(), pkg.reviewedBy(), pkg.reviewedAt(),
                            pkg.createdBy(), pkg.createdAt(), pkg.updatedAt()));
                } catch (Exception e) {
                    log.warn("skipping invalid uploaded skill {}@{}: {}", pkg.id(), pkg.version(),
                            e.toString());
                }
            }
        } catch (Exception e) {
            log.warn("failed to load uploaded skills: {}", e.toString());
        }

        this.skills = Map.copyOf(next);
        this.loadedAt = Instant.now();

        List<SkillView> views = new ArrayList<>();
        for (LoadedSkill loaded : next.values()) {
            views.add(SkillView.of(loaded));
        }
        log.info("skill registry reloaded: {} skill(s) ({} built-in)", views.size(), builtins.size());
        return new ReloadResult(views.size(), views, properties.resolveSkillsDir().toString(),
                loadedAt.toString());
    }

    public List<SkillDefinition> list() {
        List<SkillDefinition> definitions = new ArrayList<>();
        for (LoadedSkill loaded : skills.values()) {
            definitions.add(loaded.definition());
        }
        return definitions;
    }

    public List<LoadedSkill> loadedSkills() {
        return List.copyOf(skills.values());
    }

    public Optional<LoadedSkill> findLoaded(String id) {
        return Optional.ofNullable(skills.get(id));
    }

    public Optional<SkillDefinition> find(String id) {
        LoadedSkill loaded = skills.get(id);
        return loaded == null ? Optional.empty() : Optional.of(loaded.definition());
    }

    public Set<String> ids() {
        return skills.keySet();
    }

    public int count() {
        return skills.size();
    }

    public Instant loadedAt() {
        return loadedAt;
    }

    /** A skill definition currently active in the runtime registry. */
    public record LoadedSkill(SkillDefinition definition, String source, String status,
                              boolean enabled, String reviewComment, String reviewedBy,
                              String reviewedAt, String createdBy, String createdAt,
                              String updatedAt) {
    }

    /** Hot-reload result returned by {@code POST /api/skills/reload}. */
    public record ReloadResult(int reloaded, List<SkillView> skills, String skillsDir, String loadedAt) {
    }
}
