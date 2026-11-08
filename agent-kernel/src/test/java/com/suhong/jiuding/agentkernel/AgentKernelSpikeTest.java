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
package com.suhong.jiuding.agentkernel;

import com.suhong.jiuding.agentkernel.model.ScriptedModel;
import com.suhong.jiuding.agentkernel.runtime.AgentKernel;
import com.suhong.jiuding.agentkernel.tool.EchoTool;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Technical spike: proves that the official AgentScope Java runtime
 * (io.agentscope:agentscope-core:2.0.4) can be driven programmatically with a
 * deterministic model and a registered tool, entirely offline.
 */
class AgentKernelSpikeTest {

    @Test
    void reactLoopExecutesRegisteredToolThenAnswers() {
        AtomicInteger toolCalls = new AtomicInteger();

        ScriptedModel model = new ScriptedModel("scripted-spike", messages -> {
            if (!ScriptedModel.hasToolResult(messages)) {
                return ScriptedModel.toolCall("echo", Map.of("text", "hello-tenancy"));
            }
            String toolOutput = messages.stream()
                    .filter(m -> m.getRole() == MsgRole.TOOL)
                    .flatMap(m -> m.getContentBlocks(ToolResultBlock.class).stream())
                    .flatMap(block -> block.getOutput().stream())
                    .filter(TextBlock.class::isInstance)
                    .map(TextBlock.class::cast)
                    .map(TextBlock::getText)
                    .collect(Collectors.joining());
            return ScriptedModel.text("FINAL after tool: " + toolOutput);
        });

        EchoTool tool = new EchoTool(text -> {
            toolCalls.incrementAndGet();
            return text.toUpperCase();
        });

        com.suhong.jiuding.agentkernel.tool.FunctionTool functionTool = new com.suhong.jiuding.agentkernel.tool.FunctionTool(
                "echo", "回显给定文本",
                com.suhong.jiuding.agentkernel.tool.FunctionTool.schema(
                        java.util.Map.of("text", com.suhong.jiuding.agentkernel.tool.FunctionTool.ParamSpec.requiredString("待回显文本")),
                        java.util.List.of("text")),
                input -> {
                    toolCalls.incrementAndGet();
                    return "{\"ok\":true,\"echoed\":\"" + String.valueOf(input.get("text")).toUpperCase() + "\"}";
                });

        AgentKernel kernel = new AgentKernel();
        AgentKernel.Result result = kernel.run(AgentKernel.Spec
                .builder("spike-agent", "You are a spike agent.", model)
                .agentTool(functionTool)
                .input(new UserMessage("please echo hello-tenancy"))
                .maxIterations(4)
                .build());

        assertThat(result.error()).as("agent run should not fail").isNull();
        assertThat(result.output()).contains("FINAL after tool");
        assertThat(result.output()).contains("HELLO-TENANCY");
        assertThat(toolCalls.get()).isGreaterThanOrEqualTo(1);
        assertThat(result.model()).isEqualTo("scripted-spike");
    }

    @Test
    void directAgentCallWorks() {
        ScriptedModel model = new ScriptedModel("direct", messages -> ScriptedModel.text("pong"));
        AgentKernel.Result result = new AgentKernel().run(AgentKernel.Spec
                .builder("direct-agent", "reply with pong", model)
                .input(new UserMessage("ping"))
                .build());
        assertThat(result.ok()).isTrue();
        assertThat(result.output()).isEqualTo("pong");
    }

    private static String lastText(Msg msg) {
        return msg == null ? "" : msg.getTextContent();
    }
}
