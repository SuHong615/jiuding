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
import com.suhong.jiuding.agent.config.TenancyAgentProperties;
import com.suhong.jiuding.agent.skill.SkillDefinition;
import com.suhong.jiuding.agent.skill.SkillRegistry;
import com.suhong.jiuding.agentkernel.runtime.AgentKernel;
import com.suhong.jiuding.agentkernel.model.ScriptedModel;
import io.agentscope.core.message.Msg;
import io.agentscope.core.message.MsgRole;
import io.agentscope.core.message.TextBlock;
import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.message.UserMessage;
import io.agentscope.core.tool.Toolkit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Executes the four platform agents on top of the AgentScope ReAct runtime.
 *
 * <p>Scripted channel (default): the model is a deterministic responder that calls
 * exactly one tool and then synthesizes structured JSON from the observation.
 * Remote channel ({@code provider=openai}): the same tool set and system prompt
 * are handed to a real OpenAI-compatible model; steps are then only available
 * from the model's own reasoning (we do not instrument AgentScope internals).
 */
@Service
public class AgentRunService {

    private static final Logger log = LoggerFactory.getLogger(AgentRunService.class);

    private final AgentScripts scripts;
    private final AgentTools agentTools;
    private final AgentModelFactory modelFactory;
    private final com.suhong.jiuding.agent.repository.AgentRunRepository repository;
    private final SkillRegistry skillRegistry;
    private final TenancyAgentProperties properties;
    private final ObjectMapper objectMapper;
    private final AgentPresentation presentation;

    public AgentRunService(AgentScripts scripts, AgentTools agentTools, AgentModelFactory modelFactory,
                           com.suhong.jiuding.agent.repository.AgentRunRepository repository,
                           SkillRegistry skillRegistry, TenancyAgentProperties properties,
                           ObjectMapper objectMapper, AgentPresentation presentation) {
        this.scripts = scripts;
        this.agentTools = agentTools;
        this.modelFactory = modelFactory;
        this.repository = repository;
        this.skillRegistry = skillRegistry;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.presentation = presentation;
    }

    /** Run one agent and persist the trace (agent_runs + rule_hits via skill.run tool). */
    public AgentRunView run(String agentType, String tenantId, Map<String, Object> rawInput, Integer maxSteps) {
        AgentScript script = scripts.require(agentType);
        Map<String, Object> input = rawInput == null ? Map.of() : new LinkedHashMap<>(rawInput);
        Map<String, Object> context = new LinkedHashMap<>(input);
        if (tenantId != null && !tenantId.isBlank()) {
            context.putIfAbsent("tenantId", tenantId);
        }
        String runId = UUID.randomUUID().toString();
        long startedAt = System.currentTimeMillis();
        RunRecorder recorder = new RunRecorder(objectMapper, runId);

        AgentScript.ScriptContext scriptContext = new AgentScript.ScriptContext(context, tenantId);
        AgentModelFactory.ResolvedModel resolvedModel = modelFactory.create(
                messages -> respond(script, scriptContext, recorder, messages));
        Toolkit toolkit = agentTools.toolkitFor(recorder);

        String systemPrompt = systemPrompt(script);
        String userText = userText(script, context);
        int iterations = clamp(maxSteps == null ? properties.getAgent().getMaxSteps() : maxSteps, 1, 12);

        AgentKernel.Result result = new AgentKernel().run(AgentKernel.Spec
                .builder("tenancy-" + script.agentType(), systemPrompt, resolvedModel.model())
                .toolkit(toolkit)
                .input(new UserMessage(userText))
                .maxIterations(iterations)
                .timeoutSeconds(properties.getAgent().getTimeoutSeconds())
                .build());

        long durationMs = System.currentTimeMillis() - startedAt;
        String status = result.error() == null ? "SUCCEEDED" : "FAILED";
        Object output = result.error() == null
                ? parseModelOutput(result.output())
                : Map.of("error", String.valueOf(result.error().getMessage()),
                "agentType", script.agentType());
        List<Step> steps = recorder.steps();
        int tokensUsed = estimateTokens(userText, result.output());
        String createdAt = java.time.Instant.now().toString();

        AgentRunView view = new AgentRunView(runId, script.agentType(), null, null, status, output, null,
                steps, resolvedModel.label(), tokensUsed, durationMs, createdAt, tenantId, context);
        view = enrich(view);
        try {
            repository.insert(view, tenantId, writeJson(context));
        } catch (Exception e) {
            log.error("failed to persist agent run {}", runId, e);
        }
        if ("FAILED".equals(status)) {
            log.warn("agent run {} ({}) failed: {}", runId, script.agentType(),
                    result.error() == null ? "unknown" : result.error().toString());
        }
        return view;
    }

    /** Scripted responder: tool call on iteration 1, structured JSON on iteration 2. */
    private io.agentscope.core.model.ChatResponse respond(AgentScript script,
                                                          AgentScript.ScriptContext context,
                                                          RunRecorder recorder,
                                                          List<Msg> messages) {
        if (!ScriptedModel.hasToolResult(messages)) {
            AgentScript.ToolInvocation invocation = script.toolSelector().apply(context);
            recorder.beginStep(script.thought(), invocation.tool(), invocation.input());
            return ScriptedModel.toolCall(invocation.tool(), invocation.input());
        }
        String observation = lastObservation(messages);
        Map<String, Object> observationMap = parseObservation(observation);
        Map<String, Object> output = script.finalizer().apply(context, observationMap,
                recorder.lastSkillResult());
        recorder.finalizeStep(output);
        return ScriptedModel.text(writeJson(output));
    }

