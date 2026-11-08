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
package com.suhong.jiuding.approval.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Configuration for the approval workflow ({@code tenancy.approval.*}). */
@ConfigurationProperties(prefix = "tenancy.approval")
public class ApprovalProperties {

    /** Base URL of agent-service (skill execution). */
    private String agentServiceUrl = "http://localhost:8083";
    /** Base URL of tenant-service (internal status callbacks). */
    private String tenantServiceUrl = "http://localhost:8081";
    /** Shared secret sent as X-Internal-Token on tenant-service internal calls. */
    private String internalToken = "dev-internal-token";
    /** API key sent as X-API-Key to agent-service. */
    private String downstreamApiKey = "dev-mcp-key";
    private int connectTimeoutMs = 2000;
    private int readTimeoutMs = 5000;
    /** SLA scan interval in milliseconds. */
    private long scanIntervalMs = 30_000;
    private int l1SlaHours = 24;
    private int l2SlaHours = 8;
    private String defaultRiskLevel = "MEDIUM";
    private int provisioningRetryDelaySeconds = 30;

    public String getAgentServiceUrl() {
        return agentServiceUrl;
    }

    public void setAgentServiceUrl(String agentServiceUrl) {
        this.agentServiceUrl = agentServiceUrl;
    }

    public String getTenantServiceUrl() {
        return tenantServiceUrl;
    }

    public void setTenantServiceUrl(String tenantServiceUrl) {
        this.tenantServiceUrl = tenantServiceUrl;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public String getDownstreamApiKey() {
        return downstreamApiKey;
    }

    public void setDownstreamApiKey(String downstreamApiKey) {
        this.downstreamApiKey = downstreamApiKey;
    }

    public int getConnectTimeoutMs() {
        return connectTimeoutMs;
    }

    public void setConnectTimeoutMs(int connectTimeoutMs) {
        this.connectTimeoutMs = connectTimeoutMs;
    }

    public int getReadTimeoutMs() {
        return readTimeoutMs;
    }

    public void setReadTimeoutMs(int readTimeoutMs) {
        this.readTimeoutMs = readTimeoutMs;
    }

    public long getScanIntervalMs() {
        return scanIntervalMs;
    }

    public void setScanIntervalMs(long scanIntervalMs) {
        this.scanIntervalMs = scanIntervalMs;
    }

    public int getL1SlaHours() {
        return l1SlaHours;
    }

    public void setL1SlaHours(int l1SlaHours) {
        this.l1SlaHours = l1SlaHours;
    }

    public int getL2SlaHours() {
        return l2SlaHours;
    }

    public void setL2SlaHours(int l2SlaHours) {
        this.l2SlaHours = l2SlaHours;
    }

    public String getDefaultRiskLevel() {
        return defaultRiskLevel;
    }

    public void setDefaultRiskLevel(String defaultRiskLevel) {
        this.defaultRiskLevel = defaultRiskLevel;
    }

    public int getProvisioningRetryDelaySeconds() {
        return provisioningRetryDelaySeconds;
    }

    public void setProvisioningRetryDelaySeconds(int provisioningRetryDelaySeconds) {
        this.provisioningRetryDelaySeconds = provisioningRetryDelaySeconds;
    }
}
