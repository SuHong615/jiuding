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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory sliding-window rate limiter for {@code POST /api/auth/login} (docs/14 §4).
 *
 * <p>Key = {@code clientIp:username}; a request is counted regardless of whether the
 * credentials are valid (successful logins are not penalised — they are simply counted
 * like every other attempt). When the number of requests inside the window reaches the
 * configured limit the next attempt is rejected with
 * {@link ErrorCode#QUOTA_EXCEEDED} (HTTP 429 via the shared exception handler).
 *
 * <p>Thread safety: {@link ConcurrentHashMap} of per-key windows; each window's deque is
 * guarded by the window instance monitor. Stale windows are purged periodically so a
 * long-running gateway does not leak memory for one-off client IPs.
 */
@Component
public class LoginRateLimiter {

    private static final Logger log = LoggerFactory.getLogger(LoginRateLimiter.class);

    /** Exact message required by docs/14 §4. */
    public static final String TOO_MANY_ATTEMPTS = "too many login attempts, retry later";

    private static final int PURGE_EVERY_CALLS = 1024;

    private final LoginRateLimitProperties properties;
    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    public LoginRateLimiter(LoginRateLimitProperties properties) {
        this.properties = properties;
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
        String key = ip + ":" + user;

        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        synchronized (window) {
            window.lastSeen = now;
            purgeOlderThan(window, now - windowMs);
            if (window.timestamps.size() >= maxRequests) {
                throw new BizException(ErrorCode.QUOTA_EXCEEDED, TOO_MANY_ATTEMPTS);
            }
            window.timestamps.addLast(now);
        }

        if ((calls.incrementAndGet() % PURGE_EVERY_CALLS) == 0) {
            purgeStaleWindows(now - windowMs * 2);
        }
    }

    /** Clears all counters; used by tests. */
    public void reset() {
        windows.clear();
        calls.set(0);
    }

    /** Number of tracked keys; used by tests. */
    public int trackedKeys() {
        return windows.size();
    }

    private void purgeOlderThan(Window window, long cutoff) {
        Deque<Long> timestamps = window.timestamps;
        while (!timestamps.isEmpty() && timestamps.peekFirst() <= cutoff) {
            timestamps.pollFirst();
        }
    }

    private void purgeStaleWindows(long cutoff) {
        int removed = 0;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, Window> entry = iterator.next();
            Window window = entry.getValue();
            synchronized (window) {
                purgeOlderThan(window, cutoff);
                if (window.timestamps.isEmpty() && window.lastSeen <= cutoff) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("purged {} stale login rate-limit windows", removed);
        }
    }

    private static final class Window {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long lastSeen;
    }
}
