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
package com.suhong.jiuding.mcp.credentials;

import com.suhong.jiuding.mcp.protocol.McpCallContext;
import com.suhong.jiuding.mcp.protocol.McpProtocol;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Writes one {@code mcp_call_audits} row per {@code tools/call} (docs/15 §4.2):
 * tool/success/errorCode/duration/ip/UA/requestId/sessionId plus the API-key owner.
 * Static env keys are recorded with {@code key_prefix=static}.
 */
@Service
public class McpAuditService {

    private static final Logger log = LoggerFactory.getLogger(McpAuditService.class);
    public static final int MAX_USER_AGENT_LENGTH = 256;

    private final McpCallAuditRepository repository;

    public McpAuditService(McpCallAuditRepository repository) {
        this.repository = repository;
    }

    public void record(HttpServletRequest request, String tool, boolean success,
                       Integer errorCode, long durationMs) {
        try {
            Object keyId = request.getAttribute(McpAuthAttributes.KEY_ID);
            Object keyPrefix = request.getAttribute(McpAuthAttributes.KEY_PREFIX);
            Object owner = request.getAttribute(McpAuthAttributes.OWNER_USERNAME);
            Object tenantId = request.getAttribute(McpAuthAttributes.TENANT_ID);
            McpCallContext.Call call = McpCallContext.get();
            String requestId = call != null ? call.requestId()
                    : request.getHeader(McpProtocol.HEADER_REQUEST_ID);
            String sessionId = call != null ? call.sessionId()
                    : request.getHeader(McpProtocol.HEADER_SESSION_ID);
            String userAgent = request.getHeader("User-Agent");
            if (userAgent != null && userAgent.length() > MAX_USER_AGENT_LENGTH) {
                userAgent = userAgent.substring(0, MAX_USER_AGENT_LENGTH);
            }
            McpCallAuditRow row = new McpCallAuditRow(
                    UUID.randomUUID().toString(),
                    keyId == null ? null : String.valueOf(keyId),
                    keyPrefix == null ? McpAuthAttributes.STATIC_PREFIX : String.valueOf(keyPrefix),
                    owner == null ? "jwt" : String.valueOf(owner),
                    tenantId == null ? null : String.valueOf(tenantId),
                    tool == null || tool.isBlank() ? "unknown" : tool,
                    requestId,
                    sessionId,
                    success,
                    errorCode,
                    durationMs,
                    resolveClientIp(request),
                    userAgent,
                    LocalDateTime.now());
            repository.insert(row);
        } catch (RuntimeException ex) {
            // Auditing must never turn a successful tool call into a failure.
            log.warn("failed to persist MCP call audit for tool {}: {}", tool, ex.getMessage());
        }
    }

    public static String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = request.getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            String first = forwardedFor.split(",")[0].trim();
            if (!first.isEmpty()) {
                return first;
            }
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }
}
