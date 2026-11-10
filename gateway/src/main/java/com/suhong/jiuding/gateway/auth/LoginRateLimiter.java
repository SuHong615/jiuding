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
package com.suhong.jiuding.gateway.auth;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.gateway.config.LoginRateLimitProperties;
import com.suhong.jiuding.gateway.ratelimit.RateLimitStore;
import org.springframework.stereotype.Component;

/**
 * Sliding-window rate limiter for {@code POST /api/auth/login} (docs/14 §4).
 *
 * <p>Key = {@code login:<clientIp>:<username>}; a request is counted regardless of whether the
 * credentials are valid (successful logins are not penalised — they are simply counted like
 * every other attempt). When the number of requests inside the 60s window reaches the
 * configured limit the next attempt is rejected with {@link ErrorCode#QUOTA_EXCEEDED}
 * (HTTP 429 via the shared exception handler).
 *
 * <p>Counting is delegated to a {@link RateLimitStore}: Redis-backed when
 * {@code tenancy.redis.enabled}/{@code REDIS_URL} is set (docs/23 §5), otherwise the
 * historical in-memory store. Redis failures degrade to the in-memory store automatically.
 */
@Component
public class LoginRateLimiter {

    /** Exact message required by docs/14 §4. */
    public static final String TOO_MANY_ATTEMPTS = "too many login attempts, retry later";

    private final LoginRateLimitProperties properties;
    private final RateLimitStore store;

    public LoginRateLimiter(LoginRateLimitProperties properties, RateLimitStore store) {
        this.properties = properties;
        this.store = store;
    }

    /**
     * Records one login attempt and rejects it when the sliding window is exhausted.
     *
     * @throws BizException {@code QUOTA_EXCEEDED} (HTTP 429, code 42900) when over the limit
     */
    public void check(String clientIp, String username) {
        if (!properties.isEnabled()) {
            return;
        }
        long windowMs = Math.max(1L, properties.getWindowSeconds()) * 1000L;
        int maxRequests = Math.max(1, properties.getMaxRequests());
        String ip = clientIp == null || clientIp.isBlank() ? "unknown" : clientIp;
        String user = username == null ? "" : username;
        if (!store.tryAcquire("login:" + ip + ":" + user, maxRequests, windowMs)) {
            throw new BizException(ErrorCode.QUOTA_EXCEEDED, TOO_MANY_ATTEMPTS);
        }
    }

    /** Clears all counters; used by tests. */
    public void reset() {
        store.reset();
    }

    /** Number of tracked keys; used by tests. */
    public int trackedKeys() {
        return store.trackedKeys();
    }
}
