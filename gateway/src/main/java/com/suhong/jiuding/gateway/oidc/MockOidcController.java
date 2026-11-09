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
package com.suhong.jiuding.gateway.oidc;

import com.suhong.jiuding.gateway.config.MockOidcProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Dev-only Mock OIDC identity provider (docs/23 §4), registered only when
 * {@code tenancy.auth.mock-oidc.enabled=true}.
 *
 * <p>Endpoints:
 * <ul>
 *   <li>{@code GET /mock-oidc/.well-known/openid-configuration} – discovery document;</li>
 *   <li>{@code GET /mock-oidc/authorize} – immediately redirects back to {@code redirect_uri}
 *       with {@code code} (and {@code state});</li>
 *   <li>{@code POST /mock-oidc/token} – authorization-code + PKCE S256 exchange;</li>
 *   <li>{@code GET /mock-oidc/userinfo} – returns {@code sub/name/roles} for the access token.</li>
 * </ul>
 * This is a test aid only; never enable it in a production deployment.
 */
@RestController
@RequestMapping("/mock-oidc")
@ConditionalOnProperty(prefix = "tenancy.auth.mock-oidc", name = "enabled", havingValue = "true")
public class MockOidcController {

    private static final Logger log = LoggerFactory.getLogger(MockOidcController.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final int MAX_PENDING = 1_000;

    private final MockOidcProperties properties;
    private final ConcurrentHashMap<String, PendingCode> codes = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AccessToken> tokens = new ConcurrentHashMap<>();

    public MockOidcController(MockOidcProperties properties) {
        this.properties = properties;
    }

    @GetMapping("/.well-known/openid-configuration")
    public Map<String, Object> discovery(HttpServletRequest request) {
        String issuer = issuer(request);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("issuer", issuer);
        body.put("authorization_endpoint", issuer + "/authorize");
        body.put("token_endpoint", issuer + "/token");
        body.put("userinfo_endpoint", issuer + "/userinfo");
        body.put("response_types_supported", List.of("code"));
        body.put("grant_types_supported", List.of("authorization_code"));
        body.put("subject_types_supported", List.of("public"));
        body.put("code_challenge_methods_supported", List.of("S256"));
        body.put("scopes_supported", List.of("openid", "profile", "email"));
        return body;
    }

    @GetMapping("/authorize")
    public ResponseEntity<?> authorize(@RequestParam Map<String, String> params) {
        purgeExpired();
        String redirectUri = params.get("redirect_uri");
        String state = params.get("state");
        String codeChallenge = params.get("code_challenge");
        if (redirectUri == null || redirectUri.isBlank()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_request", "redirect_uri is required");
        }
        if (codes.size() >= MAX_PENDING) {
            purgeExpired();
        }
        String code = randomToken(24);
        long expiresAt = System.currentTimeMillis() + Math.max(1L, properties.getCodeTtlSeconds()) * 1000L;
        codes.put(code, new PendingCode(redirectUri, codeChallenge, expiresAt));
        String target = appendQuery(redirectUri, "code", code);
        if (state != null && !state.isBlank()) {
            target = appendQuery(target, "state", state);
        }
        return ResponseEntity.status(HttpStatus.FOUND).header(HttpHeaders.LOCATION, target).build();
    }

    @PostMapping("/token")
    public ResponseEntity<?> token(@RequestParam Map<String, String> params) {
        purgeExpired();
        String grantType = params.get("grant_type");
        if (!"authorization_code".equals(grantType)) {
            return error(HttpStatus.BAD_REQUEST, "unsupported_grant_type", "grant_type must be authorization_code");
        }
        String code = params.get("code");
        PendingCode pending = code == null ? null : codes.get(code);
        if (pending == null || pending.expiresAt() < System.currentTimeMillis()) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "unknown or expired authorization code");
        }
        String redirectUri = params.get("redirect_uri");
        if (redirectUri != null && !redirectUri.equals(pending.redirectUri())) {
            return error(HttpStatus.BAD_REQUEST, "invalid_grant", "redirect_uri mismatch");
        }
        String verifier = params.get("code_verifier");
        String challenge = pending.codeChallenge();
        if (challenge != null && !challenge.isBlank()) {
            String computed = verifier == null ? "" : base64Url(sha256(verifier));
            if (!MessageDigest.isEqual(computed.getBytes(StandardCharsets.UTF_8),
                    challenge.getBytes(StandardCharsets.UTF_8))) {
                return error(HttpStatus.BAD_REQUEST, "invalid_grant", "PKCE verification failed");
            }
        }
        codes.remove(code);
        String accessToken = randomToken(32);
        long expiresIn = Math.max(1L, properties.getTokenTtlSeconds());
        tokens.put(accessToken, new AccessToken(System.currentTimeMillis() + expiresIn * 1000L));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("access_token", accessToken);
        body.put("token_type", "Bearer");
        body.put("expires_in", expiresIn);
        body.put("scope", "openid profile email");
        return ResponseEntity.ok(body);
    }

    @GetMapping("/userinfo")
    public ResponseEntity<?> userinfo(@RequestHeader(name = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        purgeExpired();
        String token = null;
        if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            token = authorization.substring(7).trim();
        }
        AccessToken accessToken = token == null ? null : tokens.get(token);
        if (accessToken == null || accessToken.expiresAt() < System.currentTimeMillis()) {
            return error(HttpStatus.UNAUTHORIZED, "invalid_token", "unknown or expired access token");
        }
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", properties.getUsername());
        claims.put("name", properties.getDisplayName());
        claims.put("preferred_username", properties.getUsername());
        claims.put("roles", Arrays.stream(properties.getRoles().split("[,\\s]+"))
                .filter(role -> !role.isBlank())
                .map(String::trim)
                .toList());
        return ResponseEntity.ok(claims);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String error, String description) {
        log.debug("mock OIDC request rejected ({}): {}", error, description);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("error", error);
        body.put("error_description", description);
        return ResponseEntity.status(status).body(body);
    }

    private String issuer(HttpServletRequest request) {
        String configured = properties.getIssuer();
        if (configured != null && !configured.isBlank()) {
            return trimSlash(configured.trim());
        }
        String base = ServletUriComponentsBuilder.fromContextPath(request).build().toUriString();
        return trimSlash(base) + "/mock-oidc";
    }

    private void purgeExpired() {
        long now = System.currentTimeMillis();
        codes.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
        tokens.entrySet().removeIf(entry -> entry.getValue().expiresAt() < now);
    }

    private static String appendQuery(String url, String name, String value) {
        String separator = url.contains("?") ? "&" : "?";
        return url + separator + name + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return B64_URL.encodeToString(buffer);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String base64Url(byte[] value) {
        return B64_URL.encodeToString(value);
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private record PendingCode(String redirectUri, String codeChallenge, long expiresAt) {
    }

    private record AccessToken(long expiresAt) {
    }
}
