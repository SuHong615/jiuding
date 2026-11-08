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
package com.suhong.jiuding.agent.agent;

import com.suhong.jiuding.agent.skill.SkillRunResult;

import java.util.Map;
import java.util.function.Function;

/**
 * Deterministic script for one agent type.
 *
 * <p>Each script encodes the ReAct shape used by the platform: first call one
 * tool ({@code tenant.get} or {@code skill.run}), then synthesize a structured
 * JSON answer from the observation. Because the responder is a pure function,
 * the agent is reproducible and testable without any LLM.
 */
public record AgentScript(String agentType, String skillId, String thought,
                          Function<ScriptContext, ToolInvocation> toolSelector,
                          Finalizer finalizer) {

    /** Request-scoped execution context passed to the script. */
    public record ScriptContext(Map<String, Object> input, String tenantId) {
    }

    /** Tool the first ReAct iteration should call. */
    public record ToolInvocation(String tool, Map<String, Object> input) {
        public ToolInvocation {
            input = input == null ? Map.of() : java.util.Collections.unmodifiableMap(input);
        }
    }

    /** Produces the final structured output from the tool observation. */
    public interface Finalizer {
        Map<String, Object> apply(ScriptContext context, Map<String, Object> observation,
                                  SkillRunResult skillResult);
    }
}
