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

import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliContext;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import com.suhong.jiuding.cli.TenancyCli;
import picocli.CommandLine.Command;

import java.util.List;

/** {@code tenancy version} - prints CLI/runtime/config information. */
@Command(name = "version", description = "显示 CLI 版本、运行环境与当前配置")
public class VersionCommand extends BaseCommand {

    @Override
    public Integer call() {
        CliContext context = ctx();
        String javaVersion = System.getProperty("java.version", "-");
        String os = System.getProperty("os.name", "-") + " " + System.getProperty("os.arch", "");
        String configState = java.nio.file.Files.exists(context.configPath()) ? "已存在" : "未创建";
        if (jsonOutput()) {
            out().println(Json.pretty(Json.object()
                    .put("cliVersion", TenancyCli.VERSION)
                    .put("javaVersion", javaVersion)
                    .put("os", os)
                    .put("baseUrl", context.baseUrl())
                    .put("configPath", context.configPath().toString())
                    .put("configState", configState)
                    .put("tokenConfigured", context.token() != null)
                    .put("tenantId", context.tenantId())
                    .put("apiKeyConfigured", context.apiKey() != null)
                    .put("output", context.output())));
        } else {
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("CLI 版本", "Java 版本", "操作系统", "网关地址", "配置文件",
                            "配置状态", "token", "租户", "api-key", "输出格式"),
                    List.of(
                            TenancyCli.VERSION,
                            javaVersion,
                            os,
                            context.baseUrl(),
                            context.configPath().toString(),
                            configState,
                            context.token() != null ? "已配置" : "未配置",
                            context.tenantId() == null ? "-" : context.tenantId(),
                            context.apiKey() != null ? "已配置" : "未配置（使用 dev-mcp-key）",
                            context.output())));
        }
        return ExitCodes.OK;
    }
}
