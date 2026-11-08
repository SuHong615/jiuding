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
package com.suhong.jiuding.mcp.credentials;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.security.Principal;
import com.suhong.jiuding.mcp.support.Iso8601;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Personal MCP API key lifecycle (docs/15 §4.2): generate once, persist only the
 * SHA-256 hash, list/revoke own keys, validate incoming {@code X-API-Key} values.
 *
 * <p>Key format: {@code tk_} + 32 URL-safe random characters. {@code keyPrefix} stores the
 * first 12 characters so the owner can recognise a key without exposing it.
 */
@Service
public class ApiKeyService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder URL_ENCODER = Base64.getUrlEncoder().withoutPadding();
    private static final int KEY_BYTES = 24;
    private static final int PREFIX_LENGTH = 12;
    private static final int MAX_EXPIRES_IN_DAYS = 3650;

    private final ApiKeyRepository repository;

    public ApiKeyService(ApiKeyRepository repository) {
        this.repository = repository;
    }

    public ApiKeyCreated create(Principal owner, ApiKeyCreateRequest request) {
        String name = request == null || request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw BizException.invalid("name is required");
        }
        if (name.length() > 64) {
            throw BizException.invalid("name must be at most 64 characters");
        }
        Integer expiresInDays = request.expiresInDays();
        if (expiresInDays != null && (expiresInDays < 1 || expiresInDays > MAX_EXPIRES_IN_DAYS)) {
            throw BizException.invalid("expiresInDays must be between 1 and " + MAX_EXPIRES_IN_DAYS);
        }

        String rawKey = "tk_" + URL_ENCODER.encodeToString(randomBytes(KEY_BYTES));
        String prefix = rawKey.substring(0, PREFIX_LENGTH);
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expiresAt = expiresInDays == null ? null : now.plusDays(expiresInDays);
        ApiKeyRow row = new ApiKeyRow(
                UUID.randomUUID().toString(),
                name,
                prefix,
                sha256Hex(rawKey),
                owner.userId(),
                owner.username(),
                owner.tenantId(),
                joinRoles(owner.roles()),
                "ACTIVE",
                now,
                expiresAt,
                null,
                null);
        repository.insert(row);
        return new ApiKeyCreated(row.id(), row.name(), row.keyPrefix(), rawKey,
                Iso8601.format(row.createdAt()), Iso8601.format(row.expiresAt()));
    }

    public List<ApiKeyView> list(Principal actor, boolean all) {
        if (all && !actor.isPlatformAdmin()) {
            throw BizException.forbidden("only platform administrators can list all API keys");
        }
        List<ApiKeyRow> rows = all ? repository.listAll() : repository.listByOwner(actor.userId());
        return rows.stream().map(ApiKeyView::of).toList();
    }

    public ApiKeyView revoke(Principal actor, String id) {
        ApiKeyRow row = repository.findById(id)
                .orElseThrow(() -> BizException.notFound("api key not found: " + id));
        if (!actor.isPlatformAdmin() && !row.ownerUserId().equals(actor.userId())) {
            throw BizException.forbidden("api key belongs to another user");
        }
        if (!"REVOKED".equals(row.status())) {
            repository.revoke(id, LocalDateTime.now());
        }
        return repository.findById(id).map(ApiKeyView::of).orElse(ApiKeyView.of(row));
    }

    /** @return the ACTIVE, non-expired key row matching the clear-text key, if any. */
    public Optional<ApiKeyRow> authenticate(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return Optional.empty();
        }
        Optional<ApiKeyRow> found = repository.findByHash(sha256Hex(rawKey));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        ApiKeyRow row = found.get();
        if (!"ACTIVE".equals(row.status())) {
            return Optional.empty();
        }
        if (row.expiresAt() != null && row.expiresAt().isBefore(LocalDateTime.now())) {
            return Optional.empty();
        }
        return found;
    }

    public void markUsed(String id) {
        repository.touchLastUsed(id, LocalDateTime.now());
    }

    public static String sha256Hex(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                builder.append(String.format("%02x", item));
            }
            return builder.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static Set<String> parseRoles(String rolesCsv) {
        if (rolesCsv == null || rolesCsv.isBlank()) {
            return Set.of();
        }
        Set<String> roles = new LinkedHashSet<>();
        for (String role : rolesCsv.split(",")) {
            if (!role.isBlank()) {
                roles.add(role.trim());
            }
        }
        return roles;
    }

    private static String joinRoles(Set<String> roles) {
        if (roles == null || roles.isEmpty()) {
            return "";
        }
        return roles.stream().sorted().collect(Collectors.joining(","));
    }

    private static byte[] randomBytes(int length) {
        byte[] bytes = new byte[length];
        RANDOM.nextBytes(bytes);
        return bytes;
    }
}
