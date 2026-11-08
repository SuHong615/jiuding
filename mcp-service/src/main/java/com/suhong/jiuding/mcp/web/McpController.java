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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.suhong.jiuding.mcp.credentials.McpAuditService;
import com.suhong.jiuding.mcp.downstream.DownstreamException;
import com.suhong.jiuding.mcp.protocol.JsonRpcException;
import com.suhong.jiuding.mcp.protocol.JsonRpcResponse;
import com.suhong.jiuding.mcp.protocol.McpCallContext;
import com.suhong.jiuding.mcp.protocol.McpDispatcher;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * MCP transport endpoints (docs/05 §传输):
 * <ul>
 *   <li>{@code POST /mcp} – JSON-RPC 2.0 request/response, notifications → 202</li>
 *   <li>{@code GET /mcp} – SSE stream with heartbeat and {@code X-Accel-Buffering:no}</li>
 *   <li>{@code GET /mcp/info} – capability snapshot for the frontend console</li>
 * </ul>
 * Authentication (X-API-Key / Bearer JWT) is enforced by the shared AuthFilter.
 */
@RestController
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);

    private final ObjectMapper objectMapper;
    private final McpDispatcher dispatcher;
    private final McpInfoService infoService;
    private final McpSseService sseService;
    private final McpAuditService auditService;

    public McpController(ObjectMapper objectMapper, McpDispatcher dispatcher,
                         McpInfoService infoService, McpSseService sseService,
                         McpAuditService auditService) {
        this.objectMapper = objectMapper;
        this.dispatcher = dispatcher;
        this.infoService = infoService;
        this.sseService = sseService;
        this.auditService = auditService;
    }

    @PostMapping("/mcp")
    public ResponseEntity<?> rpc(@RequestBody(required = false) String raw,
                                 HttpServletRequest request, HttpServletResponse response) {
        String requestId = resolveRequestId(request, response);
        String sessionId = request.getHeader(McpProtocol.HEADER_SESSION_ID);
        McpCallContext.set(new McpCallContext.Call(
                request.getHeader(McpProtocol.HEADER_AUTHORIZATION),
                request.getHeader(McpProtocol.HEADER_API_KEY),
                requestId,
                sessionId));
        try {
            JsonNode root;
            try {
                root = raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
            } catch (Exception ex) {
                return ResponseEntity.ok(JsonRpcResponse.error(null, McpProtocol.PARSE_ERROR,
                        "Parse error: " + ex.getMessage(), null));
            }
            if (root == null) {
                return ResponseEntity.ok(JsonRpcResponse.error(null, McpProtocol.INVALID_REQUEST,
                        "Invalid Request: empty body", null));
            }
            if (root.isArray()) {
                return handleBatch(root, request, response);
            }
            return handleSingle(root, request, response);
        } finally {
            McpCallContext.clear();
        }
    }

    @GetMapping(value = "/mcp", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestHeader(value = McpProtocol.HEADER_SESSION_ID, required = false) String sessionId,
                             HttpServletRequest request, HttpServletResponse response) {
        String requestId = resolveRequestId(request, response);
        String effectiveSession = sessionId == null || sessionId.isBlank()
                ? UUID.randomUUID().toString()
                : sessionId;
        response.setHeader(McpProtocol.HEADER_SESSION_ID, effectiveSession);
        response.setHeader(McpProtocol.HEADER_ACCEL_BUFFERING, "no");
        response.setHeader(McpProtocol.HEADER_REQUEST_ID, requestId);
        return sseService.subscribe(effectiveSession);
    }

    @GetMapping("/mcp/info")
    public Map<String, Object> info() {
        return infoService.snapshot();
    }

    private ResponseEntity<?> handleSingle(JsonNode node, HttpServletRequest request,
                                           HttpServletResponse response) {
        if (node == null || !node.isObject()) {
            return ResponseEntity.ok(JsonRpcResponse.error(null, McpProtocol.INVALID_REQUEST,
                    "Invalid Request: body must be a JSON object", null));
        }
        Object id = extractId(node);
        JsonNode version = node.get(McpProtocol.JSONRPC);
        if (version == null || !JsonRpcResponse.VERSION.equals(version.asText())) {
            return ResponseEntity.ok(JsonRpcResponse.error(id, McpProtocol.INVALID_REQUEST,
                    "Invalid Request: jsonrpc must be \"2.0\"", null));
        }
        JsonNode methodNode = node.get(McpProtocol.METHOD);
        String method = methodNode == null ? null : methodNode.asText();
        if (method == null || method.isBlank()) {
            return ResponseEntity.ok(JsonRpcResponse.error(id, McpProtocol.INVALID_REQUEST,
                    "Invalid Request: method is required", null));
        }
        applySessionHeader(method, response);

        // JSON-RPC notifications (no id) and MCP notifications/* never get a body.
        boolean notification = !node.has(McpProtocol.ID) || method.startsWith("notifications/");
        if (notification) {
            return ResponseEntity.accepted().build();
        }
        if ("tools/call".equals(method)) {
            return handleToolCall(node, id, request);
        }
        try {
            JsonNode result = dispatcher.dispatch(method, node.get(McpProtocol.PARAMS));
            return ResponseEntity.ok(JsonRpcResponse.ok(id, result));
        } catch (JsonRpcException ex) {
            return ResponseEntity.ok(JsonRpcResponse.error(id, ex.code(), ex.getMessage(), ex.data()));
        } catch (DownstreamException ex) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorCode", ex.errorCode());
            data.put("message", ex.getMessage());
            return ResponseEntity.ok(JsonRpcResponse.error(id, McpProtocol.INTERNAL_ERROR,
                    ex.getMessage(), data));
        } catch (Exception ex) {
            log.error("MCP method {} failed", method, ex);
            return ResponseEntity.ok(JsonRpcResponse.error(id, McpProtocol.INTERNAL_ERROR,
                    "Internal error: " + ex.getMessage(), null));
        }
    }

    /**
     * {@code tools/call} execution plus one {@code mcp_call_audits} row (docs/15 §4.2):
     * every tool invocation is audited with tool/success/errorCode/duration/ip/UA/requestId.
     */
    private ResponseEntity<?> handleToolCall(JsonNode node, Object id, HttpServletRequest request) {
        JsonNode params = node.get(McpProtocol.PARAMS);
        String tool = params == null ? null : params.path("name").asText(null);
        long start = System.nanoTime();
        boolean success = false;
        Integer errorCode = null;
        JsonRpcResponse rpcResponse;
        try {
            JsonNode result = dispatcher.dispatch("tools/call", params);
            rpcResponse = JsonRpcResponse.ok(id, result);
            boolean isError = result != null && result.path("isError").asBoolean(false);
            success = !isError;
            if (isError) {
                JsonNode structured = result.get("structuredContent");
                errorCode = structured != null && structured.has("errorCode")
                        ? structured.get("errorCode").asInt()
                        : McpProtocol.INTERNAL_ERROR;
            }
        } catch (JsonRpcException ex) {
            rpcResponse = JsonRpcResponse.error(id, ex.code(), ex.getMessage(), ex.data());
            errorCode = ex.code();
        } catch (DownstreamException ex) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("errorCode", ex.errorCode());
            data.put("message", ex.getMessage());
            rpcResponse = JsonRpcResponse.error(id, McpProtocol.INTERNAL_ERROR, ex.getMessage(), data);
            errorCode = McpProtocol.INTERNAL_ERROR;
        } catch (Exception ex) {
            log.error("MCP tool call {} failed", tool, ex);
            rpcResponse = JsonRpcResponse.error(id, McpProtocol.INTERNAL_ERROR,
                    "Internal error: " + ex.getMessage(), null);
            errorCode = McpProtocol.INTERNAL_ERROR;
        }
        long durationMs = (System.nanoTime() - start) / 1_000_000L;
        auditService.record(request, tool, success, errorCode, durationMs);
        return ResponseEntity.ok(rpcResponse);
    }

    private ResponseEntity<?> handleBatch(JsonNode array, HttpServletRequest request,
                                          HttpServletResponse response) {
        ArrayNode responses = objectMapper.createArrayNode();
        for (JsonNode node : array) {
            ResponseEntity<?> single = handleSingle(node, request, response);
            Object body = single.getBody();
            if (body == null) {
                continue;
            }
            responses.add(objectMapper.valueToTree(body));
        }
        if (responses.isEmpty()) {
            return ResponseEntity.accepted().build();
        }
        return ResponseEntity.ok(responses);
    }

    private void applySessionHeader(String method, HttpServletResponse response) {
        McpCallContext.Call call = McpCallContext.get();
        String session = call == null ? null : call.sessionId();
        if ("initialize".equals(method) && (session == null || session.isBlank())) {
            session = UUID.randomUUID().toString();
        }
        if (session != null && !session.isBlank()) {
            response.setHeader(McpProtocol.HEADER_SESSION_ID, session);
        }
    }

    private Object extractId(JsonNode node) {
        JsonNode id = node.get(McpProtocol.ID);
        if (id == null || id.isNull()) {
            return null;
        }
        if (id.isTextual()) {
            return id.asText();
        }
        if (id.isIntegralNumber()) {
            return id.asLong();
        }
        if (id.isNumber()) {
            return id.asDouble();
        }
        return id.asText();
    }

    private String resolveRequestId(HttpServletRequest request, HttpServletResponse response) {
        String requestId = response.getHeader(McpProtocol.HEADER_REQUEST_ID);
        if (requestId == null || requestId.isBlank()) {
            requestId = request.getHeader(McpProtocol.HEADER_REQUEST_ID);
        }
        if (requestId == null || requestId.isBlank()) {
            requestId = UUID.randomUUID().toString();
        }
        response.setHeader(McpProtocol.HEADER_REQUEST_ID, requestId);
        return requestId;
    }
}
