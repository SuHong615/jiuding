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
import static com.suhong.jiuding.cli.BaseCommand.headers;
import static com.suhong.jiuding.cli.BaseCommand.num;
import static com.suhong.jiuding.cli.BaseCommand.row;
import static com.suhong.jiuding.cli.BaseCommand.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.CliUsageException;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** {@code tenancy rating ...} - tenant rating commands (docs/11 §6.4). */
public final class RatingCommands {

    private static final Set<String> GRADES = Set.of("AAA", "AA", "A", "B", "C", "D");
    private static final List<String> HISTORY_HEADERS =
            headers("ID", "等级", "分数", "来源", "评估人", "时间", "备注");

    private RatingCommands() {
    }

    @Command(name = "rating", mixinStandardHelpOptions = true, description = "租户评级：当前 / 历史 / 评估 / 人工覆盖",
            subcommands = {
                    RatingCommands.CurrentCommand.class,
                    RatingCommands.HistoryCommand.class,
                    RatingCommands.AssessCommand.class,
                    RatingCommands.OverrideCommand.class
            })
    public static class Rating extends BaseCommand.Group {
    }

    @Command(name = "current", mixinStandardHelpOptions = true, description = "查看租户当前评级")
    public static class CurrentCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            JsonNode data = apiForTenant(tenantId).get(ratingsPath(tenantId) + "/current", null);
            if (data == null || data.isNull() || (data.isObject() && data.isEmpty())) {
                if (jsonOutput()) {
                    out().println(Json.pretty(data));
                } else {
                    out().println("该租户暂无评级，可执行: tenancy rating assess --tenant " + tenantId);
                }
                return ExitCodes.OK;
            }
            JsonNode dims = Json.field(data, "dims");
            return emitDetail(data,
                    List.of("评级 ID", "等级", "总分", "上次等级", "上次分数", "分数变化", "等级变化",
                            "业务(30%)", "合规(25%)", "财务(20%)", "安全(15%)", "服务(10%)",
                            "来源", "评估人", "备注", "评估时间"),
                    List.of(text(data, "id"), text(data, "grade"), num(data, "score"),
                            text(data, "previousGrade"), num(data, "previousScore"),
                            num(data, "scoreDelta"), text(data, "gradeChanged"),
                            num(dims, "business"), num(dims, "compliance"), num(dims, "financial"),
                            num(dims, "security"), num(dims, "service"),
                            text(data, "source"), text(data, "assessedBy"), text(data, "note"),
                            text(data, "createdAt")));
        }
    }

    @Command(name = "history", mixinStandardHelpOptions = true, description = "分页查询评级历史（倒序）")
    public static class HistoryCommand extends BaseCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--page", paramLabel = "<n>", description = "页码，从 0 开始")
        int page = 0;

        @Option(names = "--size", paramLabel = "<n>", description = "每页条数（默认 20）")
        int size = 20;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, String> query = new LinkedHashMap<>();
            query.put("page", String.valueOf(page));
            query.put("size", String.valueOf(size));
            JsonNode data = apiForTenant(tenantId).get(ratingsPath(tenantId), query);
            return emitItems(data, HISTORY_HEADERS, RatingCommands::historyRow);
        }
    }

    /** Shared rendering for assess/override results. */
    private abstract static class RatingRenderCommand extends BaseCommand {

        protected int emitRatingResult(JsonNode data) {
            JsonNode dims = Json.field(data, "dims");
            return emitDetail(data,
                    List.of("评级 ID", "等级", "总分", "业务(30%)", "合规(25%)", "财务(20%)",
                            "安全(15%)", "服务(10%)", "来源", "评估人", "备注", "评估时间"),
                    List.of(text(data, "id"), text(data, "grade"), num(data, "score"),
                            num(dims, "business"), num(dims, "compliance"), num(dims, "financial"),
                            num(dims, "security"), num(dims, "service"),
                            text(data, "source"), text(data, "assessedBy"), text(data, "note"),
                            text(data, "createdAt")));
        }
    }

    @Command(name = "assess", mixinStandardHelpOptions = true, description = "触发租户评级评估（写历史并刷新当前评级）")
    public static class AssessCommand extends RatingRenderCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = "--note", paramLabel = "<text>", description = "评估备注")
        String note;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            Map<String, Object> body = new LinkedHashMap<>();
            if (note != null && !note.isBlank()) {
                body.put("note", note);
            }
            JsonNode data = apiForTenant(tenantId).post(ratingsPath(tenantId) + "/assess", body);
            return emitRatingResult(data);
        }
    }

    @Command(name = "override", mixinStandardHelpOptions = true, description = "人工覆盖评级（仅平台管理员，必须填写原因）")
    public static class OverrideCommand extends RatingRenderCommand {

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID（或全局 --tenant）")
        String tenant;

        @Option(names = {"--grade", "-g"}, paramLabel = "AAA|AA|A|B|C|D", required = true, description = "评级等级")
        String grade;

        @Option(names = {"--score", "-s"}, paramLabel = "<0-100>", required = true, description = "评级总分")
        Integer score;

        @Option(names = "--reason", paramLabel = "<text>", required = true, description = "覆盖原因（必填）")
        String reason;

        @Override
        public Integer call() {
            String tenantId = requireTenant(tenant);
            String normalizedGrade = grade == null ? "" : grade.trim().toUpperCase(Locale.ROOT);
            if (!GRADES.contains(normalizedGrade)) {
                throw new CliUsageException("--grade 只支持 AAA|AA|A|B|C|D，当前为: " + grade);
            }
            if (score == null || score < 0 || score > 100) {
                throw new CliUsageException("--score 必须在 0..100 之间");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("grade", normalizedGrade);
            body.put("score", score);
            body.put("reason", reason);
            JsonNode data = apiForTenant(tenantId).post(ratingsPath(tenantId) + "/override", body);
            return emitRatingResult(data);
        }
    }

    private static List<String> historyRow(JsonNode node) {
        return row(
                text(node, "id"),
                text(node, "grade"),
                num(node, "score"),
                text(node, "source"),
                text(node, "assessedBy"),
                text(node, "createdAt"),
                text(node, "note"));
    }

    private static String ratingsPath(String tenantId) {
        return "/api/tenants/" + encPath(tenantId) + "/ratings";
    }
}
