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

import static com.suhong.jiuding.cli.BaseCommand.encPath;

import com.suhong.jiuding.cli.ApiClient;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/** {@code tenancy audit export ...} - compliance audit CSV export (docs/14 §3.2). */
public final class AuditCommands {

    private AuditCommands() {
    }

    @Command(name = "audit", description = "合规审计：导出 CSV", mixinStandardHelpOptions = true,
            subcommands = {AuditCommands.ExportCommand.class})
    public static class Audit extends BaseCommand.Group {
    }

    @Command(name = "export", mixinStandardHelpOptions = true,
            description = "导出租户审计日志为 CSV（默认最近 30 天）")
    public static class ExportCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--from", paramLabel = "<ISO-8601>", description = "起始时间，如 2026-11-01T00:00:00Z")
        String from;

        @Option(names = "--to", paramLabel = "<ISO-8601>", description = "结束时间")
        String to;

        @Option(names = "--out", paramLabel = "<file.csv>", description = "保存到本地 CSV 文件（缺省打印到 stdout）")
        Path out;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("from", from);
            query.put("to", to);
            query.put("format", "csv");
            ApiClient.RawResponse response = apiForTenant(tenantId)
                    .getRaw("/api/tenants/" + encPath(tenantId) + "/audits/export", query);
            String csv = response.body() == null ? "" : response.body();
            if (out != null) {
                try {
                    Path parent = out.toAbsolutePath().getParent();
                    if (parent != null) {
                        Files.createDirectories(parent);
                    }
                    Files.writeString(out, csv, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    throw new com.suhong.jiuding.cli.CliUsageException("写入 CSV 文件失败: " + out + " (" + e.getMessage() + ")");
                }
                if (jsonOutput()) {
                    out().println(Json.pretty(Json.object()
                            .put("out", out.toString())
                            .put("bytes", csv.getBytes(StandardCharsets.UTF_8).length)
                            .put("contentType", response.contentType())));
                } else {
                    out().println("已导出审计日志: " + out + "（" + csv.length() + " 字符, " + response.contentType() + "）");
                }
                return ExitCodes.OK;
            }
            if (jsonOutput()) {
                out().println(Json.pretty(Json.object()
                        .put("csv", csv)
                        .put("contentType", response.contentType())));
            } else {
                out().print(csv.endsWith("\n") ? csv : csv + System.lineSeparator());
            }
            return ExitCodes.OK;
        }
    }
}
