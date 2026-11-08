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
package com.suhong.jiuding.common.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/** Shared security configuration ({@code tenancy.security.*}). */
@ConfigurationProperties(prefix = "tenancy.security")
public class SecurityProperties {

    /** HMAC secret used to sign/verify JWTs. Override per environment. */
    private String jwtSecret = "tenancy-center-dev-secret-change-me-please";
    /** Access token TTL in minutes. */
    private long tokenTtlMinutes = 120;
    /** Static API keys accepted for machine-to-machine calls (X-API-Key). */
    private List<String> apiKeys = new ArrayList<>();
    /**
     * Token that authenticates internal service-to-service calls under {@code /internal/**}.
     * Internal endpoints are never exposed through the gateway.
     */
    private String internalToken = "dev-internal-token";
    /** Paths that bypass authentication (Ant patterns). */
    private List<String> whitelist = new ArrayList<>(List.of(
            "/actuator/health", "/actuator/info", "/api/auth/login", "/error",
            "/v3/api-docs/**", "/swagger-ui/**", "/favicon.ico"));
    /** When true, a missing X-Tenant-Id header is tolerated (internal calls). */
    private boolean requireTenantHeader = false;

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getTokenTtlMinutes() {
        return tokenTtlMinutes;
    }

    public void setTokenTtlMinutes(long tokenTtlMinutes) {
        this.tokenTtlMinutes = tokenTtlMinutes;
    }

    public List<String> getApiKeys() {
        return apiKeys;
    }

    public void setApiKeys(List<String> apiKeys) {
        this.apiKeys = apiKeys;
    }

    public String getInternalToken() {
        return internalToken;
    }

    public void setInternalToken(String internalToken) {
        this.internalToken = internalToken;
    }

    public List<String> getWhitelist() {
        return whitelist;
    }

    public void setWhitelist(List<String> whitelist) {
        this.whitelist = whitelist;
    }

    public boolean isRequireTenantHeader() {
        return requireTenantHeader;
    }

    public void setRequireTenantHeader(boolean requireTenantHeader) {
        this.requireTenantHeader = requireTenantHeader;
    }
}
