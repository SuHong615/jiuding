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
package com.suhong.jiuding.agentkernel.runtime;

import io.agentscope.core.ReActAgent;
import io.agentscope.core.agent.RuntimeContext;
import io.agentscope.core.message.Msg;
import io.agentscope.core.model.Model;
import io.agentscope.core.tool.Toolkit;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Thin façade over AgentScope {@link ReActAgent}.
 *
 * <p>Keeps the rest of the platform free of AgentScope types: services talk to
 * {@link #run(Spec)} and receive a plain {@link Result}.
 */
public class AgentKernel {

    public Result run(Spec spec) {
        long start = System.currentTimeMillis();
        Toolkit toolkit = spec.toolkit() == null ? new Toolkit() : spec.toolkit();
        if (spec.tool() != null) {
            toolkit.registerTool(spec.tool());
        }
        if (spec.agentTool() != null) {
            toolkit.registerAgentTool(spec.agentTool());
        }
        try (ReActAgent agent = ReActAgent.builder()
                .name(spec.name())
                .sysPrompt(spec.systemPrompt())
                .model(spec.model())
                .toolkit(toolkit)
                .maxIters(spec.maxIterations())
                .build()) {

            RuntimeContext context = RuntimeContext.empty();
            Msg reply = agent.call(java.util.List.of(spec.input()), context)
                    .block(Duration.ofSeconds(spec.timeoutSeconds()));
            return new Result(spec.name(), reply == null ? "" : reply.getTextContent(),
                    System.currentTimeMillis() - start, spec.model().getModelName());
        } catch (Exception e) {
            return new Result(spec.name(), "", System.currentTimeMillis() - start, spec.model().getModelName(), e);
        }
    }

    public record Spec(String name, String systemPrompt, Model model, Toolkit toolkit,
                       Object tool, io.agentscope.core.tool.AgentTool agentTool,
                       Msg input, int maxIterations, int timeoutSeconds) {
        public static Builder builder(String name, String systemPrompt, Model model) {
            return new Builder(name, systemPrompt, model);
        }

        public static final class Builder {
            private final String name;
            private final String systemPrompt;
            private final Model model;
            private Toolkit toolkit;
            private Object tool;
            private io.agentscope.core.tool.AgentTool agentTool;
            private Msg input;
            private int maxIterations = 6;
            private int timeoutSeconds = 60;

            private Builder(String name, String systemPrompt, Model model) {
                this.name = name;
                this.systemPrompt = systemPrompt;
                this.model = model;
            }

            public Builder toolkit(Toolkit toolkit) {
                this.toolkit = toolkit;
                return this;
            }

            public Builder tool(Object tool) {
                this.tool = tool;
                return this;
            }

            public Builder agentTool(io.agentscope.core.tool.AgentTool agentTool) {
                this.agentTool = agentTool;
                return this;
            }

            public Builder input(Msg input) {
                this.input = input;
                return this;
            }

            public Builder maxIterations(int maxIterations) {
                this.maxIterations = maxIterations;
                return this;
            }

            public Builder timeoutSeconds(int timeoutSeconds) {
                this.timeoutSeconds = timeoutSeconds;
                return this;
            }

            public Spec build() {
                return new Spec(name, systemPrompt, model, toolkit, tool, agentTool, input, maxIterations,
                        timeoutSeconds);
            }
        }
    }

    public record Result(String agent, String output, long durationMs, String model, Throwable error) {
        public Result(String agent, String output, long durationMs, String model) {
            this(agent, output, durationMs, model, null);
        }

        public boolean ok() {
            return error == null;
        }
    }

    /** Convenience factory for a run-scoped session id. */
    public static String newRunId() {
        return UUID.randomUUID().toString();
    }

    /** Empty history helper for callers that only need a single-turn run. */
    public static List<Msg> history() {
        return List.of();
    }
}
