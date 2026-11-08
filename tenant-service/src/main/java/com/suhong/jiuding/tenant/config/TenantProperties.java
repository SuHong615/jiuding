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
package com.suhong.jiuding.tenant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Tenant-service specific configuration ({@code tenancy.*}).
 *
 * <p>Security related settings ({@code tenancy.security.*}) are owned by
 * tenancy-common's {@code SecurityProperties}; this class only holds service
 * behaviour knobs plus the approval-service client configuration.
 */
@ConfigurationProperties(prefix = "tenancy")
public class TenantProperties {

    /** Hard cap applied to page size parameters. */
    private int maxPageSize = 100;

    private final ApprovalService approvalService = new ApprovalService();
    private final AgentService agentService = new AgentService();
    private final RiskReview riskReview = new RiskReview();
    private final Rating rating = new Rating();
    private final Collect collect = new Collect();

    public int getMaxPageSize() {
        return maxPageSize;
    }

    public void setMaxPageSize(int maxPageSize) {
        this.maxPageSize = maxPageSize;
    }

    public ApprovalService getApprovalService() {
        return approvalService;
    }

    public AgentService getAgentService() {
        return agentService;
    }

    public RiskReview getRiskReview() {
        return riskReview;
    }

    public Rating getRating() {
        return rating;
    }

    /** Outbound agent-service client settings (skill execution). */
    public static class AgentService {

        private String url = "http://localhost:8083";
        private int connectTimeoutMs = 1500;
        private int readTimeoutMs = 3000;
        /** Machine credential used for headless skill execution. */
        private String apiKey = "dev-mcp-key";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
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

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** Risk review (skill execution) behaviour. */
    public static class RiskReview {

        /** Disable in isolated unit tests / offline environments. */
        private boolean enabled = true;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }
    }

    /**
     * Tenant rating assessment behaviour. The agent-service {@code tenant-rating} skill is
     * optional: when it is disabled/unreachable the base weighted score is used as-is.
     */
    public static class Rating {

        private boolean enabled = true;
        /** Total budget for the optional skill call (docs/11 section 4: 2s timeout). */
        private int timeoutMs = 2000;
        /** Daily scheduled policy refresh (docs/13 section 4). */
        private boolean autoRefreshEnabled = true;
        private String refreshCron = "0 0 3 * * *";
        private int refreshLimit = 200;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public boolean isAutoRefreshEnabled() {
            return autoRefreshEnabled;
        }

        public void setAutoRefreshEnabled(boolean autoRefreshEnabled) {
            this.autoRefreshEnabled = autoRefreshEnabled;
        }

        public String getRefreshCron() {
            return refreshCron;
        }

        public void setRefreshCron(String refreshCron) {
            this.refreshCron = refreshCron;
        }

        public int getRefreshLimit() {
            return refreshLimit;
        }

        public void setRefreshLimit(int refreshLimit) {
            this.refreshLimit = refreshLimit;
        }
    }

    public Collect getCollect() {
        return collect;
    }

    /** Outbound approval-service client settings. */
    public static class ApprovalService {

        private String url = "http://localhost:8082";
        private int connectTimeoutMs = 2000;
        private int readTimeoutMs = 5000;
        /** Fallback machine credential used when the caller has no JWT to forward. */
        private String apiKey = "dev-mcp-key";

        public String getUrl() {
            return url;
        }

        public void setUrl(String url) {
            this.url = url;
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

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }

    /** Bulk collection engine settings. */
    public static class Collect {

        private long csvMaxBytes = 5L * 1024 * 1024;
        private int maxErrors = 20;
        /** When true the job runs on the caller thread (deterministic tests). */
        private boolean sync = false;
        private int threadPoolSize = 4;
        private int queueCapacity = 100;

        public long getCsvMaxBytes() {
            return csvMaxBytes;
        }

        public void setCsvMaxBytes(long csvMaxBytes) {
            this.csvMaxBytes = csvMaxBytes;
        }

        public int getMaxErrors() {
            return maxErrors;
        }

        public void setMaxErrors(int maxErrors) {
            this.maxErrors = maxErrors;
        }

        public boolean isSync() {
            return sync;
        }

        public void setSync(boolean sync) {
            this.sync = sync;
        }

        public int getThreadPoolSize() {
            return threadPoolSize;
        }

        public void setThreadPoolSize(int threadPoolSize) {
            this.threadPoolSize = threadPoolSize;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = queueCapacity;
        }
    }
}
