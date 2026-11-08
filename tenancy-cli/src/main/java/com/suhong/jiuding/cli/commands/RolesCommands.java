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
import static com.suhong.jiuding.cli.BaseCommand.row;
import static com.suhong.jiuding.cli.BaseCommand.text;

import com.fasterxml.jackson.databind.JsonNode;
import com.suhong.jiuding.cli.BaseCommand;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@code tenancy roles ...} - role and permission catalog (docs/15 §7.2). */
public final class RolesCommands {

    private RolesCommands() {
    }

    @Command(name = "roles", mixinStandardHelpOptions = true, description = "角色与权限：角色列表 / 权限目录",
            subcommands = {RolesCommands.ListCommand.class, RolesCommands.PermissionsCommand.class})
    public static class Roles extends BaseCommand.Group {
    }

    @Command(name = "list", mixinStandardHelpOptions = true, description = "平台角色列表（仅平台管理员）")
    public static class ListCommand extends BaseCommand {

        @Option(names = "--scope", paramLabel = "PLATFORM|TENANT", description = "按作用域过滤")
        String scope;

        @Override
        public Integer call() {
            Map<String, String> query = new LinkedHashMap<>();
            query.put("scope", scope);
            JsonNode data = api().get("/api/platform/roles", query);
            return emitItems(data,
                    headers("Code", "名称", "作用域", "内置", "状态", "说明"),
                    node -> row(text(node, "code"), text(node, "name"), text(node, "scope"),
                            text(node, "builtin"), text(node, "status"), text(node, "description")));
        }
    }

    @Command(name = "permissions", mixinStandardHelpOptions = true, description = "权限目录（仅平台管理员）")
    public static class PermissionsCommand extends BaseCommand {

        @Override
        public Integer call() {
            JsonNode data = api().get("/api/platform/permissions", null);
            return emitItems(data,
                    headers("权限码", "名称", "分组", "说明"),
                    node -> row(text(node, "code"), text(node, "name"),
                            text(node, "groupCode"), text(node, "description")));
        }
    }
}
