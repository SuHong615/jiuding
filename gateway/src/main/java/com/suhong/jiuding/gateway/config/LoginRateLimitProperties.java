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
 * Login brute-force protection ({@code tenancy.auth.login-rate-limit.*}, docs/14 §4).
 *
 * <p>Only {@code POST /api/auth/login} is limited; the key is {@code clientIp:username}
 * and the window is sliding (in-memory). Values are configurable through the documented
 * environment variables {@code TENANCY_LOGIN_RATE_LIMIT} (max attempts) and
 * {@code TENANCY_LOGIN_RATE_WINDOW_SECONDS} (window length).
 */
@ConfigurationProperties(prefix = "tenancy.auth.login-rate-limit")
public class LoginRateLimitProperties {

    private boolean enabled = true;
    private int maxRequests = 10;
    private long windowSeconds = 60L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public int getMaxRequests() {
        return maxRequests;
    }

    public void setMaxRequests(int maxRequests) {
        this.maxRequests = maxRequests;
    }

    public long getWindowSeconds() {
        return windowSeconds;
    }

    public void setWindowSeconds(long windowSeconds) {
        this.windowSeconds = windowSeconds;
    }
}
