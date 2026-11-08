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
 * OIDC / SSO login configuration ({@code tenancy.auth.oidc.*}, docs/16 §2).
 * All fields can be overridden through the documented {@code TENANCY_OIDC_*} env vars.
 */
@ConfigurationProperties(prefix = "tenancy.auth.oidc")
public class OidcProperties {

    private boolean enabled = false;
    private String providerName = "企业 SSO";
    private String issuer = "";
    private String clientId = "";
    private String clientSecret = "";
    private String redirectUri = "http://localhost:30080/api/auth/oidc/callback";
    /** Comma or space separated OIDC scopes. */
    private String scopes = "openid,profile,email";
    private String frontendRedirect = "http://localhost:30088/sso-callback";
    private String roleClaim = "roles";
    private String defaultRole = "AUDITOR";

    /** Optional explicit endpoints; when blank the discovery document is used. */
    private String authorizationEndpoint = "";
    private String tokenEndpoint = "";
    private String userinfoEndpoint = "";
    /** HMAC secret for the stateless OIDC state parameter (defaults to the shared JWT secret). */
    private String stateSecret = "tenancy-center-dev-secret-change-me-please";
    private long stateTtlSeconds = 600L;
    private long discoveryCacheSeconds = 300L;
    private int requestTimeoutMs = 5_000;

    /** Usable only when the flag is on and the mandatory client settings are present. */
    public boolean isUsable() {
        return enabled && hasText(issuer) && hasText(clientId) && hasText(clientSecret);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getProviderName() {
        return providerName;
    }

    public void setProviderName(String providerName) {
        this.providerName = providerName;
    }

    public String getIssuer() {
        return issuer;
    }

    public void setIssuer(String issuer) {
        this.issuer = issuer;
    }

    public String getClientId() {
        return clientId;
    }

    public void setClientId(String clientId) {
        this.clientId = clientId;
    }

    public String getClientSecret() {
        return clientSecret;
    }

    public void setClientSecret(String clientSecret) {
        this.clientSecret = clientSecret;
    }

    public String getRedirectUri() {
        return redirectUri;
    }

    public void setRedirectUri(String redirectUri) {
        this.redirectUri = redirectUri;
    }

    public String getScopes() {
        return scopes;
    }

    public void setScopes(String scopes) {
        this.scopes = scopes;
    }

    public String getFrontendRedirect() {
        return frontendRedirect;
    }

    public void setFrontendRedirect(String frontendRedirect) {
        this.frontendRedirect = frontendRedirect;
    }

    public String getRoleClaim() {
        return roleClaim;
    }

    public void setRoleClaim(String roleClaim) {
        this.roleClaim = roleClaim;
    }

    public String getDefaultRole() {
        return defaultRole;
    }

    public void setDefaultRole(String defaultRole) {
        this.defaultRole = defaultRole;
    }

    public String getAuthorizationEndpoint() {
        return authorizationEndpoint;
    }

    public void setAuthorizationEndpoint(String authorizationEndpoint) {
        this.authorizationEndpoint = authorizationEndpoint;
    }

    public String getTokenEndpoint() {
        return tokenEndpoint;
    }

    public void setTokenEndpoint(String tokenEndpoint) {
        this.tokenEndpoint = tokenEndpoint;
    }

    public String getUserinfoEndpoint() {
        return userinfoEndpoint;
    }

    public void setUserinfoEndpoint(String userinfoEndpoint) {
        this.userinfoEndpoint = userinfoEndpoint;
    }

    public String getStateSecret() {
        return stateSecret;
    }

    public void setStateSecret(String stateSecret) {
        this.stateSecret = stateSecret;
    }

    public long getStateTtlSeconds() {
        return stateTtlSeconds;
    }

    public void setStateTtlSeconds(long stateTtlSeconds) {
        this.stateTtlSeconds = stateTtlSeconds;
    }

    public long getDiscoveryCacheSeconds() {
        return discoveryCacheSeconds;
    }

    public void setDiscoveryCacheSeconds(long discoveryCacheSeconds) {
        this.discoveryCacheSeconds = discoveryCacheSeconds;
    }

    public int getRequestTimeoutMs() {
        return requestTimeoutMs;
    }

    public void setRequestTimeoutMs(int requestTimeoutMs) {
        this.requestTimeoutMs = requestTimeoutMs;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
