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
package com.suhong.jiuding.gateway.ratelimit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Single-node sliding-window limiter store (the historical gateway implementation, docs/14 §4,
 * docs/17 §5). Used directly when Redis is disabled and as the degradation target when Redis
 * is enabled but unavailable.
 */
public class MemoryRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(MemoryRateLimitStore.class);
    private static final int PURGE_EVERY_CALLS = 1024;

    private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();
    private final AtomicLong calls = new AtomicLong();

    @Override
    public boolean tryAcquire(String key, long limit, long windowMillis) {
        long windowMs = Math.max(1L, windowMillis);
        long now = System.currentTimeMillis();
        Window window = windows.computeIfAbsent(key, ignored -> new Window());
        boolean allowed;
        synchronized (window) {
            window.lastSeen = now;
            purgeOlderThan(window, now - windowMs);
            allowed = window.timestamps.size() < limit;
            if (allowed) {
                window.timestamps.addLast(now);
            }
        }
        if ((calls.incrementAndGet() % PURGE_EVERY_CALLS) == 0) {
            purgeStaleWindows(now - windowMs * 2);
        }
        return allowed;
    }

    @Override
    public void reset() {
        windows.clear();
        calls.set(0);
    }

    @Override
    public int trackedKeys() {
        return windows.size();
    }

    private static void purgeOlderThan(Window window, long cutoff) {
        while (!window.timestamps.isEmpty() && window.timestamps.peekFirst() <= cutoff) {
            window.timestamps.pollFirst();
        }
    }

    private void purgeStaleWindows(long cutoff) {
        int removed = 0;
        Iterator<Map.Entry<String, Window>> iterator = windows.entrySet().iterator();
        while (iterator.hasNext()) {
            Window window = iterator.next().getValue();
            synchronized (window) {
                purgeOlderThan(window, cutoff);
                if (window.timestamps.isEmpty() && window.lastSeen <= cutoff) {
                    iterator.remove();
                    removed++;
                }
            }
        }
        if (removed > 0) {
            log.debug("purged {} stale rate-limit windows", removed);
        }
    }

    private static final class Window {
        private final Deque<Long> timestamps = new ArrayDeque<>();
        private long lastSeen;
    }
}
