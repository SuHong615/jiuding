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
import com.suhong.jiuding.agent.skill.SkillService;
import com.suhong.jiuding.agentkernel.tool.FunctionTool;
import io.agentscope.core.tool.Toolkit;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tools exposed to AgentScope ReActAgents (and listed by {@code GET /api/agents/tools}).
 *
 * <p>Uses {@link FunctionTool} instead of reflective {@code @Tool} registration:
 * schemas are built explicitly and the call path is fully deterministic, which
 * keeps the offline scripted model and a real OpenAI model on the same contract.
 */
@Component
public class AgentTools {

    public static final String TENANT_GET = "tenant.get";
    public static final String SKILL_RUN = "skill.run";
    public static final String APPROVAL_LIST_PENDING = "approval.list_pending";

    private final SkillService skillService;
    private final DownstreamClient downstream;
    private final ObjectMapper objectMapper;

    public AgentTools(SkillService skillService, DownstreamClient downstream, ObjectMapper objectMapper) {
        this.skillService = skillService;
        this.downstream = downstream;
        this.objectMapper = objectMapper;
    }

    /** Build a toolkit with all tools bound to this run's recorder. */
    public Toolkit toolkitFor(RunRecorder recorder) {
        Toolkit toolkit = new Toolkit();
        toolkit.registerAgentTool(tenantGetTool(recorder));
        toolkit.registerAgentTool(skillRunTool(recorder));
        toolkit.registerAgentTool(approvalPendingTool(recorder));
        return toolkit;
    }

    private FunctionTool tenantGetTool(RunRecorder recorder) {
        Map<String, FunctionTool.ParamSpec> tenantParams = new LinkedHashMap<>();
        tenantParams.put("tenantId", FunctionTool.ParamSpec.requiredString("租户 ID"));
        Map<String, Object> schema = FunctionTool.schema(tenantParams, List.of("tenantId"));
        return new FunctionTool(TENANT_GET, "按 tenantId 读取租户档案（透传 tenant-service 内部接口）", schema,
                input -> {
                    String tenantId = string(input.get("tenantId"));
                    if (tenantId == null) {
                        return record(recorder, error("tenantId is required"));
                    }
                    DownstreamClient.Result result = downstream.getTenant(tenantId);
                    if (result.ok()) {
                        return record(recorder, json(result.data()));
                    }
                    return record(recorder, error("tenant-service unavailable: " + result.error()));
                });
    }

    private FunctionTool skillRunTool(RunRecorder recorder) {
        Map<String, FunctionTool.ParamSpec> properties = new LinkedHashMap<>();
        properties.put("skillId", FunctionTool.ParamSpec.requiredString("技能 ID，如 risk-review"));
        properties.put("tenantId", FunctionTool.ParamSpec.string("租户 ID"));
        properties.put("input", FunctionTool.ParamSpec.object("技能输入对象"));
        Map<String, Object> schema = FunctionTool.schema(properties, List.of("skillId"));

        return new FunctionTool(SKILL_RUN, "执行确定性 Skill 规则引擎并返回 verdict/decision/findings/trace", schema,
                input -> {
                    String skillId = string(input.get("skillId"));
                    if (skillId == null) {
                        return record(recorder, error("skillId is required"));
                    }
                    Map<String, Object> skillInput = asMap(input.get("input"));
                    String tenantId = string(input.get("tenantId"));
                    try {
                        SkillRunResult result = skillService.runWithRunId(skillId, skillInput, tenantId,
                                recorder.runId());
                        recorder.recordSkillResult(result);
                        return record(recorder, json(result));
                    } catch (Exception e) {
                        return record(recorder, error("skill run failed: " + e.getMessage()));
                    }
                });
    }

    private FunctionTool approvalPendingTool(RunRecorder recorder) {
        return FunctionTool.of(APPROVAL_LIST_PENDING, "读取待办审批列表（透传 approval-service）", input -> {
            DownstreamClient.Result result = downstream.listPendingApprovals();
            if (result.ok()) {
                return record(recorder, json(result.data()));
            }
            return record(recorder, error("approval-service unavailable: " + result.error()));
        });
    }

    /** Declarative tool catalog served by {@code GET /api/agents/tools}. */
    public List<ToolDescriptor> descriptors() {
        List<ToolDescriptor> descriptors = new ArrayList<>();

        Map<String, Object> tenantProps = new LinkedHashMap<>();
        tenantProps.put("tenantId", property("string", "租户 ID"));
        descriptors.add(new ToolDescriptor(TENANT_GET, "按 tenantId 读取租户档案",
                objectSchema(tenantProps, List.of("tenantId"))));

        Map<String, Object> skillProps = new LinkedHashMap<>();
        skillProps.put("skillId", property("string", "技能 ID"));
        skillProps.put("tenantId", property("string", "租户 ID"));
        skillProps.put("input", property("object", "技能输入对象"));
        descriptors.add(new ToolDescriptor(SKILL_RUN, "执行 Skill 规则引擎",
                objectSchema(skillProps, List.of("skillId"))));

        descriptors.add(new ToolDescriptor(APPROVAL_LIST_PENDING, "读取待办审批列表",
                objectSchema(new LinkedHashMap<>(), List.of())));
        return descriptors;
    }

    private String record(RunRecorder recorder, String payload) {
        recorder.observation(payload);
        return payload;
    }

    private static Map<String, Object> property(String type, String description) {
        Map<String, Object> property = new LinkedHashMap<>();
        property.put("type", type);
        property.put("description", description);
        return property;
    }

    private static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        return schema;
    }

    private static Map<String, Object> asMap(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return Map.of();
    }

    private static String string(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return error("failed to serialize payload: " + e.getMessage());
        }
    }

    private String error(String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ok", false);
        payload.put("error", message);
        return json(payload);
    }
}
