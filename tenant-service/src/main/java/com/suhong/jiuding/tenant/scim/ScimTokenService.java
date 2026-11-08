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
package com.suhong.jiuding.tenant.scim;

import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.repository.TenantRepository;
import com.suhong.jiuding.tenant.repository.TenantRow;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.List;
import java.util.UUID;

/**
 * SCIM bearer token management (docs/16 section 7.1).
 *
 * <p>Tokens are generated as {@code scim_<random hex>}; only their SHA-256 digest is stored.
 * The clear text token is returned exactly once by the creation endpoint. Each token is
 * bound to one tenant and authenticates the {@code /scim/v2/**} endpoints.
 */
@Service
public class ScimTokenService {

    static final String TOKEN_PREFIX = "scim_";
    private static final int RANDOM_BYTES = 24;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final ScimTokenRepository tokens;
    private final TenantRepository tenants;
    private final AuditService auditService;

    public ScimTokenService(ScimTokenRepository tokens, TenantRepository tenants,
                            AuditService auditService) {
        this.tokens = tokens;
        this.tenants = tenants;
        this.auditService = auditService;
    }

    // ------------------------------------------------------------------ management API

    public List<ScimTokenView> list(String tenantId) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        return tokens.listByTenant(tenantId).stream().map(ScimTokenView::of).toList();
    }

    public ScimTokenCreatedView create(ScimTokenCreateRequest request) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        TenantRow tenant = tenants.findById(request.tenantId())
                .filter(row -> row.deletedAt() == null)
                .orElseThrow(() -> BizException.notFound("tenant not found: " + request.tenantId()));
        String clearText = TOKEN_PREFIX + randomHex(RANDOM_BYTES);
        String prefix = clearText.substring(0, Math.min(13, clearText.length()));
        var now = TimeUtil.now();
        ScimTokenRow row = new ScimTokenRow(
                UUID.randomUUID().toString(),
                tenant.id(),
                request.name().trim(),
                prefix,
                sha256(clearText),
                "ACTIVE",
                SecurityScope.actor(scope),
                now,
                null,
                null);
        tokens.insert(row);
        auditService.record(tenant.id(), SecurityScope.actor(scope), "SCIM_TOKEN_CREATED",
                "SCIM token " + prefix + " created for tenant " + tenant.tenantCode());
        return new ScimTokenCreatedView(row.id(), row.tenantId(), row.name(), row.tokenPrefix(),
                clearText, row.status(), TimeUtil.iso(row.createdAt()));
    }

    public ScimTokenView revoke(String id) {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        ScimTokenRow row = tokens.findById(id)
                .orElseThrow(() -> BizException.notFound("SCIM token not found: " + id));
        if (!"REVOKED".equals(row.status())) {
            tokens.revoke(row.id(), TimeUtil.now());
            auditService.record(row.tenantId(), SecurityScope.actor(scope), "SCIM_TOKEN_REVOKED",
                    "SCIM token " + row.tokenPrefix() + " revoked");
            row = tokens.findById(id).orElseThrow();
        }
        return ScimTokenView.of(row);
    }

    // ------------------------------------------------------------------ SCIM authentication

    /**
     * Resolves the tenant bound to a {@code Authorization: Bearer scim_...} header.
     *
     * @return the token principal (tenant id, token id and display prefix)
     * @throws ScimException 401 for missing/invalid/revoked tokens
     */
    public ScimPrincipal authenticateTenant(String authorizationHeader) {
        if (authorizationHeader == null || !authorizationHeader.startsWith("Bearer ")) {
            throw ScimException.unauthorized("SCIM bearer token is required");
        }
        String clearText = authorizationHeader.substring("Bearer ".length()).trim();
        if (!clearText.startsWith(TOKEN_PREFIX)) {
            throw ScimException.unauthorized("SCIM bearer token is required");
        }
        ScimTokenRow row = tokens.findActiveByHash(sha256(clearText))
                .orElseThrow(() -> ScimException.unauthorized("invalid or revoked SCIM token"));
        TenantRow tenant = tenants.findById(row.tenantId())
                .filter(value -> value.deletedAt() == null)
                .orElseThrow(() -> ScimException.unauthorized("invalid or revoked SCIM token"));
        tokens.updateLastUsed(row.id(), TimeUtil.now());
        return new ScimPrincipal(tenant.id(), row.id(), row.tokenPrefix());
    }

    /** Authenticated SCIM token principal bound to one tenant. */
    public record ScimPrincipal(String tenantId, String tokenId, String prefix) {
    }

    // ------------------------------------------------------------------ helpers

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte item : digest) {
                hex.append(String.format("%02x", item));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static String randomHex(int bytes) {
        byte[] random = new byte[bytes];
        RANDOM.nextBytes(random);
        StringBuilder hex = new StringBuilder(bytes * 2);
        for (byte item : random) {
            hex.append(String.format("%02x", item));
        }
        return hex.toString();
    }
}
