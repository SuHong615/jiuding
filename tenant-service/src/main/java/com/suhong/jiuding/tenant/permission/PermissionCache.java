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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Short lived cache of role -> permission codes used by the permission interceptor
 * (docs/11 section 5, v9). Entries expire after {@link #DEFAULT_TTL_MILLIS}; every RBAC
 * mutation evicts the cache so custom role changes take effect immediately.
 */
@Component
public class PermissionCache {

    public static final long DEFAULT_TTL_MILLIS = 60_000L;

    private final RbacRepository repository;
    private final long ttlMillis;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    @Autowired
    public PermissionCache(RbacRepository repository) {
        this(repository, DEFAULT_TTL_MILLIS);
    }

    PermissionCache(RbacRepository repository, long ttlMillis) {
        this.repository = repository;
        this.ttlMillis = ttlMillis > 0 ? ttlMillis : DEFAULT_TTL_MILLIS;
    }

    /** Union of permission codes granted to the caller's roles (cached). */
    public Set<String> permissions(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return Set.of();
        }
        String key = roles.stream()
                .filter(role -> role != null && !role.isBlank())
                .sorted()
                .reduce((left, right) -> left + "," + right)
                .orElse("");
        if (key.isEmpty()) {
            return Set.of();
        }
        long now = System.currentTimeMillis();
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > now) {
            return cached.codes();
        }
        Set<String> codes = Set.copyOf(repository.permissionCodesForRoles(roles));
        cache.put(key, new Entry(codes, now + ttlMillis));
        return codes;
    }

    /** Drops every cached entry (called after role/permission mutations). */
    public void evictAll() {
        cache.clear();
    }

    /** @return number of cached role combinations (diagnostics / tests). */
    public int size() {
        return cache.size();
    }

    private record Entry(Set<String> codes, long expiresAt) {
    }
}
