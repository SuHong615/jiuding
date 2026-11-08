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
import com.suhong.jiuding.cli.CliContext;
import com.suhong.jiuding.cli.ExitCodes;
import com.suhong.jiuding.cli.Json;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * {@code tenancy doctor} - checks the gateway health endpoint plus TCP reachability of
 * the four downstream services (8081-8084). Exit codes: 0 all up, 4 partial, 5 gateway
 * unreachable.
 */
@Command(name = "doctor", description = "检查网关健康与各服务端口可达性")
public class DoctorCommand extends BaseCommand {

    private static final int TCP_TIMEOUT_MS = 1200;

    @Option(names = "--timeout", paramLabel = "<seconds>", description = "单次探测超时秒数（默认 3）")
    Integer probeTimeout;

    @Override
    public Integer call() {
        CliContext context = ctx();
        int timeoutSeconds = probeTimeout != null ? Math.max(1, probeTimeout) : 3;
        String host = hostOf(context.baseUrl());
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(timeoutSeconds)).build();

        List<Check> checks = new ArrayList<>();
        Check gateway = probeHttp(http, "gateway", context.baseUrl() + "/actuator/health", timeoutSeconds);
        if (!"UP".equals(gateway.status())) {
            Check platformHealth = probeHttp(http, "gateway", context.baseUrl() + "/api/platform/health",
                    timeoutSeconds);
            if ("UP".equals(platformHealth.status()) || platformHealth.httpStatus() == 401
                    || platformHealth.httpStatus() == 403) {
                gateway = platformHealth;
            }
        }
        checks.add(gateway);
        checks.add(probeTcp("tenant-service", host, 8081));
        checks.add(probeTcp("approval-service", host, 8082));
        checks.add(probeTcp("agent-service", host, 8083));
        checks.add(probeTcp("mcp-service", host, 8084));

        boolean gatewayUp = "UP".equals(gateway.status());
        boolean allUp = gatewayUp;
        for (Check check : checks) {
            allUp = allUp && "UP".equals(check.status());
        }

        com.fasterxml.jackson.databind.node.ObjectNode data = Json.object();
        data.put("baseUrl", context.baseUrl());
        data.put("configPath", context.configPath().toString());
        data.put("tokenConfigured", context.token() != null);
        data.put("apiKeyConfigured", context.apiKey() != null);
        data.put("allUp", allUp);
        com.fasterxml.jackson.databind.node.ArrayNode services = Json.mapper().createArrayNode();
        for (Check check : checks) {
            com.fasterxml.jackson.databind.node.ObjectNode node = Json.object();
            node.put("name", check.name());
            node.put("url", check.target());
            node.put("status", check.status());
            node.put("latencyMs", check.latencyMs());
            if (check.detail() != null) {
                node.put("detail", check.detail());
            }
            services.add(node);
        }
        data.set("services", services);

        if (jsonOutput()) {
            out().println(Json.pretty(data));
        } else {
            List<List<String>> rows = new ArrayList<>();
            for (Check check : checks) {
                rows.add(row(check.name(), check.target(), check.status(),
                        String.valueOf(check.latencyMs()), check.detail()));
            }
            out().println(com.suhong.jiuding.cli.TableRenderer.render(
                    headers("检查项", "地址", "状态", "耗时(ms)", "说明"), rows));
            out().println();
            out().println("网关: " + (gatewayUp ? "UP" : "DOWN")
                    + "，配置文件: " + context.configPath()
                    + "，token: " + (context.token() != null ? "已配置" : "未配置")
                    + "，api-key: " + (context.apiKey() != null ? "已配置" : "未配置"));
            if (!gatewayUp) {
                out().println("提示: 请先执行 scripts/run-local.ps1 或 docker compose up，再重试。");
            }
        }
        if (!gatewayUp) {
            return ExitCodes.NETWORK;
        }
        return allUp ? ExitCodes.OK : ExitCodes.BUSINESS;
    }

    private Check probeHttp(HttpClient http, String name, String url, int timeoutSeconds) {
        long start = System.nanoTime();
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Accept", "application/json");
            if (ctx().token() != null) {
                builder.header("Authorization", "Bearer " + ctx().token());
            }
            HttpResponse<String> response = http.send(builder.GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            long latency = (System.nanoTime() - start) / 1_000_000;
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                String body = response.body() == null ? "" : response.body();
                String status = body.contains("\"status\":\"DOWN\"") ? "DOWN" : "UP";
                return new Check(name, url, status, latency, "HTTP " + response.statusCode(), response.statusCode());
            }
            return new Check(name, url, "DOWN", latency, "HTTP " + response.statusCode(), response.statusCode());
        } catch (Exception e) {
            return new Check(name, url, "DOWN", (System.nanoTime() - start) / 1_000_000,
                    e.getClass().getSimpleName() + ": " + e.getMessage(), -1);
        }
    }

    private Check probeTcp(String name, String host, int port) {
        long start = System.nanoTime();
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), TCP_TIMEOUT_MS);
            return new Check(name, host + ":" + port, "UP",
                    (System.nanoTime() - start) / 1_000_000, "TCP 可达", port);
        } catch (Exception e) {
            return new Check(name, host + ":" + port, "DOWN",
                    (System.nanoTime() - start) / 1_000_000, "TCP 不可达", -1);
        }
    }

    private static String hostOf(String baseUrl) {
        try {
            String host = URI.create(baseUrl).getHost();
            return host == null ? "127.0.0.1" : host;
        } catch (IllegalArgumentException e) {
            return "127.0.0.1";
        }
    }

    private record Check(String name, String target, String status, long latencyMs, String detail, int httpStatus) {
    }
}
