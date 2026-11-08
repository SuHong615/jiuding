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
package com.suhong.jiuding.cli;

import picocli.CommandLine.Option;

/** Options accepted by every command (picocli mixin on the root command). */
public class GlobalOptions {

    @Option(names = "--base-url", paramLabel = "<url>",
            description = "网关地址，默认 http://localhost:8080")
    public String baseUrl;

    @Option(names = "--token", paramLabel = "<jwt>",
            description = "JWT token（覆盖配置文件/env）")
    public String token;

    @Option(names = "--tenant", paramLabel = "<tenantId>",
            description = "租户 ID（X-Tenant-Id 头）")
    public String tenant;

    @Option(names = "--api-key", paramLabel = "<key>",
            description = "MCP X-API-Key（默认 dev-mcp-key）")
    public String apiKey;

    @Option(names = "--output", paramLabel = "table|json",
            description = "输出格式：table 或 json（默认 table）")
    public String output;

    @Option(names = "--config", paramLabel = "<path>",
            description = "配置文件路径，默认 <user.home>/.tenancy/config.json")
    public String config;

    @Option(names = "--timeout", paramLabel = "<seconds>",
            description = "HTTP 超时秒数（默认 30）")
    public Integer timeout;
}
