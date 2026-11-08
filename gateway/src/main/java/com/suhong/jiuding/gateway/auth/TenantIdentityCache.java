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

import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Remembers the tenant-account login username for the account id carried in the JWT.
 *
 * <p>The tenant account JWT's {@code sub} is the account id and its {@code name} claim is the
 * display name, so the original login username (required by the tenant-service switch /
 * candidate endpoints, docs/20 §3) is not recoverable from the token alone. This small
 * in-memory cache bridges the gap; multi-replica deployments may need a shared store.
 */
@Component
public class TenantIdentityCache {

    private static final int MAX_ENTRIES = 10_000;

    private final ConcurrentHashMap<String, String> usernames = new ConcurrentHashMap<>();

    public void remember(String accountId, String username) {
        if (accountId == null || accountId.isBlank() || username == null || username.isBlank()) {
            return;
        }
        if (usernames.size() >= MAX_ENTRIES) {
            usernames.clear();
        }
        usernames.put(accountId, username);
    }

    public Optional<String> usernameFor(String accountId) {
        if (accountId == null || accountId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(usernames.get(accountId));
    }

    /** Visible for tests. */
    public void clear() {
        usernames.clear();
    }
}
