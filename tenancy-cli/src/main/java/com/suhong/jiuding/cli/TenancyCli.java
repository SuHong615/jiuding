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

import com.suhong.jiuding.cli.commands.AccountCommands;
import com.suhong.jiuding.cli.commands.AgentCommands;
import com.suhong.jiuding.cli.commands.ApprovalCommands;
import com.suhong.jiuding.cli.commands.AuditCommands;
import com.suhong.jiuding.cli.commands.AuthCommands;
import com.suhong.jiuding.cli.commands.CollectCommands;
import com.suhong.jiuding.cli.commands.DoctorCommand;
import com.suhong.jiuding.cli.commands.McpCommands;
import com.suhong.jiuding.cli.commands.MeCommands;
import com.suhong.jiuding.cli.commands.OnboardingCommands;
import com.suhong.jiuding.cli.commands.OrgCommands;
import com.suhong.jiuding.cli.commands.RatingCommands;
import com.suhong.jiuding.cli.commands.RolesCommands;
import com.suhong.jiuding.cli.commands.SkillCommands;
import com.suhong.jiuding.cli.commands.TenantCommands;
import com.suhong.jiuding.cli.commands.VersionCommand;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Mixin;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.Callable;

/**
 * Root command of the multi-tenant center CLI.
 *
 * <pre>
 * jiuding [--base-url URL] [--token JWT] [--tenant ID] [--api-key KEY]
 *         [--output table|json] [--config PATH] [--timeout SECONDS] &lt;command&gt;
 * </pre>
 */
@Command(
        name = "jiuding",
        aliases = {"tenancy"},
        mixinStandardHelpOptions = true,
        version = TenancyCli.VERSION,
        description = "九鼎 · 多租户智能中枢 CLI：租户/组织/账号/评级/审计/角色权限、采集、审批、Skill 中心、Agent、MCP 控制台",
        subcommands = {
                AuthCommands.Auth.class,
                TenantCommands.Tenant.class,
                OrgCommands.Org.class,
                AccountCommands.Account.class,
                RatingCommands.Rating.class,
                AuditCommands.Audit.class,
                RolesCommands.Roles.class,
                MeCommands.Me.class,
                OnboardingCommands.Onboarding.class,
                CollectCommands.Collect.class,
                ApprovalCommands.Approval.class,
                SkillCommands.Skill.class,
                AgentCommands.Agent.class,
                McpCommands.Mcp.class,
                DoctorCommand.class,
                VersionCommand.class
        },
        sortOptions = false,
        usageHelpAutoWidth = true)
public class TenancyCli implements Callable<Integer> {

    public static final String VERSION = "0.9.0";

    @Mixin
    public GlobalOptions global = new GlobalOptions();

    @Spec
    public CommandSpec spec;

    private CliContext context;

    @Override
    public Integer call() {
        spec.commandLine().usage(spec.commandLine().getOut());
        return ExitCodes.OK;
    }

    public CliContext context() {
        if (context == null) {
            context = CliContext.resolve(global);
        }
        return context;
    }

    public static void main(String[] args) {
        // Always emit UTF-8 on stdout/stderr so Chinese tables are not mangled by the
        // platform default charset (GBK on zh-CN Windows).
        PrintWriter out = new PrintWriter(new java.io.OutputStreamWriter(System.out, StandardCharsets.UTF_8), true);
        PrintWriter err = new PrintWriter(new java.io.OutputStreamWriter(System.err, StandardCharsets.UTF_8), true);
        int exitCode = new CliRunner().execute(args, out, err);
        out.flush();
        err.flush();
        System.exit(exitCode);
    }

    /** Package-visible factory used by tests. */
    static CommandLine commandLine() {
        return new CommandLine(new TenancyCli());
    }
}
