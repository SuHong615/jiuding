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
package com.suhong.jiuding.common.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Minimal, dependency-free HS256 JWT implementation. Kept in-tree so the whole
 * platform can run offline without pulling a JWT library.
 */
public class JwtService {

    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final byte[] secret;
    private final Duration ttl;

    public JwtService(String secret, Duration ttl) {
        if (secret == null || secret.length() < 16) {
            throw new IllegalArgumentException("jwt secret must be at least 16 chars");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.ttl = ttl;
    }

    public String issue(Principal principal) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", principal.userId());
        claims.put("name", principal.username());
        claims.put("tid", principal.tenantId() == null ? "" : principal.tenantId());
        claims.put("roles", principal.roles() == null ? List.of() : List.copyOf(principal.roles()));
        if (principal.orgUnitId() != null && !principal.orgUnitId().isBlank()) {
            claims.put("oid", principal.orgUnitId());
        }
        claims.put("iat", now);
        claims.put("exp", now + ttl.getSeconds());
        claims.put("jti", UUID.randomUUID().toString());

        String signingInput = encodeJson(header) + "." + encodeJson(claims);
        return signingInput + "." + sign(signingInput);
    }

    @SuppressWarnings("unchecked")
    public Principal parse(String token) {
        if (token == null || token.isBlank()) {
            throw unauthorized("missing token");
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw unauthorized("malformed token");
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = sign(signingInput).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw unauthorized("invalid signature");
        }
        Map<String, Object> claims;
        try {
            claims = MAPPER.readValue(B64D.decode(parts[1]), Map.class);
        } catch (Exception e) {
            throw unauthorized("invalid claims");
        }
        Object exp = claims.get("exp");
        if (!(exp instanceof Number n) || n.longValue() < Instant.now().getEpochSecond()) {
            throw unauthorized("token expired");
        }
        String tenantId = String.valueOf(claims.getOrDefault("tid", ""));
        List<String> roles = (List<String>) claims.getOrDefault("roles", List.of());
        String orgUnitId = claims.get("oid") == null ? null : String.valueOf(claims.get("oid"));
        return new Principal(
                String.valueOf(claims.get("sub")),
                String.valueOf(claims.getOrDefault("name", claims.get("sub"))),
                tenantId.isBlank() ? null : tenantId,
                new java.util.LinkedHashSet<>(roles),
                "JWT",
                orgUnitId == null || orgUnitId.isBlank() ? null : orgUnitId);
    }

    public long ttlSeconds() {
        return ttl.getSeconds();
    }

    public String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return B64E.encodeToString(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("hmac failure", e);
        }
    }

    private String encodeJson(Map<String, Object> value) {
        try {
            return B64E.encodeToString(MAPPER.writeValueAsBytes(value));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static BizException unauthorized(String message) {
        return new BizException(ErrorCode.UNAUTHORIZED, message);
    }

    /** Utility used by tests. */
    public static String digest(String salt) {
        try {
            return B64E.encodeToString(MessageDigest.getInstance("SHA-256")
                    .digest(salt.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
