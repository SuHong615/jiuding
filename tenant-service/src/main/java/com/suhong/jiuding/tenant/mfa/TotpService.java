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

import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;

/**
 * RFC 6238 TOTP implementation (HMAC-SHA1, 30 second step, 6 digits, +/-1 window) as
 * required by docs/16 section 3. Secrets are base32 encoded (RFC4648).
 */
@Component
public class TotpService {

    static final int STEP_SECONDS = 30;
    static final int DIGITS = 6;
    static final int WINDOW = 1;
    private static final int SECRET_BYTES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** Generates a random 160-bit base32 encoded secret. */
    public String generateSecret() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);
        return Base32.encode(secret);
    }

    /** RFC 6238 code for a base32 secret at the given epoch millisecond. */
    public String code(String secretBase32, long epochMillis) {
        return code(Base32.decode(secretBase32), epochMillis);
    }

    public String code(byte[] secret, long epochMillis) {
        long counter = epochMillis / 1000L / STEP_SECONDS;
        byte[] hash = hmacSha1(secret, counter);
        int offset = hash[hash.length - 1] & 0x0F;
        int binary = ((hash[offset] & 0x7F) << 24)
                | ((hash[offset + 1] & 0xFF) << 16)
                | ((hash[offset + 2] & 0xFF) << 8)
                | (hash[offset + 3] & 0xFF);
        int otp = binary % 1_000_000;
        return String.format("%06d", otp);
    }

    /** Verifies a code against the current time using the +/-1 step window. */
    public boolean verify(String secretBase32, String code, long epochMillis) {
        return verify(secretBase32, code, epochMillis, WINDOW);
    }

    public boolean verify(String secretBase32, String code, long epochMillis, int window) {
        if (secretBase32 == null || code == null) {
            return false;
        }
        String candidate = normalizeCode(code);
        if (candidate == null) {
            return false;
        }
        byte[] secret;
        try {
            secret = Base32.decode(secretBase32);
        } catch (IllegalArgumentException ex) {
            return false;
        }
        long stepMillis = STEP_SECONDS * 1000L;
        for (int offset = -window; offset <= window; offset++) {
            long shifted = epochMillis + offset * stepMillis;
            String expected = code(secret, shifted);
            if (MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                    candidate.getBytes(StandardCharsets.UTF_8))) {
                return true;
            }
        }
        return false;
    }

    /** otpauth:// URI for authenticator apps (secret, issuer, digits, period, algorithm). */
    public String otpauthUrl(String issuer, String accountName, String secretBase32) {
        String label = urlEncode(issuer) + ":" + urlEncode(accountName);
        return "otpauth://totp/" + label
                + "?secret=" + urlEncode(secretBase32)
                + "&issuer=" + urlEncode(issuer)
                + "&algorithm=SHA1&digits=" + DIGITS + "&period=" + STEP_SECONDS;
    }

    private String normalizeCode(String raw) {
        String value = raw.trim().replace(" ", "");
        if (value.length() != DIGITS || !value.chars().allMatch(Character::isDigit)) {
            return null;
        }
        return value;
    }

    private byte[] hmacSha1(byte[] secret, long counter) {
        byte[] counterBytes = new byte[8];
        long value = counter;
        for (int index = 7; index >= 0; index--) {
            counterBytes[index] = (byte) (value & 0xFF);
            value >>= 8;
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            return mac.doFinal(counterBytes);
        } catch (Exception ex) {
            throw new IllegalStateException("HMAC-SHA1 TOTP is unavailable", ex);
        }
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
