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
package com.suhong.jiuding.cli;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * Offline JWT payload reader used by {@code auth whoami} and post-login config persistence.
 * Only the payload segment is decoded (base64url JSON); the signature is NOT verified -
 * the CLI never trusts a token for authorization, it just displays what it carries.
 */
public final class JwtToken {

    private final JsonNode payload;

    private JwtToken(JsonNode payload) {
        this.payload = payload;
    }

    public static JwtToken parse(String token) {
        if (token == null || token.isBlank()) {
            throw new CliUsageException("token 为空");
        }
        String[] parts = token.trim().split("\\.");
        if (parts.length < 2) {
            throw new CliUsageException("token 不是有效的 JWT（缺少 payload 段）");
        }
        String segment = parts[1];
        int remainder = segment.length() % 4;
        if (remainder == 2) {
            segment += "==";
        } else if (remainder == 3) {
            segment += "=";
        }
        byte[] bytes;
        try {
            bytes = Base64.getUrlDecoder().decode(segment);
        } catch (IllegalArgumentException e) {
            throw new CliUsageException("token payload 不是合法的 base64url");
        }
        JsonNode node = Json.parse(new String(bytes, StandardCharsets.UTF_8));
        if (node == null || !node.isObject()) {
            throw new CliUsageException("token payload 不是 JSON 对象");
        }
        return new JwtToken(node);
    }

    public JsonNode payload() {
        return payload;
    }

    /** Claim as text; empty string is normalized to null. */
    public String text(String claim) {
        return Json.text(payload, claim, null);
    }

    /** Claims like {@code roles} are arrays in the gateway-issued JWT. */
    public List<String> roles() {
        JsonNode node = Json.field(payload, "roles");
        List<String> roles = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> {
                if (!value.isNull()) {
                    roles.add(value.asText());
                }
            });
        }
        return roles;
    }

    public Long epochSeconds(String claim) {
        JsonNode node = Json.field(payload, claim);
        return node.isNumber() ? node.asLong() : null;
    }

    public String issuedAt() {
        Long iat = epochSeconds("iat");
        return iat == null ? null : Instant.ofEpochSecond(iat).toString();
    }

    public String expiresAt() {
        Long exp = epochSeconds("exp");
        return exp == null ? null : Instant.ofEpochSecond(exp).toString();
    }

    /** True when the token carries an {@code exp} claim that is in the past. */
    public boolean expired() {
        Long exp = epochSeconds("exp");
        return exp != null && Instant.now().getEpochSecond() >= exp;
    }
}
