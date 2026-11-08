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
package com.suhong.jiuding.mcp.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.api.ApiResponse;
import com.suhong.jiuding.common.api.PageResult;
import com.suhong.jiuding.common.security.AuthContext;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.mcp.config.McpProperties;
import com.suhong.jiuding.mcp.credentials.ApiKeyCreateRequest;
import com.suhong.jiuding.mcp.credentials.ApiKeyCreated;
import com.suhong.jiuding.mcp.credentials.ApiKeyService;
import com.suhong.jiuding.mcp.credentials.ApiKeyView;
import com.suhong.jiuding.mcp.credentials.McpCallAuditRepository;
import com.suhong.jiuding.mcp.external.ExternalCheckResult;
import com.suhong.jiuding.mcp.external.ExternalServerCreateRequest;
import com.suhong.jiuding.mcp.external.ExternalServerService;
import com.suhong.jiuding.mcp.external.ExternalServerView;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * MCP console API behind the gateway ({@code /api/mcp/**}, docs/15 §4.2):
 * copy-ready MCP config, personal API key lifecycle, call audits and external MCP servers.
 *
 * <p>JWT authentication is enforced by the shared {@code AuthFilter}; platform administrators
 * see all keys/audits, other authenticated users only their own.
 */
@RestController
@RequestMapping("/api/mcp")
public class McpConfigController {

    private static final int MAX_PAGE_SIZE = 200;

    private final McpProperties properties;
    private final ApiKeyService apiKeyService;
    private final McpCallAuditRepository auditRepository;
    private final ExternalServerService externalServerService;
    private final ObjectMapper objectMapper;

    public McpConfigController(McpProperties properties, ApiKeyService apiKeyService,
                               McpCallAuditRepository auditRepository,
                               ExternalServerService externalServerService, ObjectMapper objectMapper) {
        this.properties = properties;
        this.apiKeyService = apiKeyService;
        this.auditRepository = auditRepository;
        this.externalServerService = externalServerService;
        this.objectMapper = objectMapper;
    }

    /** {@code GET /api/mcp/config}: everything the console needs to render a copy-ready snippet. */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        Principal principal = AuthContext.require();
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put("X-API-Key", "<your-personal-api-key>");
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("url", properties.getPublicEndpoint());
        server.put("headers", headers);
        Map<String, Object> mcpServers = new LinkedHashMap<>();
        mcpServers.put("tenancy-center", server);
        Map<String, Object> sampleConfig = new LinkedHashMap<>();
        sampleConfig.put("mcpServers", mcpServers);
        try {
            // Round-trip through Jackson so the snippet is always valid JSON.
            sampleConfig = objectMapper.convertValue(sampleConfig, Map.class);
        } catch (RuntimeException ignored) {
            // keep the map as built
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("endpoint", properties.getPublicEndpoint());
        body.put("transport", "JSON-RPC 2.0 over HTTP + SSE");
        body.put("protocolVersion", properties.getProtocolVersion());
        body.put("sampleConfig", sampleConfig);
        body.put("keyHint", "Create a personal API key below (prefix tk_); the clear-text key is shown only once. "
                + "Send it as X-API-Key when connecting MCP clients.");
        body.put("docs", properties.getDocsUrl());
        body.put("authenticatedAs", principal.username());
        body.put("platformAdmin", principal.isPlatformAdmin());
        return ApiResponse.ok(body);
    }

    @GetMapping("/keys")
    public ApiResponse<List<ApiKeyView>> keys(@RequestParam(defaultValue = "false") boolean all) {
        return ApiResponse.ok(apiKeyService.list(AuthContext.require(), all));
    }

    @PostMapping("/keys")
    public ApiResponse<ApiKeyCreated> createKey(@RequestBody(required = false) ApiKeyCreateRequest request) {
        return ApiResponse.ok(apiKeyService.create(AuthContext.require(), request));
    }

    @DeleteMapping("/keys/{id}")
    public ApiResponse<ApiKeyView> revokeKey(@PathVariable String id) {
        return ApiResponse.ok(apiKeyService.revoke(AuthContext.require(), id));
    }

    @GetMapping("/audits")
    public ApiResponse<PageResult<McpCallAuditRepository.AuditView>> audits(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) String tool,
            @RequestParam(required = false) Boolean success) {
        Principal actor = AuthContext.require();
        int safePage = Math.max(0, page);
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        String owner = actor.isPlatformAdmin() ? null : actor.username();
        return ApiResponse.ok(auditRepository.search(owner, tool, success, safePage, safeSize));
    }

    @GetMapping("/external-servers")
    public ApiResponse<List<ExternalServerView>> externalServers() {
        AuthContext.require();
        return ApiResponse.ok(externalServerService.list());
    }

    @PostMapping("/external-servers")
    public ApiResponse<ExternalServerView> createExternalServer(
            @RequestBody(required = false) ExternalServerCreateRequest request) {
        return ApiResponse.ok(externalServerService.create(AuthContext.require(), request));
    }

    @DeleteMapping("/external-servers/{id}")
    public ApiResponse<Map<String, Object>> deleteExternalServer(@PathVariable String id) {
        externalServerService.delete(AuthContext.require(), id);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", id);
        body.put("deleted", true);
        return ApiResponse.ok(body);
    }

    @PostMapping("/external-servers/{id}/check")
    public ApiResponse<ExternalCheckResult> checkExternalServer(@PathVariable String id) {
        return ApiResponse.ok(externalServerService.check(AuthContext.require(), id));
    }
}
