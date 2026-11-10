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

import com.suhong.jiuding.gateway.ratelimit.MemoryRateLimitStore;
import com.suhong.jiuding.gateway.ratelimit.RateLimitStore;
import com.suhong.jiuding.gateway.ratelimit.RedisRateLimitStore;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Rate-limit store selection: Redis disabled → memory regression, URL parsing (docs/23 §5). */
class RateLimitConfigTest {

    @Test
    void disabledOrBlankUrlUsesTheInMemoryStore() {
        RateLimitStore store = new RateLimitConfig().rateLimitStore(new TenancyRedisProperties());
        assertThat(store).isInstanceOf(MemoryRateLimitStore.class);
    }

    @Test
    void redisUrlEnablesTheRedisStore() {
        TenancyRedisProperties properties = new TenancyRedisProperties();
        properties.setUrl("redis://127.0.0.1:6379");

        RateLimitStore store = new RateLimitConfig().rateLimitStore(properties);
        assertThat(store).isInstanceOf(RedisRateLimitStore.class);
    }

    @Test
    void redisUrlWithCredentialsAndDatabaseIsParsed() {
        RedisStandaloneConfiguration configuration =
                RateLimitConfig.parseUrl("redis://alice:secret@cache.example:6380/3");
        assertThat(configuration.getHostName()).isEqualTo("cache.example");
        assertThat(configuration.getPort()).isEqualTo(6380);
        assertThat(configuration.getDatabase()).isEqualTo(3);
        assertThat(configuration.getUsername()).isEqualTo("alice");
        assertThat(configuration.getPassword().isPresent()).isTrue();
    }

    @Test
    void blankUrlFallsBackToLocalhostDefaults() {
        RedisStandaloneConfiguration configuration = RateLimitConfig.parseUrl(" ");
        assertThat(configuration.getHostName()).isEqualTo("localhost");
        assertThat(configuration.getPort()).isEqualTo(6379);
    }

    @Test
    void unsupportedSchemeIsRejected() {
        assertThatThrownBy(() -> RateLimitConfig.parseUrl("http://cache:6379"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
