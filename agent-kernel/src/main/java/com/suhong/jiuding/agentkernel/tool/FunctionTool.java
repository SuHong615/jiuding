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
package com.suhong.jiuding.agentkernel.tool;

import io.agentscope.core.message.ToolResultBlock;
import io.agentscope.core.tool.AgentTool;
import io.agentscope.core.tool.ToolCallParam;
import reactor.core.publisher.Mono;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

/**
 * AgentTool implementation backed by a plain Java function.
 *
 * <p>Used instead of reflective {@code @Tool} registration for two reasons:
 * (1) the platform must be able to build tool schemas dynamically from Skill
 * manifests; (2) reflective invocation in AgentScope validates through its own
 * argument binder, which is unnecessary indirection for our JSON-in/JSON-out
 * tools. Each tool is deterministic and side-effect free unless the function
 * explicitly performs I/O.
 */
public final class FunctionTool implements AgentTool {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final Function<Map<String, Object>, String> function;

    public FunctionTool(String name, String description, Map<String, Object> parameters,
                        Function<Map<String, Object>, String> function) {
        this.name = Objects.requireNonNull(name, "name");
        this.description = description == null ? "" : description;
        this.parameters = parameters == null ? emptySchema() : parameters;
        this.function = Objects.requireNonNull(function, "function");
    }

    /** Convenience factory for a tool with no arguments. */
    public static FunctionTool of(String name, String description, Function<Map<String, Object>, String> function) {
        return new FunctionTool(name, description, emptySchema(), function);
    }

    public static Map<String, Object> emptySchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of());
        schema.put("required", List.of());
        return schema;
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return description;
    }

    @Override
    public Map<String, Object> getParameters() {
        return parameters;
    }

    @Override
    public Mono<ToolResultBlock> callAsync(ToolCallParam param) {
        return Mono.fromCallable(() -> {
            Map<String, Object> input = param.getInput();
            if (input == null && param.getToolUseBlock() != null) {
                input = param.getToolUseBlock().getInput();
            }
            if (input == null && param.getToolUseBlock() != null) {
                input = param.getToolUseBlock().getInput();
            }
            try {
                String output = function.apply(input == null ? Map.of() : input);
                return ToolResultBlock.text(output == null ? "" : output);
            } catch (Exception e) {
                return ToolResultBlock.error("tool '" + name + "' failed: " + e.getMessage());
            }
        });
    }

    /** Build a simple JSON-schema object for tools with declarative parameters. */
    public static Map<String, Object> schema(Map<String, ParamSpec> properties, List<String> required) {
        Map<String, Object> propertyMap = new LinkedHashMap<>();
        properties.forEach((key, spec) -> propertyMap.put(key, spec.toJson()));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", propertyMap);
        schema.put("required", required == null ? List.of() : required);
        return schema;
    }

    /** Minimal JSON-schema parameter description helper. */
    public record ParamSpec(String type, String description, boolean required) {
        public static ParamSpec string(String description) {
            return new ParamSpec("string", description, false);
        }

        public static ParamSpec requiredString(String description) {
            return new ParamSpec("string", description, true);
        }

        public static ParamSpec integer(String description) {
            return new ParamSpec("integer", description, false);
        }

        public static ParamSpec object(String description) {
            return new ParamSpec("object", description, false);
        }

        public static ParamSpec array(String description) {
            return new ParamSpec("array", description, false);
        }

        public static ParamSpec bool(String description) {
            return new ParamSpec("boolean", description, false);
        }

        Map<String, Object> toJson() {
            Map<String, Object> json = new LinkedHashMap<>();
            json.put("type", type);
            json.put("description", description);
            return json;
        }
    }
}
