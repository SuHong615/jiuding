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

import com.suhong.jiuding.cli.commands.TenantCommands;
import org.junit.jupiter.api.Test;
import picocli.CommandLine;
import picocli.CommandLine.ParseResult;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Command tree and option parsing. */
class CliParsingTest {

    @Test
    void parsesGlobalOptionsAndNestedSubcommands() {
        CommandLine commandLine = TenancyCli.commandLine();
        ParseResult result = commandLine.parseArgs(
                "--base-url", "http://127.0.0.1:9999",
                "--output", "json",
                "--tenant", "t-acme",
                "tenant", "list", "--status", "ACTIVE", "--size", "5");

        TenancyCli root = (TenancyCli) commandLine.getCommand();
        assertEquals("http://127.0.0.1:9999", root.global.baseUrl);
        assertEquals("json", root.global.output);
        assertEquals("t-acme", root.global.tenant);

        ParseResult tenant = result.subcommand();
        assertEquals("tenant", tenant.commandSpec().name());
        ParseResult list = tenant.subcommand();
        assertEquals("list", list.commandSpec().name());
        assertInstanceOf(TenantCommands.ListCommand.class, list.commandSpec().userObject());
        assertEquals("ACTIVE", list.matchedOption("--status").getValue());
    }

    @Test
    void rootCommandExposesRequiredGlobalOptions() {
        CommandLine commandLine = TenancyCli.commandLine();
        assertTrue(commandLine.getCommandSpec().findOption("--base-url") != null);
        assertTrue(commandLine.getCommandSpec().findOption("--token") != null);
        assertTrue(commandLine.getCommandSpec().findOption("--tenant") != null);
        assertTrue(commandLine.getCommandSpec().findOption("--api-key") != null);
        assertTrue(commandLine.getCommandSpec().findOption("--output") != null);
    }

    @Test
    void topLevelCommandTreeContainsEveryGroup() {
        CommandLine root = TenancyCli.commandLine();
        for (String name : new String[]{"auth", "tenant", "org", "account", "rating", "collect",
                "approval", "skill", "agent", "mcp", "doctor", "version"}) {
            assertTrue(root.getSubcommands().containsKey(name), "missing subcommand: " + name);
        }
        assertTrue(root.getSubcommands().get("tenant").getSubcommands()
                .keySet().containsAll(java.util.List.of(
                        "list", "get", "create", "submit", "suspend", "activate", "terminate", "delete")));
        assertTrue(root.getSubcommands().get("org").getSubcommands()
                .keySet().containsAll(java.util.List.of("list", "tree", "create", "move", "delete")));
        assertTrue(root.getSubcommands().get("account").getSubcommands()
                .keySet().containsAll(java.util.List.of(
                        "list", "create", "disable", "enable", "reset-password", "delete")));
        assertTrue(root.getSubcommands().get("rating").getSubcommands()
                .keySet().containsAll(java.util.List.of("current", "history", "assess", "override")));
        assertTrue(root.getSubcommands().get("mcp").getSubcommands()
                .get("tools").getSubcommands().keySet().containsAll(java.util.List.of("list", "call")));
    }

    @Test
    void helpExitsZero() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"--help"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.OK, exit);
        assertTrue(out.toString().contains("tenancy"));
        assertTrue(out.toString().contains("tenant"));
    }

    @Test
    void unknownCommandExitsWithUsageCode() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"nope"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.USAGE, exit);
        assertTrue(err.toString().contains("参数错误"));
    }

    @Test
    void missingRequiredOptionExitsWithUsageCode() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"auth", "login"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.USAGE, exit);
    }

    @Test
    void invalidOutputFormatExitsWithUsageCode() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"--output", "xml", "version"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.USAGE, exit);
    }

    @Test
    void groupCommandWithoutSubcommandPrintsUsage() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"tenant"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.OK, exit);
        assertTrue(out.toString().contains("tenant"));
    }

    @Test
    void versionCommandReportsCliVersion() {
        StringWriter out = new StringWriter();
        StringWriter err = new StringWriter();
        int exit = new CliRunner().execute(new String[]{"--output", "json", "version"},
                new PrintWriter(out, true), new PrintWriter(err, true));
        assertEquals(ExitCodes.OK, exit);
        assertTrue(out.toString().contains(TenancyCli.VERSION));
        assertTrue(out.toString().contains("cliVersion"));
    }
}
