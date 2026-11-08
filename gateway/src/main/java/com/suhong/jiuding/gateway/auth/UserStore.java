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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Gateway-local user directory. Users are held in memory only; see
 * {@link PasswordHasher} for the password strategy.
 *
 * <p>Preset users come from {@code docs/02-api-contracts.md} §1 and can be fully
 * replaced by the {@code TENANCY_USERS} environment variable (JSON array):
 * <pre>
 * [{"userId":"u-admin","username":"admin","password":"Admin@123",
 *   "tenantId":null,"roles":["PLATFORM_ADMIN"]}]
 * </pre>
 * A {@code passwordHash} field (PBKDF2 format) is accepted instead of the
 * plaintext {@code password} so real deployments never need plaintext in env.
 */
@Component
public class UserStore {

    private static final Logger log = LoggerFactory.getLogger(UserStore.class);

    private final Map<String, TenantUser> usersByUsername;
    private final Map<String, TenantUser> usersByUserId;

    public UserStore(ObjectMapper objectMapper,
                     @Value("${tenancy.auth.users-json:}") String usersJson) {
        this.usersByUsername = load(objectMapper, usersJson);
        this.usersByUserId = new LinkedHashMap<>();
        for (TenantUser user : usersByUsername.values()) {
            usersByUserId.putIfAbsent(user.userId(), user);
        }
        log.info("gateway user store initialized with {} user(s)", usersByUsername.size());
    }

    public Optional<TenantUser> find(String username) {
        if (username == null || username.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersByUsername.get(username));
    }

    /** Used by {@code /api/auth/me} to distinguish platform tokens from tenant-account tokens. */
    public Optional<TenantUser> findByUserId(String userId) {
        if (userId == null || userId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(usersByUserId.get(userId));
    }

    public int size() {
        return usersByUsername.size();
    }

    private Map<String, TenantUser> load(ObjectMapper objectMapper, String usersJson) {
        Map<String, TenantUser> users = new LinkedHashMap<>();
        if (usersJson == null || usersJson.isBlank()) {
            for (Map<String, Object> entry : PresetUsers.defaults()) {
                Set<String> presetRoles = new LinkedHashSet<>();
                for (Object role : (Iterable<?>) entry.get("roles")) {
                    presetRoles.add(String.valueOf(role));
                }
                add(users, new TenantUser(
                        (String) entry.get("userId"),
                        (String) entry.get("username"),
                        blankToNull((String) entry.get("tenantId")),
                        presetRoles,
                        PasswordHasher.hash((String) entry.get("password"))));
            }
            return users;
        }
        try {
            JsonNode root = objectMapper.readTree(usersJson);
            if (!root.isArray()) {
                throw new IllegalStateException("TENANCY_USERS must be a JSON array");
            }
            for (JsonNode node : root) {
                String username = required(node, "username");
                String userId = node.hasNonNull("userId") ? node.get("userId").asText() : "u-" + username;
                String tenantId = blankToNull(node.hasNonNull("tenantId") ? node.get("tenantId").asText() : null);
                Set<String> roles = new LinkedHashSet<>();
                JsonNode rolesNode = node.get("roles");
                if (rolesNode != null && rolesNode.isArray()) {
                    rolesNode.forEach(role -> {
                        if (role != null && !role.asText().isBlank()) {
                            roles.add(role.asText());
                        }
                    });
                }
                String passwordHash = node.hasNonNull("passwordHash") ? node.get("passwordHash").asText() : null;
                if (passwordHash == null || passwordHash.isBlank()) {
                    passwordHash = PasswordHasher.hash(required(node, "password"));
                }
                add(users, new TenantUser(userId, username, tenantId, roles, passwordHash));
            }
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("failed to parse TENANCY_USERS: " + ex.getMessage(), ex);
        }
        return users;
    }

    private static void add(Map<String, TenantUser> users, TenantUser user) {
        if (users.putIfAbsent(user.username(), user) != null) {
            throw new IllegalStateException("duplicate username in user store: " + user.username());
        }
    }

    private static String required(JsonNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || value.asText().isBlank()) {
            throw new IllegalStateException("TENANCY_USERS entry is missing '" + field + "'");
        }
        return value.asText();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
