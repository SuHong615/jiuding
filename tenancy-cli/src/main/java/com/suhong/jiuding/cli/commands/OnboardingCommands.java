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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code tenancy onboarding ...} - public self-service onboarding (docs/15 §6.2). */
public final class OnboardingCommands {

    private OnboardingCommands() {
    }

    @Command(name = "onboarding", mixinStandardHelpOptions = true,
            description = "租户自助入驻：提交申请 / 查询状态（公开接口，无需登录）",
            subcommands = {OnboardingCommands.ApplyCommand.class, OnboardingCommands.StatusCommand.class})
    public static class Onboarding extends BaseCommand.Group {
    }

    @Command(name = "apply", mixinStandardHelpOptions = true, description = "提交自助入驻申请")
    public static class ApplyCommand extends BaseCommand {

        @Option(names = {"--company-name", "-c"}, paramLabel = "<name>", required = true, description = "公司名称")
        String companyName;

        @Option(names = "--tenant-code", paramLabel = "<code>", description = "期望租户代码（缺省自动生成）")
        String tenantCode;

        @Option(names = "--contact-name", paramLabel = "<name>", description = "联系人")
        String contactName;

        @Option(names = {"--contact-email", "-e"}, paramLabel = "<email>", required = true, description = "联系邮箱")
        String contactEmail;

        @Option(names = "--contact-phone", paramLabel = "<phone>", description = "联系电话")
        String contactPhone;

        @Option(names = "--plan", paramLabel = "FREE|PRO|ENTERPRISE", description = "计划（默认 PRO）")
        String plan;

        @Option(names = "--region", paramLabel = "<region>", description = "区域（默认 cn-shanghai）")
        String region;

        @Option(names = "--industry", paramLabel = "<industry>", description = "行业")
        String industry;

        @Option(names = "--notes", paramLabel = "<text>", description = "备注")
        String notes;

        @Override
        public Integer call() {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("companyName", companyName);
            body.put("contactEmail", contactEmail);
            putIfPresent(body, "tenantCode", tenantCode);
            putIfPresent(body, "contactName", contactName);
            putIfPresent(body, "contactPhone", contactPhone);
            putIfPresent(body, "plan", plan);
            putIfPresent(body, "region", region);
            putIfPresent(body, "industry", industry);
            putIfPresent(body, "notes", notes);
            // Public endpoint: works without a token; api() only adds Authorization when one is configured.
            JsonNode data = api().post("/api/public/onboarding", body);
            return emitDetail(data,
                    List.of("申请编号", "租户代码", "状态", "提示"),
                    List.of(text(data, "applicationNo"), text(data, "tenantCode"),
                            text(data, "status"), text(data, "message")));
        }
    }

    @Command(name = "status", mixinStandardHelpOptions = true, description = "按申请编号查询入驻状态")
    public static class StatusCommand extends BaseCommand {

        @Parameters(index = "0", paramLabel = "<applicationNo>", description = "申请编号")
        String applicationNo;

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/public/onboarding/" + encode(applicationNo), null);
            return emitDetail(data,
                    List.of("申请编号", "租户代码", "状态", "提示"),
                    List.of(text(data, "applicationNo"), text(data, "tenantCode"),
                            text(data, "status"), text(data, "message")));
        }
    }

    private static void putIfPresent(Map<String, Object> body, String key, String value) {
        if (value != null && !value.isBlank()) {
            body.put(key, value);
        }
    }

    private static String encode(String segment) {
        return URLEncoder.encode(segment, StandardCharsets.UTF_8);
    }
}
