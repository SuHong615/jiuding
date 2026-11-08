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
package com.suhong.jiuding.tenant.onboarding;

import org.springframework.stereotype.Component;

import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * In-memory sliding-window rate limiter for the public onboarding endpoint.
 * 5 requests per rolling minute per client IP by default ({@code tenancy.onboarding.*}).
 */
@Component
public class OnboardingRateLimiter {

    private static final long WINDOW_MILLIS = 60_000L;
    private static final int MAX_TRACKED_CLIENTS = 10_000;

    private final OnboardingProperties properties;
    private final Map<String, Deque<Long>> windows = new ConcurrentHashMap<>();

    public OnboardingRateLimiter(OnboardingProperties properties) {
        this.properties = properties;
    }

    /** @return true when the request is allowed and consumes one slot. */
    public boolean tryAcquire(String clientKey) {
        String key = clientKey == null || clientKey.isBlank() ? "unknown" : clientKey.trim();
        if (windows.size() > MAX_TRACKED_CLIENTS) {
            prune();
        }
        Deque<Long> timestamps = windows.computeIfAbsent(key, ignored -> new ConcurrentLinkedDeque<>());
        long now = System.currentTimeMillis();
        long windowStart = now - WINDOW_MILLIS;
        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() >= properties.getRateLimitPerMinute()) {
                return false;
            }
            timestamps.addLast(now);
            return true;
        }
    }

    private void prune() {
        long windowStart = System.currentTimeMillis() - WINDOW_MILLIS;
        windows.entrySet().removeIf(entry -> {
            Deque<Long> timestamps = entry.getValue();
            synchronized (timestamps) {
                Long last = timestamps.peekLast();
                return last == null || last < windowStart;
            }
        });
    }
}
