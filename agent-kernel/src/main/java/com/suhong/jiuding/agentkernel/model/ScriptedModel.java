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
package com.suhong.jiuding.agentkernel.model;

import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolUseBlock;
import io.agentscope.core.model.ChatResponse;
import io.agentscope.core.model.ChatUsage;
import io.agentscope.core.model.GenerateOptions;
import io.agentscope.core.model.Model;
import io.agentscope.core.model.ToolSchema;
import reactor.core.publisher.Flux;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/**
 * Deterministic AgentScope {@link Model} implementation.
 *
 * <p>The platform is expected to run fully offline in CI and docker-compose
 * smoke tests, so every agent is backed by a scripted responder by default.
 * A real OpenAI-compatible model can be plugged in via configuration
 * ({@code tenancy.agent.model.provider=openai}); the agent code itself never
 * changes because both implement the same AgentScope {@code Model} SPI.
 */
public class ScriptedModel implements Model {

    private final String modelName;
    private final Function<List<Msg>, ChatResponse> responder;

    public ScriptedModel(String modelName, Function<List<Msg>, ChatResponse> responder) {
        this.modelName = modelName;
        this.responder = responder;
    }

    @Override
    public Flux<ChatResponse> stream(List<Msg> messages, List<ToolSchema> tools, GenerateOptions options) {
        return Flux.defer(() -> Flux.just(responder.apply(messages)));
    }

    @Override
    public String getModelName() {
        return modelName;
    }

    /** True when the conversation already contains a tool result (second ReAct iteration). */
    public static boolean hasToolResult(List<Msg> messages) {
        return messages.stream().anyMatch(m -> m.getRole() == MsgRole.TOOL);
    }

    public static ChatResponse text(String text) {
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(List.of(TextBlock.builder().text(text).build()))
                .usage(new ChatUsage(12, 24, 0.01))
                .finishReason("stop")
                .build();
    }

    public static ChatResponse toolCall(String toolName, Map<String, Object> input) {
        String argumentsJson;
        try {
            argumentsJson = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(input);
        } catch (Exception e) {
            argumentsJson = "{}";
        }
        return ChatResponse.builder()
                .id(UUID.randomUUID().toString())
                .content(List.of(ToolUseBlock.builder()
                        .id("call-" + UUID.randomUUID())
                        .name(toolName)
                        .input(input)
                        .content(argumentsJson)
                        .build()))
                .usage(new ChatUsage(12, 8, 0.01))
                .finishReason("tool_calls")
                .build();
    }
}
