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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.agent.skill.SkillRunResult;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Thread-confined recorder that turns the scripted ReAct execution into the
 * persisted {@code steps[]} timeline.
 *
 * <p>The scripted responder and the tool functions run on the same (blocking)
 * thread inside the AgentScope loop, so a plain mutable recorder is sufficient
 * and avoids instrumenting AgentScope internals.
 */
public final class RunRecorder {

    private final ObjectMapper objectMapper;
    private final String runId;
    private final List<Step> steps = new ArrayList<>();
    private String pendingThought;
    private SkillRunResult lastSkillResult;

    public RunRecorder(ObjectMapper objectMapper, String runId) {
        this.objectMapper = objectMapper;
        this.runId = runId;
    }

    public String runId() {
        return runId;
    }

    /** Record the decision of the model to call a tool (step N). */
    public synchronized void beginStep(String thought, String action, Map<String, Object> actionInput) {
        this.pendingThought = thought;
        steps.add(new Step(steps.size() + 1, thought, action, toJson(actionInput), null));
    }

    /** Attach the observation returned by the tool to the current step. */
    public synchronized void observation(String observation) {
        if (steps.isEmpty()) {
            return;
        }
        int last = steps.size() - 1;
        Step current = steps.get(last);
        steps.set(last, new Step(current.index(), current.thought(), current.action(),
                current.actionInput(), truncate(observation, 4000)));
    }

    /** Record the final answer as the closing step. */
    public synchronized void finalizeStep(Map<String, Object> output) {
        steps.add(new Step(steps.size() + 1,
                "根据工具观测结果生成结构化输出（" + pendingThought + "）",
                "finalize", null, truncate(toJson(output), 4000)));
    }

    public synchronized void recordSkillResult(SkillRunResult result) {
        this.lastSkillResult = result;
    }

    public synchronized SkillRunResult lastSkillResult() {
        return lastSkillResult;
    }

    public synchronized List<Step> steps() {
        return List.copyOf(steps);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max) + "...";
    }

    /** Convenience for tools that need to build ordered JSON payloads. */
    public static Map<String, Object> orderedMap() {
        return new LinkedHashMap<>();
    }
}
