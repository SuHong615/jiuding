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
package com.suhong.jiuding.tenant.sso;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.tenant.TenantScope;
import com.suhong.jiuding.tenant.permission.RbacService;
import com.suhong.jiuding.tenant.service.AuditService;
import com.suhong.jiuding.tenant.support.SecurityScope;
import com.suhong.jiuding.tenant.support.Texts;
import com.suhong.jiuding.tenant.support.TimeUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * OIDC SSO provider management (docs/20 section 3).
 *
 * <p>Admin CRUD and discovery tests require a platform administrator JWT and are audited as
 * {@code SSO_PROVIDER_*}. The public login-button list only contains enabled providers and
 * never exposes client secrets; the internal gateway list additionally carries the client
 * credentials needed for the authorization-code exchange.
 */
@Service
public class SsoProviderService {

    private static final Logger log = LoggerFactory.getLogger(SsoProviderService.class);

    private static final Duration DISCOVERY_TIMEOUT = Duration.ofSeconds(5);

    private final SsoProviderRepository repository;
    private final AuditService auditService;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public SsoProviderService(SsoProviderRepository repository,
                              AuditService auditService,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(DISCOVERY_TIMEOUT)
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ------------------------------------------------------------------ admin CRUD

    public List<SsoProviderView> list() {
        requirePlatformAdmin();
        return repository.list().stream().map(SsoProviderView::of).toList();
    }

    public SsoProviderView get(String id) {
        requirePlatformAdmin();
        return SsoProviderView.of(requireProvider(id));
    }

    public SsoProviderView create(SsoProviderCreateRequest request) {
        TenantScope scope = requirePlatformAdmin();
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        String code = normalizeCode(request.code());
        String name = Texts.blankToNull(request.name());
        if (name == null) {
            throw BizException.invalid("provider name is required");
        }
        if (repository.findByCode(code).isPresent()) {
            throw BizException.conflict("sso provider code already exists: " + code);
        }
        var now = TimeUtil.now();
        SsoProviderRow row = new SsoProviderRow(
                UUID.randomUUID().toString(),
                code,
                Texts.truncate(name, 64),
                Texts.truncate(Texts.blankToNull(request.issuer()), 512),
                Texts.truncate(Texts.blankToNull(request.clientId()), 256),
                Texts.truncate(Texts.blankToNull(request.clientSecret()), 512),
                Texts.truncate(Texts.blankToNull(request.scopes()), 256),
                Texts.truncate(Texts.blankToNull(request.redirectUri()), 512),
                Texts.truncate(Texts.blankToNull(request.frontendRedirect()), 512),
                Texts.truncate(Texts.blankToNull(request.roleClaim()), 64),
                Texts.truncate(Texts.blankToNull(request.defaultRole()), 32),
                Boolean.TRUE.equals(request.enabled()),
                now,
                now);
        repository.insert(row);
        audit(SecurityScope.actor(scope), "SSO_PROVIDER_CREATED",
                "sso provider " + code + " created (enabled=" + row.enabled() + ")");
        return SsoProviderView.of(row);
    }

    public SsoProviderView update(String id, SsoProviderUpdateRequest request) {
        TenantScope scope = requirePlatformAdmin();
        SsoProviderRow existing = requireProvider(id);
        if (request == null) {
            throw BizException.invalid("request body is required");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        putText(fields, "name", request.name(), 64);
        putText(fields, "issuer", request.issuer(), 512);
        putText(fields, "client_id", request.clientId(), 256);
        putText(fields, "client_secret", request.clientSecret(), 512);
        putText(fields, "scopes", request.scopes(), 256);
        putText(fields, "redirect_uri", request.redirectUri(), 512);
        putText(fields, "frontend_redirect", request.frontendRedirect(), 512);
        putText(fields, "role_claim", request.roleClaim(), 64);
        putText(fields, "default_role", request.defaultRole(), 32);
        if (request.enabled() != null) {
            fields.put("enabled", request.enabled());
        }
        fields.put("updated_at", TimeUtil.now());
        repository.updateFields(id, fields);
        audit(SecurityScope.actor(scope), "SSO_PROVIDER_UPDATED",
                "sso provider " + existing.code() + " updated");
        return SsoProviderView.of(requireProvider(id));
    }

    public void delete(String id) {
        TenantScope scope = requirePlatformAdmin();
        SsoProviderRow existing = requireProvider(id);
        repository.delete(id);
        audit(SecurityScope.actor(scope), "SSO_PROVIDER_DELETED",
                "sso provider " + existing.code() + " deleted");
    }

    /** Pulls {@code <issuer>/.well-known/openid-configuration} and validates the document. */
    public SsoProviderTestResult test(String id) {
        TenantScope scope = requirePlatformAdmin();
        SsoProviderRow provider = requireProvider(id);
        String issuer = Texts.blankToNull(provider.issuer());
        if (issuer == null) {
            throw BizException.invalid("provider issuer is required before testing");
        }
        String base = issuer.endsWith("/") ? issuer.substring(0, issuer.length() - 1) : issuer;
        URI discoveryUri;
        try {
            discoveryUri = URI.create(base + "/.well-known/openid-configuration");
        } catch (IllegalArgumentException ex) {
            throw BizException.invalid("provider issuer is not a valid URL");
        }
        if (!"http".equalsIgnoreCase(discoveryUri.getScheme())
                && !"https".equalsIgnoreCase(discoveryUri.getScheme())) {
            throw BizException.invalid("provider issuer must be an http(s) URL");
        }
        JsonNode document;
        int status;
        try {
            HttpRequest request = HttpRequest.newBuilder(discoveryUri)
                    .timeout(DISCOVERY_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString());
            status = response.statusCode();
            if (status != 200) {
                throw BizException.invalid("oidc discovery returned HTTP " + status);
            }
            document = objectMapper.readTree(response.body());
        } catch (IOException ex) {
            throw BizException.invalid("oidc discovery request failed: " + ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw BizException.invalid("oidc discovery request was interrupted");
        }
        boolean valid = document != null
                && (document.hasNonNull("authorization_endpoint") || document.hasNonNull("issuer"));
        if (!valid) {
            throw BizException.invalid("oidc discovery document is missing issuer/authorization_endpoint");
        }
        String authorizationEndpoint = document.path("authorization_endpoint").asText(null);
        audit(SecurityScope.actor(scope), "SSO_PROVIDER_TESTED",
                "sso provider " + provider.code() + " discovery test succeeded");
        return new SsoProviderTestResult(provider.id(), provider.code(), true, issuer,
                authorizationEndpoint, "discovery ok");
    }

    // ------------------------------------------------------------------ public / internal lists

    public List<PublicSsoProviderView> publicProviders() {
        return repository.listEnabled().stream().map(PublicSsoProviderView::of).toList();
    }

    public List<InternalSsoProviderView> internalEnabled() {
        return repository.listEnabled().stream().map(InternalSsoProviderView::of).toList();
    }

    // ------------------------------------------------------------------ helpers

    private TenantScope requirePlatformAdmin() {
        TenantScope scope = SecurityScope.require();
        SecurityScope.requireJwtPlatformAdmin(scope);
        return scope;
    }

    private SsoProviderRow requireProvider(String id) {
        String value = Texts.blankToNull(id);
        return repository.findById(value == null ? "" : value)
                .orElseThrow(() -> BizException.notFound("sso provider not found: " + id));
    }

    private String normalizeCode(String raw) {
        String code = Texts.blankToNull(raw);
        if (code == null) {
            throw BizException.invalid("provider code is required");
        }
        String normalized = code.trim().toLowerCase(Locale.ROOT);
        if (!normalized.matches("^[a-z0-9][a-z0-9_-]{1,31}$")) {
            throw BizException.invalid("provider code must match ^[a-z0-9][a-z0-9_-]{1,31}$");
        }
        return normalized;
    }

    private void putText(Map<String, Object> fields, String column, String value, int maxLength) {
        if (value != null) {
            fields.put(column, Texts.truncate(Texts.blankToNull(value), maxLength));
        }
    }

    private void audit(String actor, String action, String detail) {
        try {
            auditService.record(RbacService.PLATFORM_AUDIT_TENANT, actor, action, detail);
        } catch (RuntimeException ex) {
            log.warn("could not persist sso provider audit {}: {}", action, ex.toString());
        }
    }
}
