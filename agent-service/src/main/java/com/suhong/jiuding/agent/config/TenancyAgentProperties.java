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
package com.suhong.jiuding.agent.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * agent-service specific configuration.
 *
 * <p>Everything has a safe offline default: the service must be startable and
 * testable without network, Docker or an LLM API key.
 */
@ConfigurationProperties(prefix = "tenancy")
public class TenancyAgentProperties {

    /** Skill package root. Relative paths are resolved from the process CWD. */
    private String skillsDir = "../skills";
    /** Shared secret used for service-to-service calls (X-Internal-Token). */
    private String internalToken = "dev-internal-token";
    /** Downstream service URLs. */
    private Services services = new Services();
    /** Agent runtime tuning. */
    private Agent agent = new Agent();

    public String getSkillsDir() {
        return skillsDir;
    }

    public void setSkillsDir(String skillsDir) {
        this.skillsDir = skillsDir;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public Services getServices() {
        return services;
    }

    public void setServices(Services services) {
        this.services = services;
    }

    public Agent getAgent() {
        return agent;
    }

    public void setAgent(Agent agent) {
        this.agent = agent;
    }

    /** Downstream HTTP endpoints (all optional, failures are degraded). */
    public static class Services {
        private String tenantUrl = "http://localhost:8081";
        private String approvalUrl = "http://localhost:8082";

        public String getTenantUrl() {
            return tenantUrl;
        }

        public void setTenantUrl(String tenantUrl) {
            this.tenantUrl = tenantUrl;
        }

        public String getApprovalUrl() {
            return approvalUrl;
        }

        public void setApprovalUrl(String approvalUrl) {
            this.approvalUrl = approvalUrl;
        }
    }

    /** Agent runtime settings (ReAct iterations, timeouts, model provider). */
    public static class Agent {
        private int maxSteps = 6;
        private int timeoutSeconds = 60;
        private Model model = new Model();

        public int getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(int maxSteps) {
            this.maxSteps = maxSteps;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }

        public Model getModel() {
            return model;
        }

        public void setModel(Model model) {
            this.model = model;
        }
    }

    /** OpenAI-compatible model channel; falls back to scripted when unconfigured. */
    public static class Model {
        private String provider = "scripted";
        private String name = "gpt-4o-mini";
        private String baseUrl = "https://api.openai.com/v1";
        private String apiKey = "";
        private double temperature = 0.0;
        private int maxTokens = 1024;
        private int timeoutSeconds = 60;

        public String getProvider() {
            return provider;
        }

        public void setProvider(String provider) {
            this.provider = provider;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getBaseUrl() {
            return baseUrl;
        }

        public void setBaseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }

        public double getTemperature() {
            return temperature;
        }

        public void setTemperature(double temperature) {
            this.temperature = temperature;
        }

        public int getMaxTokens() {
            return maxTokens;
        }

        public void setMaxTokens(int maxTokens) {
            this.maxTokens = maxTokens;
        }

        public int getTimeoutSeconds() {
            return timeoutSeconds;
        }

        public void setTimeoutSeconds(int timeoutSeconds) {
            this.timeoutSeconds = timeoutSeconds;
        }
    }

    /** Convenience: resolve the skill root as an absolute path. */
    public java.nio.file.Path resolveSkillsDir() {
        java.nio.file.Path configured = java.nio.file.Path.of(skillsDir).toAbsolutePath().normalize();
        if (java.nio.file.Files.isDirectory(configured)) {
            return configured;
        }
        // The documented default is "../skills"; when a launcher starts the JVM from the
        // repository root instead of the module directory, try the sibling layout too.
        if ("../skills".equals(skillsDir)) {
            java.nio.file.Path fromRoot = java.nio.file.Path.of("skills").toAbsolutePath().normalize();
            if (java.nio.file.Files.isDirectory(fromRoot)) {
                return fromRoot;
            }
        }
        return configured;
    }
}
