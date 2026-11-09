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

/**
 * Dev-only built-in Mock OIDC identity provider (docs/23 §4).
 *
 * <p>When {@code tenancy.auth.mock-oidc.enabled} is true the gateway exposes
 * {@code /mock-oidc/**} (discovery, authorize, token, userinfo) so the SSO flow can be tested
 * end-to-end without an external IdP. Production deployments must keep this disabled.
 */
@ConfigurationProperties(prefix = "tenancy.auth.mock-oidc")
public class MockOidcProperties {

    private boolean enabled = false;
    /** Optional issuer override; when blank the issuer is derived from the current request. */
    private String issuer = "";
    private String username = "mock-user";
    private String displayName = "Mock User";
    private String roles = "TENANT_ADMIN";
    private long codeTtlSeconds = 120L;
    private long tokenTtlSeconds = 300L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRoles() {
        return roles;
    }

    public void setRoles(String roles) {
        this.roles = roles;
    }

    public long getCodeTtlSeconds() {
        return codeTtlSeconds;
    }

    public void setCodeTtlSeconds(long codeTtlSeconds) {
        this.codeTtlSeconds = codeTtlSeconds;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }
}
