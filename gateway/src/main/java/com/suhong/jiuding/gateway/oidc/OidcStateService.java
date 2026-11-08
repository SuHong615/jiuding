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

import com.suhong.jiuding.gateway.config.OidcProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stateless OIDC {@code state} plus PKCE verifier handling (docs/16 §2).
 *
 * <p>{@code state = nonce "." issuedAtEpochMillis "." HMAC-SHA256(secret, nonce "." issuedAt)}
 * — tampering or a state older than {@code tenancy.auth.oidc.state-ttl-seconds}
 * (10 minutes by default) is rejected. The PKCE {@code code_verifier} is kept server-side
 * in an in-memory map keyed by the signed state and consumed exactly once at callback.
 */
@Service
public class OidcStateService {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Base64.Encoder B64_URL = Base64.getUrlEncoder().withoutPadding();
    private static final int NONCE_BYTES = 16;
    private static final int VERIFIER_BYTES = 32;
    private static final long CLOCK_SKEW_MS = 60_000L;

    private final OidcProperties properties;
    private final ConcurrentHashMap<String, PkceSession> sessions = new ConcurrentHashMap<>();

    public OidcStateService(OidcProperties properties) {
        this.properties = properties;
    }

    /** Issues a new state + PKCE pair for one authorization request (legacy static provider). */
    public AuthorizationRequest newAuthorizationRequest() {
        return newAuthorizationRequest(null);
    }

    /** Issues a new state + PKCE pair bound to a dynamic provider code (docs/20 §3). */
    public AuthorizationRequest newAuthorizationRequest(String providerCode) {
        purgeExpired();
        String nonce = randomToken(NONCE_BYTES);
        long issuedAt = Instant.now().toEpochMilli();
        String signature = sign(nonce, issuedAt);
        String state = nonce + "." + issuedAt + "." + signature;
        String verifier = randomToken(VERIFIER_BYTES);
        String challenge = base64Url(sha256(verifier));
        sessions.put(state, new PkceSession(verifier, issuedAt, providerCode));
        return new AuthorizationRequest(state, challenge, verifier);
    }

    /**
     * Validates the signed state and returns the matching PKCE session (one-time use).
     * Tampered / expired / unknown states return empty.
     */
    public Optional<PkceSession> consume(String state) {
        if (state == null || state.isBlank()) {
            return Optional.empty();
        }
        String[] parts = state.split("\\.");
        if (parts.length != 3) {
            return Optional.empty();
        }
        long issuedAt;
        try {
            issuedAt = Long.parseLong(parts[1]);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        String expected = sign(parts[0], issuedAt);
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expectedBytes, actualBytes)) {
            return Optional.empty();
        }
        long now = Instant.now().toEpochMilli();
        long ttlMs = Math.max(1L, properties.getStateTtlSeconds()) * 1000L;
        if (now - issuedAt > ttlMs || issuedAt - now > CLOCK_SKEW_MS) {
            sessions.remove(state);
            return Optional.empty();
        }
        return Optional.ofNullable(sessions.remove(state));
    }

    /** Visible for tests: current number of in-flight authorization requests. */
    public int pendingRequests() {
        return sessions.size();
    }

    private String sign(String nonce, long issuedAt) {
        String secret = properties.getStateSecret();
        if (secret == null || secret.length() < 16) {
            throw new IllegalStateException("tenancy.auth.oidc.state-secret must be at least 16 characters");
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return B64_URL.encodeToString(mac.doFinal(
                    (nonce + "." + issuedAt).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", ex);
        }
    }

    private void purgeExpired() {
        long cutoff = Instant.now().toEpochMilli()
                - Math.max(1L, properties.getStateTtlSeconds()) * 1000L;
        Iterator<Map.Entry<String, PkceSession>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, PkceSession> entry = iterator.next();
            if (entry.getValue().issuedAt() < cutoff) {
                iterator.remove();
            }
        }
    }

    private static String randomToken(int bytes) {
        byte[] buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return B64_URL.encodeToString(buffer);
    }

    private static String base64Url(byte[] value) {
        return B64_URL.encodeToString(value);
    }

    private static byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    /** One authorization request: signed state + PKCE challenge/verifier. */
    public record AuthorizationRequest(String state, String codeChallenge, String codeVerifier) {
    }

    /** Server-side PKCE session bound to a signed state (and optional dynamic provider code). */
    public record PkceSession(String codeVerifier, long issuedAt, String providerCode) {
    }
}
