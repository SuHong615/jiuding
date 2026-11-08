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
package com.suhong.jiuding.cli.commands;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** {@code tenancy agent run|status}. */
public final class AgentCommands {

    public static final Set<String> AGENT_TYPES =
            Set.of("tenant-intake", "risk-review", "approval", "provisioning");

    private AgentCommands() {
    }

    @Command(name = "agent", description = "Agent 运行：发起 / 查询",
            subcommands = {AgentCommands.RunCommand.class, AgentCommands.StatusCommand.class})
    public static class Agent extends BaseCommand.Group {
    }

    /** Shared rendering for run/status so both commands print exactly the same shape. */
    private abstract static class AgentRenderCommand extends BaseCommand {

        protected int renderRun(JsonNode data) {
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("运行 ID", "类型", "状态", "模型", "Token 用量", "耗时(ms)", "创建时间"),
                    List.of(
                            text(data, "runId"), text(data, "agentType"), text(data, "status"),
                            text(data, "model"), num(data, "tokensUsed"), num(data, "durationMs"),
                            text(data, "createdAt"))));
            JsonNode output = Json.field(data, "output");
            if (!output.isNull() && !output.isMissingNode()) {
                out().println();
                out().println("输出:");
                out().println(output.isTextual() ? output.asText() : Json.pretty(output));
            }
            JsonNode steps = Json.field(data, "steps");
            if (steps.isArray() && !steps.isEmpty()) {
                out().println();
                out().println("ReAct 步骤:");
                List<List<String>> rows = new ArrayList<>();
                steps.forEach(step -> rows.add(row(
                        num(step, "index"), text(step, "thought"), text(step, "action"),
                        truncate(text(step, "actionInput", ""), 40),
                        truncate(text(step, "observation", ""), 60))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("步骤", "Thought", "Action", "Action Input", "Observation"), rows));
            }
            return ExitCodes.OK;
        }
    }

    @Command(name = "run", description = "发起一次 Agent 运行")
    public static class RunCommand extends AgentRenderCommand {

        @Option(names = {"--type", "-t"}, paramLabel = "<agentType>", required = true,
                description = "tenant-intake|risk-review|approval|provisioning")
        String agentType;

        @Option(names = {"--input", "-i"}, paramLabel = "<json>", description = "输入 JSON（默认 {}）")
        String input;

        @Option(names = "--max-steps", paramLabel = "<n>", description = "最大步数（默认 6）")
        int maxSteps = 6;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Override
        public Integer call() {
            if (!AGENT_TYPES.contains(agentType)) {
                throw new CliUsageException("--type 必须是 " + AGENT_TYPES + " 之一，当前为: " + agentType);
            }
            if (maxSteps <= 0 || maxSteps > 20) {
                throw new CliUsageException("--max-steps 需在 1..20 之间");
            }
            JsonNode inputNode = input == null || input.isBlank() ? Json.object() : Json.parse(input);
            if (!inputNode.isObject()) {
                throw new CliUsageException("--input 必须是 JSON 对象");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("agentType", agentType);
            if (tenantId != null && !tenantId.isBlank()) {
                body.put("tenantId", tenantId);
            }
            body.put("input", inputNode);
            body.put("maxSteps", maxSteps);
            return renderRun(api().post("/api/agents/runs", body));
        }
    }

    @Command(name = "status", description = "查询 Agent 运行状态")
    public static class StatusCommand extends AgentRenderCommand {

        @Parameters(index = "0", paramLabel = "<runId>", description = "运行 ID")
        String runId;

        @Override
        public Integer call() {
            return renderRun(api().get("/api/agents/runs/" + encode(runId), null));
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
