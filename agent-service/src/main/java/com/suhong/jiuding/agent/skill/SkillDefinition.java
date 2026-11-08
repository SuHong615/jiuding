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

import com.suhong.jiuding.agent.rule.RuleSet;

/**
 * One fully loaded skill package: parsed manifest, markdown instructions, the
 * deterministic rule set and the on-disk location. Raw texts are kept so the
 * skill-center detail/edit API can return them verbatim.
 */
public record SkillDefinition(SkillManifest manifest, String instructions, RuleSet rules,
                              String path, String rawManifest, String rawRulesYaml) {

    public SkillDefinition(SkillManifest manifest, String instructions, RuleSet rules,
                           String path, String rawManifest) {
        this(manifest, instructions, rules, path, rawManifest, null);
    }

    public String id() {
        return manifest.id();
    }

    public String type() {
        return manifest.type();
    }
}
