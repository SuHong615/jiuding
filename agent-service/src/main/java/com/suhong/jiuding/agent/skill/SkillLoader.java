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
import com.suhong.jiuding.agent.rule.RuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Loads skill packages from {@code tenancy.skills-dir} (env
 * {@code TENANCY_SKILLS_DIR}, default {@code ../skills}).
 *
 * <p>Only YAML is parsed (through the snakeyaml already on the classpath) and no
 * code is ever executed from a skill package — skills are declarative data.
 */
@Component
public class SkillLoader {

    private static final Logger log = LoggerFactory.getLogger(SkillLoader.class);
    private static final String SKILL_FILE = "SKILL.md";
    private static final String RULES_FILE = "rules.yaml";

    private final TenancyAgentProperties properties;

    public SkillLoader(TenancyAgentProperties properties) {
        this.properties = properties;
    }

    /** Load every valid skill package under the configured directory. */
    public List<SkillDefinition> loadAll() {
        Path root = properties.resolveSkillsDir();
        List<SkillDefinition> definitions = new ArrayList<>();
        if (!Files.isDirectory(root)) {
            log.warn("skills directory does not exist: {}", root);
            return definitions;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            List<Path> directories = new ArrayList<>();
            stream.forEach(directories::add);
            directories.sort(Comparator.comparing(path -> path.getFileName().toString()));
            for (Path directory : directories) {
                if (!Files.isDirectory(directory) || !Files.exists(directory.resolve(SKILL_FILE))) {
                    continue;
                }
                try {
                    definitions.add(load(directory));
                } catch (Exception e) {
                    log.warn("skipping invalid skill package {}: {}", directory.getFileName(), e.toString());
                }
            }
        } catch (IOException e) {
            log.warn("failed to scan skills directory {}: {}", root, e.toString());
        }
        log.info("loaded {} skill package(s) from {}", definitions.size(), root);
        return definitions;
    }

    /** Load one skill directory. */
    public SkillDefinition load(Path directory) throws IOException {
        Path skillFile = directory.resolve(SKILL_FILE);
        String raw = Files.readString(skillFile, StandardCharsets.UTF_8);
        ParsedMarkdown parsed = parseMarkdown(raw);
        Map<String, Object> frontmatter = parseYamlMap(parsed.frontmatter(), "frontmatter of " + skillFile);
        SkillManifest manifest = SkillManifest.from(frontmatter);

        Path rulesFile = directory.resolve(RULES_FILE);
        RuleSet rules;
        String rulesRaw = null;
        if (Files.exists(rulesFile)) {
            rulesRaw = Files.readString(rulesFile, StandardCharsets.UTF_8);
            Map<String, Object> rulesMap = parseYamlMap(rulesRaw, "rules of " + skillFile);
            rules = RuleSet.from(rulesMap, manifest.id(), manifest.version());
        } else {
            rules = RuleSet.empty(manifest.id(), manifest.version());
        }
        return new SkillDefinition(manifest, parsed.instructions(), rules,
                directory.toAbsolutePath().normalize().toString(), raw, rulesRaw);
    }

    /** Split YAML frontmatter from the markdown instruction body. */
    public static ParsedMarkdown parseMarkdown(String content) {
        String normalized = content.replace("\r\n", "\n").replace("\r", "\n");
        String[] lines = normalized.split("\n", -1);
        if (lines.length < 3 || !"---".equals(lines[0].trim())) {
            throw new IllegalArgumentException("SKILL.md must start with a YAML frontmatter block (---)");
        }
        int closing = -1;
        for (int i = 1; i < lines.length; i++) {
            if ("---".equals(lines[i].trim())) {
                closing = i;
                break;
            }
        }
        if (closing < 0) {
            throw new IllegalArgumentException("SKILL.md frontmatter is not closed with ---");
        }
        String yaml = String.join("\n", Arrays.copyOfRange(lines, 1, closing));
        String body = String.join("\n", Arrays.copyOfRange(lines, closing + 1, lines.length)).trim();
        return new ParsedMarkdown(yaml, body);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseYamlMap(String yamlText, String what) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(50);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        Object loaded = yaml.load(yamlText);
        if (loaded == null) {
            return new LinkedHashMap<>();
        }
        if (!(loaded instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(what + " must be a YAML object");
        }
        return (Map<String, Object>) map;
    }

    /** Parse SKILL.md frontmatter YAML into a validated manifest. */
    public static SkillManifest parseManifest(String frontmatterYaml) {
        return SkillManifest.from(parseYamlMap(frontmatterYaml, "frontmatter"));
    }

    /** Parse rules.yaml text into a rule set (throws on unknown operators/actions). */
    public static RuleSet parseRuleSet(String rulesYaml, String skillId, String version) {
        if (rulesYaml == null || rulesYaml.isBlank()) {
            return RuleSet.empty(skillId, version);
        }
        return RuleSet.from(parseYamlMap(rulesYaml, "rules"), skillId, version);
    }

    /** Build a definition from already-parsed parts (upload validation / DB reload). */
    public static SkillDefinition definitionOf(SkillManifest manifest, String instructions,
                                               String rulesYaml, String path, String rawManifest) {
        RuleSet rules = parseRuleSet(rulesYaml, manifest.id(), manifest.version());
        return new SkillDefinition(manifest, instructions, rules, path, rawManifest, rulesYaml);
    }

    /** Frontmatter map persisted in {@code skill_packages.manifest_json}. */
    public static Map<String, Object> manifestMap(SkillManifest manifest) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("id", manifest.id());
        map.put("name", manifest.name());
        map.put("version", manifest.version());
        map.put("type", manifest.type());
        map.put("description", manifest.description());
        map.put("triggers", manifest.triggers());
        map.put("tools", manifest.tools());
        map.put("tags", manifest.tags());
        return map;
    }

    /** Rebuild a runtime definition from a persisted skill package row. */
    public static SkillDefinition fromPackage(SkillPackage pkg) {
        SkillManifest manifest = pkg.toManifest();
        String path = "db://skill_packages/" + pkg.id() + "@" + pkg.version();
        return definitionOf(manifest, pkg.instructions(), pkg.rulesYaml(), path, null);
    }

    /** Frontmatter + markdown body pair. */
    public record ParsedMarkdown(String frontmatter, String instructions) {
    }
}
