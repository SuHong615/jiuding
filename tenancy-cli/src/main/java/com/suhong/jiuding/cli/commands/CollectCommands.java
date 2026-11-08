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

import static com.suhong.jiuding.cli.BaseCommand.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** {@code tenancy collect import --file ... --source ...}. */
public final class CollectCommands {

    private static final List<String> JOB_HEADERS =
            headers("JOB ID", "模式", "来源", "状态", "总数", "导入", "重复", "失败", "创建时间");

    private CollectCommands() {
    }

    @Command(name = "collect", description = "租户采集导入",
            subcommands = {CollectCommands.ImportCommand.class})
    public static class Collect extends BaseCommand.Group {
    }

    @Command(name = "import", description = "导入 CSV 或 JSON 批量记录")
    public static class ImportCommand extends BaseCommand {

        @Option(names = {"--file", "-f"}, paramLabel = "<path>", required = true,
                description = "CSV 或 JSON 文件")
        Path file;

        @Option(names = "--source", paramLabel = "<name>", description = "来源名称，默认 cli-import")
        String source = "cli-import";

        @Option(names = "--mode", paramLabel = "<mode>",
                description = "MANUAL_BATCH|API_PULL（JSON 导入，默认 MANUAL_BATCH）")
        String mode = "MANUAL_BATCH";

        @Option(names = "--auto-submit", description = "导入后自动提交审批（JSON 导入）")
        boolean autoSubmit;

        @Override
        public Integer call() {
            if (!Files.isRegularFile(file)) {
                throw new CliUsageException("文件不存在: " + file);
            }
            String lower = file.getFileName().toString().toLowerCase(Locale.ROOT);
            JsonNode data;
            if (lower.endsWith(".csv")) {
                Map<String, String> fields = new LinkedHashMap<>();
                fields.put("sourceName", source);
                data = api().postMultipart("/api/tenants/collect/csv", file, "file", fields);
            } else if (lower.endsWith(".json")) {
                JsonNode parsed;
                try {
                    parsed = Json.parse(Files.readString(file, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    throw new CliUsageException("读取文件失败: " + file + " (" + e.getMessage() + ")");
                }
                JsonNode records = parsed.isArray() ? parsed : Json.field(parsed, "records");
                if (!records.isArray() || records.isEmpty()) {
                    throw new CliUsageException("JSON 文件必须是记录数组或以 {records:[...]} 包裹");
                }
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("sourceName", source);
                body.put("mode", mode);
                body.put("autoSubmit", autoSubmit);
                body.put("records", records);
                data = api().post("/api/tenants/collect", body);
            } else {
                throw new CliUsageException("只支持 .csv 或 .json 文件: " + file);
            }

            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            List<List<String>> rows = new ArrayList<>();
            rows.add(row(
                    text(data, "id"),
                    text(data, "mode"),
                    text(data, "sourceName"),
                    text(data, "status"),
                    num(data, "total"),
                    num(data, "imported"),
                    num(data, "duplicated"),
                    num(data, "failed"),
                    text(data, "createdAt")));
            out().println(com.suhong.jiuding.cli.TableRenderer.render(JOB_HEADERS, rows));
            JsonNode errors = Json.field(data, "errors");
            if (errors.isArray() && !errors.isEmpty()) {
                out().println("错误（最多 20 条）:");
                errors.forEach(error -> out().println("  - " + error.asText()));
            }
            return ExitCodes.OK;
        }
    }
}
