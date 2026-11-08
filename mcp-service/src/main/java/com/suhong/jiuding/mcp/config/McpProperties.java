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
package com.suhong.jiuding.mcp.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** MCP server identity, downstream endpoints and transport tuning ({@code tenancy.mcp.*}). */
@ConfigurationProperties(prefix = "tenancy.mcp")
public class McpProperties {

    private String serverName = "tenancy-mcp";
    private String serverVersion = "1.0.0";
    private String protocolVersion = "2025-06-18";

    private String tenantServiceUrl = "http://localhost:8081";
    private String approvalServiceUrl = "http://localhost:8082";
    private String agentServiceUrl = "http://localhost:8083";

    /** X-API-Key used for service-to-service calls when the caller used a JWT. */
    private String downstreamApiKey = "dev-mcp-key";
    /** Plain tool call timeout (docs/05 §实现约束). */
    private int toolTimeoutMs = 10_000;
    /** {@code skill.run} passthrough timeout. */
    private int skillTimeoutMs = 30_000;
    /** SSE heartbeat comment interval ({@code : ping}). */
    private long sseHeartbeatMs = 15_000L;
    /** SSE connection timeout; the connection is kept alive by heartbeats. */
    private long sseTimeoutMs = 3_600_000L;
    /** Public MCP endpoint advertised by {@code GET /api/mcp/config} (through the gateway). */
    private String publicEndpoint = "/mcp";
    /** Documentation pointer surfaced by {@code GET /api/mcp/config}. */
    private String docsUrl = "docs/05-mcp-spec.md";
    /** Outbound timeout for {@code POST /api/mcp/external-servers/{id}/check}. */
    private int externalCheckTimeoutMs = 5_000;

    public String getServerName() {
        return serverName;
    }

    public void setServerName(String serverName) {
        this.serverName = serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    public void setServerVersion(String serverVersion) {
        this.serverVersion = serverVersion;
    }

    public String getProtocolVersion() {
        return protocolVersion;
    }

    public void setProtocolVersion(String protocolVersion) {
        this.protocolVersion = protocolVersion;
    }

    public String getTenantServiceUrl() {
        return tenantServiceUrl;
    }

    public void setTenantServiceUrl(String tenantServiceUrl) {
        this.tenantServiceUrl = tenantServiceUrl;
    }

    public String getApprovalServiceUrl() {
        return approvalServiceUrl;
    }

    public void setApprovalServiceUrl(String approvalServiceUrl) {
        this.approvalServiceUrl = approvalServiceUrl;
    }

    public String getAgentServiceUrl() {
        return agentServiceUrl;
    }

    public void setAgentServiceUrl(String agentServiceUrl) {
        this.agentServiceUrl = agentServiceUrl;
    }

    public String getDownstreamApiKey() {
        return downstreamApiKey;
    }

    public void setDownstreamApiKey(String downstreamApiKey) {
        this.downstreamApiKey = downstreamApiKey;
    }

    public int getToolTimeoutMs() {
        return toolTimeoutMs;
    }

    public void setToolTimeoutMs(int toolTimeoutMs) {
        this.toolTimeoutMs = toolTimeoutMs;
    }

    public int getSkillTimeoutMs() {
        return skillTimeoutMs;
    }

    public void setSkillTimeoutMs(int skillTimeoutMs) {
        this.skillTimeoutMs = skillTimeoutMs;
    }

    public long getSseHeartbeatMs() {
        return sseHeartbeatMs;
    }

    public void setSseHeartbeatMs(long sseHeartbeatMs) {
        this.sseHeartbeatMs = sseHeartbeatMs;
    }

    public long getSseTimeoutMs() {
        return sseTimeoutMs;
    }

    public void setSseTimeoutMs(long sseTimeoutMs) {
        this.sseTimeoutMs = sseTimeoutMs;
    }

    public String getPublicEndpoint() {
        return publicEndpoint;
    }

    public void setPublicEndpoint(String publicEndpoint) {
        this.publicEndpoint = publicEndpoint;
    }

    public String getDocsUrl() {
        return docsUrl;
    }

    public void setDocsUrl(String docsUrl) {
        this.docsUrl = docsUrl;
    }

    public int getExternalCheckTimeoutMs() {
        return externalCheckTimeoutMs;
    }

    public void setExternalCheckTimeoutMs(int externalCheckTimeoutMs) {
        this.externalCheckTimeoutMs = externalCheckTimeoutMs;
    }
}
