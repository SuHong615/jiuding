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
package com.suhong.jiuding.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** External service endpoints and proxy timeouts ({@code tenancy.gateway.*}). */
@ConfigurationProperties(prefix = "tenancy.gateway")
public class GatewayProperties {

    private String tenantServiceUrl = "http://localhost:8081";
    private String approvalServiceUrl = "http://localhost:8082";
    private String agentServiceUrl = "http://localhost:8083";
    private String mcpServiceUrl = "http://localhost:8084";

    /** Normal REST proxy timeout (10s per contract). */
    private int requestTimeoutMs = 10_000;
    /** MCP proxy timeout (30s per contract). */
    private int mcpTimeoutMs = 30_000;
    /** Per-service probe timeout used by {@code /api/platform/**}. */
    private int healthTimeoutMs = 2_000;
    /** TCP connect timeout for the shared {@link java.net.http.HttpClient}. */
    private int connectTimeoutMs = 5_000;

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

    public String getMcpServiceUrl() {
        return mcpServiceUrl;
    }

    public void setMcpServiceUrl(String mcpServiceUrl) {
        this.mcpServiceUrl = mcpServiceUrl;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    public int getMcpTimeoutMs() {
        return mcpTimeoutMs;
    }

    public void setMcpTimeoutMs(int mcpTimeoutMs) {
        this.mcpTimeoutMs = mcpTimeoutMs;
    }

    public int getHealthTimeoutMs() {
        return healthTimeoutMs;
    }

    public void setHealthTimeoutMs(int healthTimeoutMs) {
        this.healthTimeoutMs = healthTimeoutMs;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }
}
