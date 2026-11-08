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

import java.nio.file.Path;
import java.util.Locale;

/** Resolved configuration for one CLI invocation (options &gt; env &gt; config file &gt; defaults). */
public final class CliContext {

    public static final String DEFAULT_BASE_URL = "http://localhost:8080";
    public static final String DEFAULT_API_KEY = "dev-mcp-key";
    private static final int DEFAULT_TIMEOUT_SECONDS = 30;

    private final String baseUrl;
    private final String token;
    private final String tenantId;
    private final String apiKey;
    private final String output;
    private final Path configPath;
    private final int timeoutSeconds;
    private final ApiClient apiClient;
    private final ConfigStore configStore;

    private CliContext(String baseUrl, String token, String tenantId, String apiKey, String output,
                       Path configPath, int timeoutSeconds) {
        this.baseUrl = baseUrl;
        this.token = token;
        this.tenantId = tenantId;
        this.apiKey = apiKey;
        this.output = output;
        this.configPath = configPath;
        this.timeoutSeconds = timeoutSeconds;
        this.apiClient = new ApiClient(baseUrl, token, tenantId, timeoutSeconds);
        this.configStore = new ConfigStore(configPath);
    }

    public static CliContext resolve(GlobalOptions options) {
        GlobalOptions global = options == null ? new GlobalOptions() : options;
        Path configPath = ConfigStore.resolvePath(global.config);
        CliConfig config = new ConfigStore(configPath).load();

        String baseUrl = firstNonBlank(global.baseUrl, env("TENANCY_BASE_URL"), config.baseUrl, DEFAULT_BASE_URL);
        baseUrl = normalizeBaseUrl(baseUrl);
        String token = firstNonBlank(global.token, env("TENANCY_TOKEN"), config.token);
        String tenantId = firstNonBlank(global.tenant, env("TENANCY_TENANT"), config.tenantId);
        String apiKey = firstNonBlank(global.apiKey, env("TENANCY_API_KEY"), config.apiKey);
        String output = firstNonBlank(global.output, "table").toLowerCase(Locale.ROOT);
        if (!output.equals("table") && !output.equals("json")) {
            throw new CliUsageException("--output 只支持 table 或 json，当前为: " + output);
        }
        int timeout = global.timeout != null ? global.timeout : DEFAULT_TIMEOUT_SECONDS;
        if (timeout <= 0) {
            throw new CliUsageException("--timeout 必须为正整数（秒）");
        }
        return new CliContext(baseUrl, token, tenantId, apiKey, output, configPath, timeout);
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String token() {
        return token;
    }

    public String tenantId() {
        return tenantId;
    }

    public String apiKey() {
        return apiKey == null ? DEFAULT_API_KEY : apiKey;
    }

    public String output() {
        return output;
    }

    public boolean json() {
        return "json".equals(output);
    }

    public Path configPath() {
        return configPath;
    }

    public int timeoutSeconds() {
        return timeoutSeconds;
    }

    public ApiClient api() {
        return apiClient;
    }

    /** Returns a client that scopes every request to the given tenant via {@code X-Tenant-Id}. */
    public ApiClient apiWithTenant(String tenantId) {
        return new ApiClient(baseUrl, token, tenantId, timeoutSeconds);
    }

    public ConfigStore configStore() {
        return configStore;
    }

    private static String normalizeBaseUrl(String url) {
        String trimmed = url.trim();
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            throw new CliUsageException("--base-url 必须以 http:// 或 https:// 开头: " + url);
        }
        return trimmed.endsWith("/") ? trimmed.substring(0, trimmed.length() - 1) : trimmed;
    }

    private static String env(String name) {
        return System.getenv(name);
    }

    private static String firstNonBlank(String... candidates) {
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank()) {
                return candidate;
            }
        }
        return null;
    }
}
