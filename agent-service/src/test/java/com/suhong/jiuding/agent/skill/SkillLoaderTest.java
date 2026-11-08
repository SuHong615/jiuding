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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SKILL.md frontmatter + rules.yaml parsing and hot-load directory scanning. */
class SkillLoaderTest {

    @TempDir
    Path root;

    @Test
    void loadsManifestRulesAndInstructions() throws IOException {
        Path skillDir = Files.createDirectories(root.resolve("demo-skill"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                id: demo-skill
                name: 演示技能
                version: 1.2.3
                type: RISK
                description: 用于测试的技能包
                triggers: [tenant.submitted]
                tools: [tenant.get]
                tags: [demo]
                ---
                # 角色
                你是演示技能。
                """, StandardCharsets.UTF_8);
        Files.writeString(skillDir.resolve("rules.yaml"), """
                skillId: demo-skill
                version: 1.2.3
                initialScore: 5
                rules:
                  - id: R-DEMO-001
                    description: 计划为 PRO
                    severity: MEDIUM
                    when: { field: plan, op: eq, value: PRO }
                    then:
                      - { action: addScore, value: 10 }
                decision:
                  autoApproveMaxScore: 10
                  rejectMinScore: 90
                  riskLevels:
                    - { level: LOW, maxScore: 10 }
                    - { level: HIGH, maxScore: 100 }
                """, StandardCharsets.UTF_8);

        SkillLoader loader = loaderFor(root);
        List<SkillDefinition> definitions = loader.loadAll();

        assertThat(definitions).hasSize(1);
        SkillDefinition definition = definitions.get(0);
        assertThat(definition.manifest().id()).isEqualTo("demo-skill");
        assertThat(definition.manifest().type()).isEqualTo("RISK");
        assertThat(definition.manifest().triggers()).containsExactly("tenant.submitted");
        assertThat(definition.manifest().tools()).containsExactly("tenant.get");
        assertThat(definition.manifest().tags()).containsExactly("demo");
        assertThat(definition.instructions()).contains("# 角色");
        assertThat(definition.rules().rules()).hasSize(1);
        assertThat(definition.rules().initialScore()).isEqualTo(5);
        assertThat(definition.rules().decision().autoApproveMaxScore()).isEqualTo(10);
        assertThat(definition.rules().decision().riskLevels()).hasSize(2);
        assertThat(definition.path()).endsWith("demo-skill");
    }

    @Test
    void skillWithoutRulesYamlGetsEmptyRuleSet() throws IOException {
        Path skillDir = Files.createDirectories(root.resolve("plain"));
        Files.writeString(skillDir.resolve("SKILL.md"), """
                ---
                id: plain
                name: 无规则技能
                version: 1.0.0
                type: INTAKE
                description: 只有指令没有规则
                ---
                只输出 JSON。
                """, StandardCharsets.UTF_8);

        List<SkillDefinition> definitions = loaderFor(root).loadAll();
        assertThat(definitions).hasSize(1);
        assertThat(definitions.get(0).rules().hasRules()).isFalse();
        assertThat(definitions.get(0).rules().decision().emit()).isFalse();
    }

    @Test
    void invalidPackagesAreSkippedInsteadOfFailingTheScan() throws IOException {
        // missing required frontmatter field (description)
        Path broken = Files.createDirectories(root.resolve("broken"));
        Files.writeString(broken.resolve("SKILL.md"), """
                ---
                id: broken
                name: 缺字段
                version: 1.0.0
                type: RISK
                ---
                body
                """, StandardCharsets.UTF_8);

        // unknown operator in rules.yaml
        Path badRules = Files.createDirectories(root.resolve("bad-rules"));
        Files.writeString(badRules.resolve("SKILL.md"), """
                ---
                id: bad-rules
                name: 坏规则
                version: 1.0.0
                type: RISK
                description: operator 非法
                ---
                body
                """, StandardCharsets.UTF_8);
        Files.writeString(badRules.resolve("rules.yaml"), """
                skillId: bad-rules
                rules:
                  - id: R-BAD
                    when: { field: plan, op: between, value: 1 }
                    then: [ { action: addScore, value: 1 } ]
                """, StandardCharsets.UTF_8);

        // valid sibling to prove the scan continues
        Path good = Files.createDirectories(root.resolve("good"));
        Files.writeString(good.resolve("SKILL.md"), """
                ---
                id: good
                name: 正常技能
                version: 1.0.0
                type: APPROVAL
                description: 正常
                ---
                body
                """, StandardCharsets.UTF_8);

        List<SkillDefinition> definitions = loaderFor(root).loadAll();
        assertThat(definitions).extracting(d -> d.manifest().id()).containsExactly("good");
    }

    @Test
    void markdownWithoutFrontmatterIsRejected() {
        assertThatThrownBy(() -> SkillLoader.parseMarkdown("# 没有 frontmatter"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frontmatter");
        assertThatThrownBy(() -> SkillLoader.parseMarkdown("---\nid: a\n# not closed"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingDirectoryYieldsEmptyList() {
        TenancyAgentProperties properties = new TenancyAgentProperties();
        properties.setSkillsDir(root.resolve("does-not-exist").toString());
        assertThat(new SkillLoader(properties).loadAll()).isEmpty();
    }

    private SkillLoader loaderFor(Path directory) {
        TenancyAgentProperties properties = new TenancyAgentProperties();
        properties.setSkillsDir(directory.toString());
        return new SkillLoader(properties);
    }
}
