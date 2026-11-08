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

import com.suhong.jiuding.agent.rule.RuleAction;
import com.suhong.jiuding.agent.rule.RuleSet;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Detail projection: manifest + instructions + parsed rules + review state
 * (docs/15 §2.2). Keeps the parsed {@link RuleSet} under {@code rules} for
 * clients that already consume it, and exposes a frontend-friendly
 * {@code manifest} object whose {@code rules} array is easy to render.
 */
public record SkillDetail(String id, String name, String version, String type, String description,
                          List<String> triggers, List<String> tools, List<String> tags,
                          boolean enabled, String path, String instructions,
                          RuleSet rules, String rawManifest, String loadedAt,
                          String source, String status, String reviewComment, String reviewedBy,
                          String reviewedAt, String createdBy, String createdAt, String updatedAt,
                          Map<String, Object> manifest, String rulesYaml) {

    public static SkillDetail of(SkillDefinition definition, String loadedAt) {
        return of(definition, loadedAt, SkillPackage.SOURCE_BUILTIN, SkillPackage.STATUS_APPROVED,
                true, null, null, null, null, null, null);
    }

    public static SkillDetail of(SkillRegistry.LoadedSkill loaded, String loadedAt) {
        return of(loaded.definition(), loadedAt, loaded.source(), loaded.status(), loaded.enabled(),
                loaded.reviewComment(), loaded.reviewedBy(), loaded.reviewedAt(),
                loaded.createdBy(), loaded.createdAt(), loaded.updatedAt());
    }

    public static SkillDetail of(SkillPackage pkg, String loadedAt) {
        SkillDefinition definition = SkillLoader.fromPackage(pkg);
        return of(definition, loadedAt, pkg.source(), pkg.status(), pkg.enabled(),
                pkg.reviewComment(), pkg.reviewedBy(), pkg.reviewedAt(), pkg.createdBy(),
                pkg.createdAt(), pkg.updatedAt());
    }

    public static SkillDetail of(SkillDefinition definition, String loadedAt, String source,
                                 String status, boolean enabled, String reviewComment,
                                 String reviewedBy, String reviewedAt, String createdBy,
                                 String createdAt, String updatedAt) {
        SkillManifest manifest = definition.manifest();
        Map<String, Object> manifestJson = SkillLoader.manifestMap(manifest);
        manifestJson.put("rules", ruleList(definition.rules()));
        String raw = definition.rawManifest() == null
                ? writeFallbackRaw(manifest, definition.instructions()) : definition.rawManifest();
        return new SkillDetail(manifest.id(), manifest.name(), manifest.version(), manifest.type(),
                manifest.description(), manifest.triggers(), manifest.tools(), manifest.tags(),
                enabled, definition.path(), definition.instructions(), definition.rules(), raw,
                loadedAt, source, status, reviewComment, reviewedBy, reviewedAt, createdBy,
                createdAt, updatedAt, manifestJson,
                definition.rawRulesYaml() == null ? rawRulesYaml(definition.rules()) : definition.rawRulesYaml());
    }

    /** Serialize the parsed rule set back to a readable YAML-ish body for editing. */
    private static String rawRulesYaml(RuleSet rules) {
        if (rules == null || rules.rules().isEmpty()) {
            return null;
        }
        StringBuilder yaml = new StringBuilder();
        yaml.append("skillId: ").append(rules.skillId()).append('\n');
        yaml.append("version: ").append(rules.version()).append('\n');
        yaml.append("initialScore: ").append(rules.initialScore()).append('\n');
        yaml.append("rules:\n");
        for (com.suhong.jiuding.agent.rule.Rule rule : rules.rules()) {
            yaml.append("  - id: ").append(rule.id()).append('\n');
            if (rule.description() != null) {
                yaml.append("    description: ").append(rule.description()).append('\n');
            }
            yaml.append("    severity: ").append(rule.severity()).append('\n');
            yaml.append("    when: ").append(rule.when().describe()).append('\n');
            yaml.append("    then:\n");
            for (RuleAction action : rule.then()) {
                yaml.append("      - { action: ").append(action.action());
                if (action.value() != null) {
                    yaml.append(", value: ").append(action.value());
                }
                if (action.message() != null) {
                    yaml.append(", message: \"").append(action.message()).append('"');
                }
                if (action.severity() != null) {
                    yaml.append(", severity: ").append(action.severity());
                }
                yaml.append(" }\n");
            }
        }
        return yaml.toString();
    }

    private static String writeFallbackRaw(SkillManifest manifest, String instructions) {
        return "---\nid: " + manifest.id() + "\nname: " + manifest.name()
                + "\nversion: " + manifest.version() + "\ntype: " + manifest.type()
                + "\ndescription: " + manifest.description() + "\n---\n" + (instructions == null ? "" : instructions);
    }

    /** Frontend-friendly rule list: {@code [{id, description, severity, when, then}]}. */
    public static List<Map<String, Object>> ruleList(RuleSet rules) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (rules == null) {
            return list;
        }
        for (com.suhong.jiuding.agent.rule.Rule rule : rules.rules()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", rule.id());
            item.put("description", rule.description());
            item.put("severity", rule.severity());
            item.put("when", rule.when().describe());
            List<Map<String, Object>> actions = new ArrayList<>();
            for (RuleAction action : rule.then()) {
                Map<String, Object> actionJson = new LinkedHashMap<>();
                actionJson.put("action", action.action());
                actionJson.put("value", action.value());
                actionJson.put("message", action.message());
                actionJson.put("severity", action.severity());
                actions.add(actionJson);
            }
            item.put("then", actions);
            list.add(item);
        }
        return list;
    }
}