    private static String lastObservation(List<Msg> messages) {
        return messages.stream()
                .filter(message -> message.getRole() == MsgRole.TOOL)
                .flatMap(message -> message.getContentBlocks(ToolResultBlock.class).stream())
                .flatMap(block -> block.getOutput().stream())
                .filter(TextBlock.class::isInstance)
                .map(TextBlock.class::cast)
                .map(TextBlock::getText)
                .collect(Collectors.joining());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseObservation(String observation) {
        if (observation == null || observation.isBlank()) {
            return Map.of();
        }
        try {
            Object parsed = objectMapper.readValue(observation, Object.class);
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new LinkedHashMap<>();
                map.forEach((key, value) -> result.put(String.valueOf(key), value));
                return result;
            }
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("observation", parsed);
            return wrapper;
        } catch (Exception e) {
            Map<String, Object> wrapper = new LinkedHashMap<>();
            wrapper.put("raw", observation);
            return wrapper;
        }
    }

    private Object parseModelOutput(String text) {
        if (text == null || text.isBlank()) {
            return Map.of("error", "empty model output");
        }
        String cleaned = text.trim();
        if (cleaned.startsWith("```")) {
            int firstNewline = cleaned.indexOf('\n');
            int lastFence = cleaned.lastIndexOf("```");
            if (firstNewline > 0 && lastFence > firstNewline) {
                cleaned = cleaned.substring(firstNewline + 1, lastFence).trim();
            }
        }
        try {
            return objectMapper.readValue(cleaned, Object.class);
        } catch (Exception ignored) {
            int start = cleaned.indexOf('{');
            int end = cleaned.lastIndexOf('}');
            if (start >= 0 && end > start) {
                try {
                    return objectMapper.readValue(cleaned.substring(start, end + 1), Object.class);
                } catch (Exception ignoredAgain) {
                    // fall through to raw text
                }
            }
            return cleaned;
        }
    }

    private String systemPrompt(AgentScript script) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是多租户 SaaS 运营平台的 ").append(script.agentType()).append(" Agent。\n");
        skillRegistry.find(script.skillId()).ifPresent((SkillDefinition definition) -> {
            prompt.append("\n# Skill: ").append(definition.manifest().name())
                    .append(" (v").append(definition.manifest().version()).append(")\n");
            prompt.append(definition.instructions()).append("\n");
        });
        prompt.append("\n要求：只输出一个 JSON 对象，不要 markdown 代码块，不要额外解释。");
        return prompt.toString();
    }

    private String userText(AgentScript script, Map<String, Object> context) {
        return "agentType=" + script.agentType() + "\ninput=" + writeJson(context);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static int estimateTokens(String prompt, String completion) {
        int characters = (prompt == null ? 0 : prompt.length()) + (completion == null ? 0 : completion.length());
        return Math.max(1, characters / 4);
    }

    private static int clamp(int value, int min, int max) {
        return Math.min(Math.max(value, min), max);
    }

    public java.util.Optional<AgentRunView> find(String runId) {
        return repository.find(runId).map(this::enrich);
    }

    public com.suhong.jiuding.common.api.PageResult<AgentRunView> page(String agentType, int page, int size) {
        com.suhong.jiuding.common.api.PageResult<AgentRunView> result = repository.page(agentType, page, size);
        List<AgentRunView> enriched = new java.util.ArrayList<>(result.items().size());
        for (AgentRunView item : result.items()) {
            enriched.add(enrich(item));
        }
        return com.suhong.jiuding.common.api.PageResult.of(enriched, result.total(), result.page(), result.size());
    }

    /** Fill displayName/purpose/summary and Chinese step titles (docs/15 §3). */
    private AgentRunView enrich(AgentRunView view) {
        if (view == null) {
            return null;
        }
        String displayName = presentation.displayName(view.agentType());
        String purpose = presentation.purpose(view.agentType());
        String summary = presentation.summary(view.agentType(), view.output());
        List<Step> steps = presentation.decorate(view.steps());
        return new AgentRunView(view.runId(), view.agentType(), displayName, purpose, view.status(),
                view.output(), summary, steps, view.model(), view.tokensUsed(), view.durationMs(),
                view.createdAt(), view.tenantId(), view.input());
    }

    /** Agent catalog served by {@code GET /api/agents/catalog}. */
    public List<AgentPresentation.CatalogEntry> catalog() {
        return presentation.catalog();
    }

    public Map<String, Object> overview() {
        Map<String, Object> overview = new LinkedHashMap<>();
        overview.put("runs", repository.countAll());
        overview.put("todayRuns", repository.countToday());
        overview.put("skills", skillRegistry.count());
        overview.put("agentTypes", scripts.agentTypes());
        List<String> models = new java.util.ArrayList<>();
        models.add("scripted");
        if (!"scripted".equals(modelFactory.label())) {
            models.add(modelFactory.label());
        }
        overview.put("models", models);
        overview.put("modelProvider", modelFactory.openAiEnabled() ? "openai" : "scripted");
        return overview;
    }

    public List<ToolDescriptor> tools() {
        return agentTools.descriptors();
    }
}
