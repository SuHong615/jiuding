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

import com.fasterxml.jackson.databind.JsonNode;
import picocli.CommandLine.Model.CommandSpec;
import picocli.CommandLine.Spec;

import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.concurrent.Callable;

/** Base class for leaf commands: gives access to resolved context, HTTP client and output helpers. */
public abstract class BaseCommand implements Callable<Integer> {

    @Spec
    protected CommandSpec spec;

    protected TenancyCli root() {
        return (TenancyCli) spec.root().userObject();
    }

    protected CliContext ctx() {
        return root().context();
    }

    protected ApiClient api() {
        return ctx().api();
    }

    /**
     * Resolves the tenant scope for org/account/rating commands: the command-local
     * {@code --tenant} option wins, then the global {@code --tenant}.
     */
    protected String requireTenant(String localTenant) {
        String tenant = localTenant != null && !localTenant.isBlank() ? localTenant : ctx().tenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new CliUsageException("缺少租户 ID：请使用 --tenant <tenantId>（或全局 --tenant）");
        }
        return tenant.trim();
    }

    /** HTTP client whose {@code X-Tenant-Id} header is set to the resolved tenant. */
    protected ApiClient apiForTenant(String tenantId) {
        return ctx().apiWithTenant(tenantId);
    }

    public static String encPath(String segment) {
        return java.net.URLEncoder.encode(segment, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    protected PrintWriter out() {
        return spec.commandLine().getOut();
    }

    protected boolean jsonOutput() {
        return ctx().json();
    }

    /** Prints either the raw `data` (JSON mode) or the supplied table (table mode). */
    protected int emit(JsonNode data, List<String> headers, List<List<String>> rows) {
        if (jsonOutput()) {
            out().println(Json.pretty(data));
        } else {
            out().println(TableRenderer.render(headers, rows));
        }
        return ExitCodes.OK;
    }

    /**
     * Renders a list-ish payload: JSON mode prints the raw data untouched, table mode maps
     * every item of {@code data.items} (or a bare array) to a row.
     */
    protected int emitItems(JsonNode data, List<String> headers, Function<JsonNode, List<String>> mapper) {
        if (jsonOutput()) {
            out().println(Json.pretty(data));
            return ExitCodes.OK;
        }
        List<List<String>> rows = new ArrayList<>();
        Json.items(data).forEach(item -> rows.add(mapper.apply(item)));
        out().println(TableRenderer.render(headers, rows));
        if (data != null && data.isObject() && data.has("total")) {
            out().println("共 " + data.get("total").asText() + " 条，当前 " + rows.size() + " 条");
        }
        return ExitCodes.OK;
    }

    /** Prints a two-column detail table (table mode) or the raw data (JSON mode). */
    protected int emitDetail(JsonNode data, List<String> labels, List<String> values) {
        if (jsonOutput()) {
            out().println(Json.pretty(data));
        } else {
            out().println(TableRenderer.detail(labels, values));
        }
        return ExitCodes.OK;
    }

    protected void printInfo(String message) {
        if (!jsonOutput()) {
            out().println(message);
        }
    }

    public static List<String> headers(String... names) {
        return new ArrayList<>(Arrays.asList(names));
    }

    public static List<String> row(String... cells) {
        List<String> row = new ArrayList<>(cells.length);
        for (String cell : cells) {
            row.add(cell == null || cell.isEmpty() ? "-" : cell);
        }
        return row;
    }

    /** Text or "-" for a field of a JSON object. */
    public static String text(JsonNode node, String field) {
        return text(node, field, "-");
    }

    public static String text(JsonNode node, String field, String fallback) {
        return Json.text(node, field, fallback);
    }

    public static String num(JsonNode node, String field) {
        if (node == null || !node.has(field) || node.get(field).isNull()) {
            return "-";
        }
        return node.get(field).asText();
    }

    public static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            Object value = keyValues[i + 1];
            if (value != null) {
                map.put(String.valueOf(keyValues[i]), value);
            }
        }
        return map;
    }

    public static String join(JsonNode array, String separator) {
        if (array == null || !array.isArray() || array.isEmpty()) {
            return "-";
        }
        List<String> parts = new ArrayList<>();
        array.forEach(item -> parts.add(item.isTextual() ? item.asText() : item.toString()));
        return String.join(separator, parts);
    }

    public static String truncate(String value, int maxWidth) {
        return TableRenderer.truncate(value, maxWidth);
    }

    /** Group command base: prints its own usage when invoked without a subcommand. */
    public abstract static class Group implements Callable<Integer> {

        @Spec
        protected CommandSpec spec;

        @Override
        public Integer call() {
            spec.commandLine().usage(spec.commandLine().getOut());
            return ExitCodes.OK;
        }
    }
}
