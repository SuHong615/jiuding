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

import static com.suhong.jiuding.cli.BaseCommand.headers;
import static com.suhong.jiuding.cli.BaseCommand.join;
import static com.suhong.jiuding.cli.BaseCommand.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;

import java.util.List;

/** {@code tenancy me permissions} - current user's roles and permission codes (docs/15 §7.2). */
public final class MeCommands {

    private MeCommands() {
    }

    @Command(name = "me", mixinStandardHelpOptions = true, description = "当前身份：权限查询",
            subcommands = {MeCommands.PermissionsCommand.class})
    public static class Me extends BaseCommand.Group {
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "查看当前用户的角色与权限")
    public static class PermissionsCommand extends BaseCommand {

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/me/permissions", null);
            if (jsonOutput()) {
                out().println(Json.pretty(data));
                return ExitCodes.OK;
            }
            JsonNode permissions = Json.field(data, "permissions");
            out().println(com.suhong.jiuding.cli.TableRenderer.detail(
                    headers("角色", "权限数量", "权限码"),
                    List.of(join(Json.field(data, "roles"), ","),
                            String.valueOf(permissions.isArray() ? permissions.size() : 0),
                            truncate(join(permissions, ","), 120))));
            return ExitCodes.OK;
        }
    }
}
