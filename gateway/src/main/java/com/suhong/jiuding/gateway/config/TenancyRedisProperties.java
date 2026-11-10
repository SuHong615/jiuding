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
 * Optional Redis configuration for distributed login / tenant quota rate limiting
 * (docs/23 §5).
 *
 * <p>{@code REDIS_URL} (e.g. {@code redis://user:pass@host:6379/0}) is enough to enable the
 * Redis store; {@code tenancy.redis.enabled=true} is the explicit switch. When neither is set
 * — or Redis is unreachable — the gateway keeps using the in-memory limiters.
 */
@ConfigurationProperties(prefix = "tenancy.redis")
public class TenancyRedisProperties {

    private boolean enabled = false;
    private String url = "";
    /** Prefix for all limiter keys, e.g. {@code tenancy:rl:}. */
    private String keyPrefix = "tenancy:rl:";

    /** Redis is used when explicitly enabled or when REDIS_URL is configured. */
    public boolean isActive() {
        return enabled || (url != null && !url.isBlank());
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getKeyPrefix() {
        return keyPrefix;
    }

    public void setKeyPrefix(String keyPrefix) {
        this.keyPrefix = keyPrefix;
    }
}
