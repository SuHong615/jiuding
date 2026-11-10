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

/**
 * Sliding-window rate-limit store abstraction (docs/23 §5).
 *
 * <p>Implementations enforce exactly the same semantics as the historical in-memory
 * limiters: a request is allowed while the number of recorded requests inside
 * {@code windowMillis} is below {@code limit}; otherwise it is rejected. Keys are composed by
 * the callers (e.g. {@code login:<ip>:<username>}, {@code quota:<tenantId>}).
 *
 * <p>{@link MemoryRateLimitStore} is the single-node implementation;
 * {@link RedisRateLimitStore} adds a Redis ZSET implementation for multi-replica deployments
 * and falls back to the memory store when Redis is unavailable.
 */
public interface RateLimitStore {

    /**
     * Records one attempt and reports whether it is within the limit.
     *
     * @param key         logical key (prefixes are added by the implementation)
     * @param limit       maximum attempts allowed within the window
     * @param windowMillis sliding window length in milliseconds
     * @return {@code true} when the attempt is allowed
     */
    boolean tryAcquire(String key, long limit, long windowMillis);

    /** Clears the in-memory counters (Redis keys are left untouched). Visible for tests. */
    void reset();

    /** Number of tracked in-memory keys. Visible for tests. */
    int trackedKeys();
}
