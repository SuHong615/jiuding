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
 * Result of one deterministic skill execution (docs/02 §4).
 *
 * <p>{@code verdict}: {@code PASS|WARN|FAIL}; {@code decision}:
 * {@code AUTO_APPROVE|NEEDS_REVIEW|REJECT|NONE}. The full trace is always
 * returned so every decision is auditable without calling the model again.
 */
public record SkillRunResult(String skillId, String runId, String verdict, int score,
                             String decision, List<Finding> findings, List<Action> actions,
                             List<TraceItem> trace, long durationMs, String model) {

    public SkillRunResult {
        findings = findings == null ? List.of() : List.copyOf(findings);
        actions = actions == null ? List.of() : List.copyOf(actions);
        trace = trace == null ? List.of() : List.copyOf(trace);
    }
}
