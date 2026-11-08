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

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/** Demo tool used by the kernel smoke test; production tools live in agent-service. */
public class EchoTool {

    private final Function<String, String> echo;

    public EchoTool() {
        this(s -> s);
    }

    public EchoTool(Function<String, String> echo) {
        this.echo = echo;
    }

    @Tool(name = "echo", description = "回显给定文本，用于验证 AgentScope 工具调用链路")
    public String echo(@ToolParam(name = "text", required = true, description = "待回显文本") String text) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("echoed", echo.apply(text));
        payload.put("ok", true);
        return "{\"ok\":true,\"echoed\":\"" + echo.apply(text) + "\"}";
    }
}
