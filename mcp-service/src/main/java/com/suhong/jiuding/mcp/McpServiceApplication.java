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
package com.suhong.jiuding.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * MCP server (Model Context Protocol) exposing the tenancy platform to agents.
 *
 * <p>Transport follows docs/05: JSON-RPC 2.0 over {@code POST /mcp}, an SSE
 * event stream on {@code GET /mcp} (15s heartbeat, {@code X-Accel-Buffering:no})
 * and a capability snapshot on {@code GET /mcp/info}. 14 tools proxy to the
 * downstream REST services; failures become {@code isError:true} tool results
 * instead of HTTP 500s.
 */
@SpringBootApplication
@ConfigurationPropertiesScan("com.suhong.jiuding.mcp")
public class McpServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(McpServiceApplication.class, args);
    }
}
