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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.net.URI;

/**
 * Chooses the rate-limit store (docs/23 §5): Redis when {@code tenancy.redis.enabled} or
 * {@code REDIS_URL} is configured, otherwise the historical in-memory implementation.
 *
 * <p>The Redis client is created lazily by Spring Data Redis (no connection is opened at
 * startup), and {@link RedisRateLimitStore} degrades to the in-memory store per call on any
 * Redis failure. Spring Boot's own Redis auto-configuration is excluded in
 * {@code GatewayApplication} so no unused connection factory / health indicator is created.
 */
@Configuration
public class RateLimitConfig {

    private static final Logger log = LoggerFactory.getLogger(RateLimitConfig.class);

    @Bean
    public RateLimitStore rateLimitStore(TenancyRedisProperties properties) {
        MemoryRateLimitStore memory = new MemoryRateLimitStore();
        if (!properties.isActive()) {
            log.info("rate limiting store: in-memory (Redis disabled)");
            return memory;
        }
        try {
            String url = properties.getUrl();
            boolean ssl = url != null && url.startsWith("rediss://");
            RedisStandaloneConfiguration standalone = parseUrl(url);
            LettuceConnectionFactory factory = ssl
                    ? new LettuceConnectionFactory(standalone,
                            LettuceClientConfiguration.builder().useSsl().build())
                    : new LettuceConnectionFactory(standalone);
            factory.afterPropertiesSet();
            StringRedisTemplate template = new StringRedisTemplate(factory);
            template.afterPropertiesSet();
            log.info("rate limiting store: Redis {} (fallback: in-memory)", standalone.getHostName()
                    + ":" + standalone.getPort() + "/" + standalone.getDatabase());
            return new RedisRateLimitStore(template, memory, properties.getKeyPrefix());
        } catch (RuntimeException ex) {
            log.warn("Redis rate-limit initialization failed ({}); using in-memory", ex.getMessage());
            return memory;
        }
    }

    /** Parses {@code redis://[user:password@]host[:port][/db]}; defaults to localhost:6379/0. */
    static RedisStandaloneConfiguration parseUrl(String url) {
        if (url == null || url.isBlank()) {
            return new RedisStandaloneConfiguration("localhost", 6379);
        }
        URI uri = URI.create(url.trim());
        String scheme = uri.getScheme();
        if (scheme == null || (!scheme.equalsIgnoreCase("redis") && !scheme.equalsIgnoreCase("rediss"))) {
            throw new IllegalArgumentException("unsupported Redis URL scheme: " + scheme);
        }
        String host = uri.getHost() == null || uri.getHost().isBlank() ? "localhost" : uri.getHost();
        int port = uri.getPort() > 0 ? uri.getPort() : 6379;
        RedisStandaloneConfiguration configuration = new RedisStandaloneConfiguration(host, port);
        String userInfo = uri.getUserInfo();
        if (userInfo != null && !userInfo.isBlank()) {
            String[] parts = userInfo.split(":", 2);
            if (parts.length == 2) {
                if (!parts[0].isBlank()) {
                    configuration.setUsername(parts[0]);
                }
                configuration.setPassword(parts[1]);
            } else if (!parts[0].isBlank()) {
                configuration.setPassword(parts[0]);
            }
        }
        String path = uri.getPath();
        if (path != null && path.length() > 1) {
            try {
                configuration.setDatabase(Integer.parseInt(path.substring(1)));
            } catch (NumberFormatException ex) {
                log.warn("ignoring invalid Redis database in URL: {}", path);
            }
        }
        return configuration;
    }
}
