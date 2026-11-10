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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis-backed sliding-window store (docs/23 §5) for multi-replica gateways.
 *
 * <p>Each logical key maps to a Redis sorted set whose members are unique per request and
 * whose scores are the request timestamps (milliseconds). A request removes entries older
 * than the window, checks the cardinality and — when below the limit — adds the new entry and
 * refreshes the TTL. Keys are prefixed with {@code tenancy.redis.key-prefix}
 * (default {@code tenancy:rl:}).
 *
 * <p>Any Redis failure (connection loss, timeout, command error) degrades to the supplied
 * {@link MemoryRateLimitStore} for that call so login/quota enforcement keeps working with the
 * historical single-node semantics instead of failing open or returning 5xx.
 */
public class RedisRateLimitStore implements RateLimitStore {

    private static final Logger log = LoggerFactory.getLogger(RedisRateLimitStore.class);
    private static final long WARN_EVERY_FAILURES = 100L;

    private final StringRedisTemplate template;
    private final MemoryRateLimitStore fallback;
    private final String keyPrefix;
    private final AtomicLong memberSequence = new AtomicLong();
    private final AtomicLong failures = new AtomicLong();

    public RedisRateLimitStore(StringRedisTemplate template, MemoryRateLimitStore fallback,
                               String keyPrefix) {
        this.template = template;
        this.fallback = fallback;
        this.keyPrefix = keyPrefix == null || keyPrefix.isBlank() ? "tenancy:rl:" : keyPrefix;
    }

    @Override
    public boolean tryAcquire(String key, long limit, long windowMillis) {
        String redisKey = keyPrefix + key;
        long windowMs = Math.max(1L, windowMillis);
        long now = System.currentTimeMillis();
        try {
            ZSetOperations<String, String> zset = template.opsForZSet();
            zset.removeRangeByScore(redisKey, 0, now - windowMs);
            Long count = zset.zCard(redisKey);
            if (count != null && count >= limit) {
                return false;
            }
            String member = now + "-" + memberSequence.incrementAndGet() + "-"
                    + ThreadLocalRandom.current().nextInt(1_000_000);
            zset.add(redisKey, member, now);
            template.expire(redisKey, Duration.ofMillis(windowMs));
            return true;
        } catch (RuntimeException ex) {
            long failureCount = failures.incrementAndGet();
            if (failureCount == 1 || failureCount % WARN_EVERY_FAILURES == 0) {
                log.warn("Redis rate-limit store unavailable ({}); falling back to in-memory counters",
                        ex.getMessage());
            }
            return fallback.tryAcquire(key, limit, windowMs);
        }
    }

    @Override
    public void reset() {
        fallback.reset();
    }

    @Override
    public int trackedKeys() {
        return fallback.trackedKeys();
    }

    /** Visible for tests. */
    public long failureCount() {
        return failures.get();
    }
}
