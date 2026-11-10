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

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Redis path of the distributed rate limiting (docs/23 §5): key prefixing, sliding-window
 * threshold, TTL refresh and memory degradation on Redis failures.
 */
class RedisRateLimitStoreTest {

    @SuppressWarnings("unchecked")
    private final ZSetOperations<String, String> zset = mock(ZSetOperations.class);
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);

    private RedisRateLimitStore newStore(String prefix) {
        when(template.opsForZSet()).thenReturn(zset);
        return new RedisRateLimitStore(template, new MemoryRateLimitStore(), prefix);
    }

    @Test
    void redisPathUsesPrefixedKeySlidingWindowThresholdAndTtl() {
        String key = "tenancy:rl:login:203.0.113.9:alice";
        when(zset.zCard(key)).thenReturn(1L);
        RedisRateLimitStore store = newStore("tenancy:rl:");

        long before = System.currentTimeMillis();
        assertThat(store.tryAcquire("login:203.0.113.9:alice", 2, 60_000L)).isTrue();

        // Expired entries are removed with the window cutoff.
        verify(zset).removeRangeByScore(eq(key), eq(0.0),
                doubleThat(cutoff -> cutoff >= before - 60_000L - 1_000L && cutoff <= System.currentTimeMillis()));
        verify(zset).zCard(key);
        verify(zset).add(eq(key), anyString(), anyDouble());
        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(template).expire(eq(key), ttl.capture());
        assertThat(ttl.getValue().toMillis()).isEqualTo(60_000L);
        assertThat(store.failureCount()).isZero();
    }

    @Test
    void redisPathRejectsAtThresholdWithoutRecording() {
        String key = "tenancy:rl:quota:t-acme";
        when(zset.zCard(key)).thenReturn(3L);
        RedisRateLimitStore store = newStore("tenancy:rl:");

        assertThat(store.tryAcquire("quota:t-acme", 3, 60_000L)).isFalse();
        verify(zset, never()).add(anyString(), anyString(), anyDouble());
        verify(template, never()).expire(anyString(), any(Duration.class));
    }

    @Test
    void redisFailureDegradesToInMemoryCounters() {
        when(zset.zCard(anyString())).thenThrow(new RedisConnectionFailureException("redis down"));
        MemoryRateLimitStore memory = new MemoryRateLimitStore();
        when(template.opsForZSet()).thenReturn(zset);
        RedisRateLimitStore store = new RedisRateLimitStore(template, memory, "tenancy:rl:");

        assertThat(store.tryAcquire("login:198.51.100.1:bob", 2, 60_000L)).isTrue();
        assertThat(store.tryAcquire("login:198.51.100.1:bob", 2, 60_000L)).isTrue();
        assertThat(store.tryAcquire("login:198.51.100.1:bob", 2, 60_000L)).isFalse();
        assertThat(store.failureCount()).isEqualTo(3);
        assertThat(store.trackedKeys()).isEqualTo(1);
        assertThat(memory.trackedKeys()).isEqualTo(1);
    }

    @Test
    void customKeyPrefixIsApplied() {
        String key = "custom:quota:t-1";
        when(zset.zCard(key)).thenReturn(0L);
        RedisRateLimitStore store = newStore("custom:");

        assertThat(store.tryAcquire("quota:t-1", 5, 1_000L)).isTrue();
        verify(zset).zCard(key);
        verify(zset).add(eq(key), anyString(), anyDouble());
    }
}
