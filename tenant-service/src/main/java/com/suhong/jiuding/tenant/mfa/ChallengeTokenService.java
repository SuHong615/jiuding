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
package com.suhong.jiuding.tenant.mfa;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.suhong.jiuding.common.error.BizException;
import com.suhong.jiuding.common.error.ErrorCode;
import com.suhong.jiuding.common.security.JwtService;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Short lived (5 minutes) HS256 challenge token issued after a correct password when the
 * account has MFA enabled (docs/16 section 3). The token carries the {@code mfa=true} claim
 * and the challenge id but never a complete role set; it is only accepted by
 * {@code /internal/auth/authenticate-mfa}.
 */
@Service
public class ChallengeTokenService {

    public static final long TTL_SECONDS = 300;
    private static final String PURPOSE = "MFA_CHALLENGE";
    private static final Base64.Encoder B64E = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    public ChallengeTokenService(JwtService jwtService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.objectMapper = objectMapper;
    }

    public String issue(String accountId, String tenantId, String username, String challengeId) {
        long now = Instant.now().getEpochSecond();
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "HS256");
        header.put("typ", "JWT");

        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("sub", accountId);
        claims.put("tid", tenantId);
        claims.put("name", username);
        claims.put("jti", challengeId);
        claims.put("mfa", true);
        claims.put("purpose", PURPOSE);
        claims.put("iat", now);
        claims.put("exp", now + TTL_SECONDS);

        String signingInput = encode(header) + "." + encode(claims);
        return signingInput + "." + jwtService.sign(signingInput);
    }

    public ChallengeClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw invalidToken();
        }
        String[] parts = token.split("\\.");
        if (parts.length != 3) {
            throw invalidToken();
        }
        String signingInput = parts[0] + "." + parts[1];
        byte[] expected = jwtService.sign(signingInput).getBytes(StandardCharsets.UTF_8);
        byte[] actual = parts[2].getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            throw invalidToken();
        }
        JsonNode claims;
        try {
            claims = objectMapper.readTree(B64D.decode(parts[1]));
        } catch (Exception ex) {
            throw invalidToken();
        }
        if (!claims.path("mfa").asBoolean(false) || !PURPOSE.equals(claims.path("purpose").asText())) {
            throw invalidToken();
        }
        long exp = claims.path("exp").asLong(0);
        if (exp <= Instant.now().getEpochSecond()) {
            throw invalidToken();
        }
        String accountId = claims.path("sub").asText("");
        String tenantId = claims.path("tid").asText("");
        String challengeId = claims.path("jti").asText("");
        if (accountId.isBlank() || tenantId.isBlank() || challengeId.isBlank()) {
            throw invalidToken();
        }
        return new ChallengeClaims(accountId, tenantId, claims.path("name").asText(null),
                challengeId, exp);
    }

    private String encode(Map<String, Object> value) {
        try {
            return B64E.encodeToString(objectMapper.writeValueAsBytes(value));
        } catch (Exception ex) {
            throw new IllegalStateException("cannot encode challenge token", ex);
        }
    }

    private BizException invalidToken() {
        return new BizException(ErrorCode.UNAUTHORIZED, "invalid credentials");
    }

    /** Decoded challenge token claims. */
    public record ChallengeClaims(String accountId, String tenantId, String username,
                                  String challengeId, long expiresAtEpochSecond) {
    }
}
