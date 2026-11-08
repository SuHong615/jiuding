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
package com.suhong.jiuding.mcp.external;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.support.Iso8601;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * External MCP server registry (docs/15 §4.2). Only platform administrators may write;
 * any authenticated caller may list.
 */
@Service
public class ExternalServerService {

    private static final Logger log = LoggerFactory.getLogger(ExternalServerService.class);
    private static final Pattern TRANSPORT = Pattern.compile("^(STREAMABLE_HTTP|SSE)$");

    private final ExternalServerRepository repository;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final McpProperties properties;

    public ExternalServerService(ExternalServerRepository repository, HttpClient httpClient,
                                 ObjectMapper objectMapper, McpProperties properties) {
        this.repository = repository;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public List<ExternalServerView> list() {
        return repository.list().stream().map(ExternalServerView::of).toList();
    }

    public ExternalServerView create(Principal actor, ExternalServerCreateRequest request) {
        requireAdmin(actor);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        String name = trim(request.name());
        if (name == null) {
            throw BizException.invalid("name is required");
        }
        String transport = trim(request.transport());
        if (transport == null || !TRANSPORT.matcher(transport).matches()) {
            throw BizException.invalid("transport must be STREAMABLE_HTTP or SSE");
        }
        String url = trim(request.url());
        if (url == null || !(url.startsWith("http://") || url.startsWith("https://"))) {
            throw BizException.invalid("url must be an http(s) URL");
        }
        String headersJson = "{}";
        if (request.headers() != null && !request.headers().isEmpty()) {
            try {
                headersJson = objectMapper.writeValueAsString(request.headers());
            } catch (Exception ex) {
                throw BizException.invalid("headers must be a JSON object");
            }
        }
        ExternalServerRow row = new ExternalServerRow(UUID.randomUUID().toString(), name, transport, url,
                headersJson, "ACTIVE", actor.username(), LocalDateTime.now(), null, null);
        repository.insert(row);
        return ExternalServerView.of(row);
    }

    public void delete(Principal actor, String id) {
        requireAdmin(actor);
        ExternalServerRow row = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("external MCP server not found: " + id));
        repository.delete(row.id());
    }

    public ExternalCheckResult check(Principal actor, String id) {
        requireAdmin(actor);
        ExternalServerRow row = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("external MCP server not found: " + id));
        long start = System.nanoTime();
        boolean ok = false;
        Integer statusCode = null;
        String message = "unreachable";
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(row.url()))
                    .timeout(Duration.ofMillis(properties.getExternalCheckTimeoutMs()))
                    .header("Accept", "application/json, text/event-stream")
                    .GET();
            for (Map.Entry<String, String> header : parseHeaders(row.headersJson()).entrySet()) {
                try {
                    builder.header(header.getKey(), header.getValue());
                } catch (IllegalArgumentException ex) {
                    log.debug("ignored restricted header {} for external MCP check", header.getKey());
                }
            }
            HttpResponse<Void> response = httpClient.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            statusCode = response.statusCode();
            ok = statusCode >= 200 && statusCode < 300;
            message = ok ? "reachable" : "unexpected status " + statusCode;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            message = "interrupted";
        } catch (Exception ex) {
            message = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            log.debug("external MCP connectivity check failed for {}: {}", row.id(), ex.toString());
        }
        long latencyMs = (System.nanoTime() - start) / 1_000_000L;
        LocalDateTime checkedAt = LocalDateTime.now();
        repository.updateCheck(row.id(), checkedAt, ok);
        return new ExternalCheckResult(row.id(), ok, statusCode, latencyMs, message, Iso8601.format(checkedAt));
    }

    private Map<String, String> parseHeaders(String headersJson) {
        if (headersJson == null || headersJson.isBlank()) {
            return Map.of();
        }
        try {
            JsonNode root = objectMapper.readTree(headersJson);
            Map<String, String> headers = new LinkedHashMap<>();
            if (root != null && root.isObject()) {
                root.fields().forEachRemaining(entry -> headers.put(entry.getKey(), entry.getValue().asText()));
            }
            return headers;
        } catch (Exception ex) {
            return Map.of();
        }
    }

    private static void requireAdmin(Principal actor) {
        if (actor == null || !actor.isPlatformAdmin()) {
            throw BizException.forbidden("only platform administrators can manage external MCP servers");
        }
    }

    private static String trim(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
