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
import picocli.CommandLine.Parameters;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * {@code tenancy skill ...} - skill list/run plus the v5 skill-center lifecycle
 * (upload / submit-review / review / enable / disable) from docs/15 §2.2.
 */
public final class SkillCommands {

    private static final List<String> LIST_HEADERS =
            headers("ID", "名称", "版本", "类型", "来源", "状态", "启用", "触发");
    private static final Set<String> REVIEW_DECISIONS = Set.of("APPROVE", "REJECT");

    private SkillCommands() {
    }

    @Command(name = "skill", mixinStandardHelpOptions = true,
            description = "Skill 中心：列表 / 运行 / 上传 / 提交审核 / 审核 / 启停",
            subcommands = {
                    SkillCommands.ListCommand.class,
                    SkillCommands.RunCommand.class,
                    SkillCommands.UploadCommand.class,
                    SkillCommands.SubmitReviewCommand.class,
                    SkillCommands.ReviewCommand.class,
                    SkillCommands.EnableCommand.class,
                    SkillCommands.DisableCommand.class
            })
    public static class Skill extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "技能列表（可按状态/来源/启用过滤）")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--type", paramLabel = "<type>",
                description = "按类型过滤：INTAKE|RISK|APPROVAL|PROVISIONING")
        String type;

        @Option(names = "--status", paramLabel = "<status>",
                description = "DRAFT|PENDING_REVIEW|APPROVED|REJECTED|DISABLED")
        String status;

        @Option(names = "--source", paramLabel = "BUILTIN|UPLOADED", description = "按来源过滤")
        String source;

        @Option(names = "--enabled", paramLabel = "true|false", description = "按启用状态过滤")
        String enabled;

        @Override
        public Integer call() {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("type", type);
            query.put("status", status);
            query.put("source", source);
            query.put("enabled", enabled);
            JsonNode data = api().get("/api/skills", query);
            return emitItems(data, LIST_HEADERS, item -> row(
                    text(item, "id"),
                    text(item, "name"),
                    text(item, "version"),
                    text(item, "type"),
                    text(item, "source"),
                    text(item, "status"),
                    text(item, "enabled"),
                    join(Json.field(item, "triggers"), ",")));
        }
    }

    @Command(name = "run", mixinStandardHelpOptions = true, description = "执行技能规则引擎")
    public static class RunCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<skillId>", description = "技能 ID，如 risk-review")
        String skillId;

        @Option(names = {"--input", "-i"}, paramLabel = "<json>", description = "输入 JSON（默认 {}）")
        String input;

        @Option(names = "--tenant", paramLabel = "<tenantId>", description = "租户 ID")
        String tenantId;

        @Override
        public Integer call() {
            JsonNode inputNode = input == null || input.isBlank() ? Json.object() : Json.parse(input);
            if (!inputNode.isObject()) {
                throw new CliUsageException("--input 必须是 JSON 对象");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("input", inputNode);
            if (tenantId != null && !tenantId.isBlank()) {
                body.put("tenantId", tenantId);
            }
            JsonNode data = api().post("/api/skills/" + encode(skillId) + "/run", body);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("技能", "运行 ID", "结论", "评分", "决策", "模型", "耗时(ms)"),
                    List.of(
                            text(data, "skillId"), text(data, "runId"), text(data, "verdict"),
                            num(data, "score"), text(data, "decision"), text(data, "model"),
                            num(data, "durationMs"))));

            JsonNode findings = Json.field(data, "findings");
            if (findings.isArray() && !findings.isEmpty()) {
                out().println();
                out().println("Findings:");
                List<List<String>> rows = new ArrayList<>();
                findings.forEach(finding -> rows.add(row(
                        text(finding, "ruleId"), text(finding, "severity"), text(finding, "message"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("规则", "严重度", "消息"), rows));
            }
            JsonNode trace = Json.field(data, "trace");
            if (trace.isArray() && !trace.isEmpty()) {
                out().println();
                out().println("Trace:");
                List<List<String>> rows = new ArrayList<>();
                trace.forEach(item -> rows.add(row(
                        text(item, "ruleId"), text(item, "matched"), text(item, "reason"))));
                out().println(com.suhong.jiuding.cli.TableRenderer.render(
                        headers("规则", "命中", "原因"), rows));
            }
            return ExitCodes.OK;
        }
    }

    /** Shared rendering for skill-center mutations. */
    private abstract static class SkillRenderCommand extends BaseCommand {

        protected int emitSkill(JsonNode data, String title) {
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    List.of("操作", "技能 ID", "版本", "类型", "来源", "状态", "启用", "审核人", "更新时间"),
                    List.of(title,
                            text(data, "id"), text(data, "version"), text(data, "type"),
                            text(data, "source"), text(data, "status"), text(data, "enabled"),
                            text(data, "reviewedBy"), text(data, "updatedAt"))));
            return ExitCodes.OK;
        }
    }

    @Command(name = "upload", mixinStandardHelpOptions = true,
            description = "上传技能包 zip（含 SKILL.md + rules.yaml，≤1MB）")
    public static class UploadCommand extends SkillRenderCommand {

        @Option(names = {"--file", "-f"}, paramLabel = "<skill.zip>", required = true,
                description = "技能包 zip 文件")
        Path file;

        @Option(names = "--submit-for-review", description = "上传后直接提交审核（PENDING_REVIEW）")
        boolean submitForReview;

        @Override
        public Integer call() {
            if (!Files.isRegularFile(file)) {
                throw new CliUsageException("文件不存在: " + file);
            }
            if (!file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".zip")) {
                throw new CliUsageException("--file 必须是 .zip 技能包");
            }
            Map<String, String> fields = new LinkedHashMap<>();
            fields.put("submitForReview", String.valueOf(submitForReview));
            JsonNode data = api().postMultipart("/api/skills/upload", file, "file", fields);
            return emitSkill(data, "上传结果");
        }
    }

    @Command(name = "submit", mixinStandardHelpOptions = true, description = "提交技能审核")
    public static class SubmitReviewCommand extends SkillRenderCommand {

        @Parameters(index = "0", paramLabel = "<skillId>", description = "技能 ID")
        String skillId;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/skills/" + encode(skillId) + "/submit-review", new LinkedHashMap<>());
            return emitSkill(data, "已提交审核");
        }
    }

    @Command(name = "review", mixinStandardHelpOptions = true,
            description = "审核技能（仅平台管理员；APPROVE 后热加载生效）")
    public static class ReviewCommand extends SkillRenderCommand {

        @Parameters(index = "0", paramLabel = "<skillId>", description = "技能 ID")
        String skillId;

        @Option(names = {"--decision", "-d"}, paramLabel = "APPROVE|REJECT", required = true,
                description = "审核结论")
        String decision;

        @Option(names = {"--comment", "-m"}, paramLabel = "<text>", description = "审核意见")
        String comment;

        @Override
        public Integer call() {
            String normalized = decision == null ? "" : decision.trim().toUpperCase(Locale.ROOT);
            if (!REVIEW_DECISIONS.contains(normalized)) {
                throw new CliUsageException("--decision 只支持 APPROVE 或 REJECT");
            }
            if ("REJECT".equals(normalized) && (comment == null || comment.isBlank())) {
                throw new CliUsageException("REJECT 必须提供 --comment 审核意见");
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("decision", normalized);
            if (comment != null && !comment.isBlank()) {
                body.put("comment", comment);
            }
            JsonNode data = api().post("/api/skills/" + encode(skillId) + "/review", body);
            return emitSkill(data, "审核结果");
        }
    }

    @Command(name = "enable", mixinStandardHelpOptions = true, description = "启用已审核技能")
    public static class EnableCommand extends SkillRenderCommand {

        @Parameters(index = "0", paramLabel = "<skillId>", description = "技能 ID")
        String skillId;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/skills/" + encode(skillId) + "/enable", new LinkedHashMap<>());
            return emitSkill(data, "已启用");
        }
    }

    @Command(name = "disable", mixinStandardHelpOptions = true, description = "禁用技能")
    public static class DisableCommand extends SkillRenderCommand {

        @Parameters(index = "0", paramLabel = "<skillId>", description = "技能 ID")
        String skillId;

        @Override
        public Integer call() {
            JsonNode data = api().post("/api/skills/" + encode(skillId) + "/disable", new LinkedHashMap<>());
            return emitSkill(data, "已禁用");
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
