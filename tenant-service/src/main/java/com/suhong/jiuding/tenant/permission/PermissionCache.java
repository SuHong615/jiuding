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
package com.suhong.jiuding.tenant.permission;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short lived cache of role -> permission codes used by the permission interceptor
 * (docs/11 section 5, v9; Redis sharing docs/23 section 5).
 *
 * <p>Memory mode is the default and works everywhere (H2 tests). When
 * {@code tenancy.redis.enabled=true} and a {@link StringRedisTemplate} is available, each role
 * is cached as {@code rbac:perm:<role>} holding a JSON array of permission codes with a 60s
 * TTL. Redis failures transparently fall back to the in-memory cache. Every RBAC mutation
 * evicts memory entries and the Redis keys this instance wrote.
 */
@Component
public class PermissionCache {

    private static final Logger log = LoggerFactory.getLogger(PermissionCache.class);

    public static final long DEFAULT_TTL_MILLIS = 60_000L;
    private static final String KEY_PREFIX = "rbac:perm:";
    private static final long REDIS_RETRY_MILLIS = 30_000L;
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final RbacRepository repository;
    private final long ttlMillis;
    private final StringRedisTemplate redisTemplate;
    private final boolean redisEnabled;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();
    private final Set<String> redisKeys = ConcurrentHashMap.newKeySet();
    private volatile long redisRetryAfter;

    @Autowired
    public PermissionCache(RbacRepository repository,
                           ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                           @Value("${tenancy.redis.enabled:false}") boolean redisEnabled) {
        this(repository, DEFAULT_TTL_MILLIS, redisTemplateProvider.getIfAvailable(), redisEnabled);
    }

    PermissionCache(RbacRepository repository, long ttlMillis) {
        this(repository, ttlMillis, null, false);
    }

    PermissionCache(RbacRepository repository, long ttlMillis, StringRedisTemplate redisTemplate,
                    boolean redisEnabled) {
        this.repository = repository;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
        this.redisTemplate = redisTemplate;
        this.redisEnabled = redisEnabled && redisTemplate != null;
    }

    /** Union of permission codes granted to the caller's roles (cache first, Redis when on). */
    public Set<String> permissions(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        List<String> normalizedRoles = roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .sorted()
                .toList();
        if (normalizedRoles.isEmpty()) {
            return Set.of();
        }
        String memoryKey = String.join(",", normalizedRoles);

        if (redisAvailable()) {
            try {
                Set<String> fromRedis = readFromRedis(normalizedRoles);
                if (fromRedis != null) {
                    return fromRedis;
                }
                return populateRedisAndReturn(normalizedRoles);
            } catch (RuntimeException ex) {
                redisRetryAfter = System.currentTimeMillis() + REDIS_RETRY_MILLIS;
                log.warn("permission cache redis unavailable, falling back to memory: {}",
                        ex.toString());
            }
        }
        return memoryPermissions(normalizedRoles, memoryKey);
    }

    /** Drops every cached entry and the Redis keys written by this instance. */
    public void evictAll() {
        cache.clear();
        if (!redisEnabled || redisTemplate == null) {
            redisKeys.clear();
            return;
        }
        Set<String> keys = Set.copyOf(redisKeys);
        redisKeys.clear();
        try {
            if (!keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (RuntimeException ex) {
            redisRetryAfter = System.currentTimeMillis() + REDIS_RETRY_MILLIS;
            log.warn("could not evict permission cache redis keys: {}", ex.toString());
        }
    }

    /** @return number of in-memory cached role combinations (diagnostics / tests). */
    public int size() {
        return cache.size();
    }

    // ------------------------------------------------------------------ memory cache

    private Set<String> memoryPermissions(List<String> roles, String memoryKey) {
        long now = System.currentTimeMillis();
        Entry cached = cache.get(memoryKey);
        if (cached != null && cached.expiresAt() > now) {
            return cached.codes();
        }
        Set<String> codes = Set.copyOf(repository.permissionCodesForRoles(roles));
        cache.put(memoryKey, new Entry(codes, now + ttlMillis));
        return codes;
    }

    // ------------------------------------------------------------------ redis cache

    private boolean redisAvailable() {
        return redisEnabled && System.currentTimeMillis() >= redisRetryAfter;
    }

    /** @return the union when every role key is present, otherwise null (partial miss). */
    private Set<String> readFromRedis(List<String> roles) {
        Set<String> union = new LinkedHashSet<>();
        for (String role : roles) {
            String value = redisTemplate.opsForValue().get(key(role));
            if (value == null) {
                return null;
            }
            union.addAll(parse(value));
        }
        return Set.copyOf(union);
    }

    private Set<String> populateRedisAndReturn(List<String> roles) {
        Set<String> union = new LinkedHashSet<>();
        for (String role : roles) {
            List<String> codes = repository.permissionCodesForRole(role);
            union.addAll(codes);
            String key = key(role);
            redisTemplate.opsForValue().set(key, writeJson(codes), Duration.ofMillis(ttlMillis));
            redisKeys.add(key);
        }
        return Set.copyOf(union);
    }

    private Set<String> parse(String json) {
        try {
            return Set.copyOf(objectMapper.readValue(json, STRING_LIST));
        } catch (Exception ex) {
            // Corrupted/foreign value: treat as a miss so the database repopulates the key.
            return Set.of();
        }
    }

    private String writeJson(List<String> codes) {
        try {
            return objectMapper.writeValueAsString(new ArrayList<>(codes));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot serialize permission codes", ex);
        }
    }

    private static String key(String role) {
        return KEY_PREFIX + role;
    }

    private record Entry(Set<String> codes, long expiresAt) {
    }
}
