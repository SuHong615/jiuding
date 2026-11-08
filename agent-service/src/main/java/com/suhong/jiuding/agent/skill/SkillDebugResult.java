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
 * In-memory skill debug result (docs/15 §12).
 *
 * <p>Executed with {@link com.suhong.jiuding.agent.rule.RuleEngine} directly: nothing is
 * persisted (no {@code rule_hits} rows, no audit entries), which makes the debug
 * endpoint safe to call repeatedly while editing a skill.
 */
public record SkillDebugResult(String version, String decision, int score,
                               List<Action> actions, List<MatchedRule> matchedRules,
                               String evaluatedAt, long durationMs, List<String> notes) {

    public SkillDebugResult {
        actions = actions == null ? List.of() : List.copyOf(actions);
        matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** One evaluated rule; {@code matched=false} entries explain why nothing hit. */
    public record MatchedRule(String ruleId, String description, boolean matched, List<Action> actions) {
        public MatchedRule {
            actions = actions == null ? List.of() : List.copyOf(actions);
        }
    }
}
