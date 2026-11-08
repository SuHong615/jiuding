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
package com.suhong.jiuding.tenant.service;

import com.suhong.jiuding.common.error.BizException;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.Base64;
import java.util.Locale;

/**
 * PBKDF2-HMAC-SHA256 password hashing for tenant accounts (docs/11 section 2.3):
 * 210000 iterations, 256-bit derived key, 16-byte {@link SecureRandom} salt.
 *
 * <p>Only the Base64 encoded hash and salt are persisted; clear text passwords are never
 * logged or stored. Temporary passwords are generated here and returned exactly once by
 * the API layer.
 */
@Component
public class PasswordHasher {

    static final int ITERATIONS = 210_000;
    static final int KEY_LENGTH_BITS = 256;
    static final int SALT_BYTES = 16;
    static final int MIN_LENGTH = 10;
    static final int MAX_LENGTH = 128;

    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String FINGERPRINT_PEPPER = "tenancy-center-password-fingerprint-v1";
    private static final String UPPER = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String LOWER = "abcdefghijkmnpqrstuvwxyz";
    private static final String DIGITS = "23456789";
    private static final String SYMBOLS = "!@#$%^&*";

    private final SecureRandom random = new SecureRandom();

    /** @return the generated salt + hash pair for the given clear text password. */
    public Credential hash(String password) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] derived = derive(password, salt, ITERATIONS);
        return new Credential(Base64.getEncoder().encodeToString(derived),
                Base64.getEncoder().encodeToString(salt));
    }

    public boolean matches(String password, String hashBase64, String saltBase64) {
        if (password == null || hashBase64 == null || saltBase64 == null) {
            return false;
        }
        try {
            byte[] salt = Base64.getDecoder().decode(saltBase64);
            byte[] expected = Base64.getDecoder().decode(hashBase64);
            byte[] actual = derive(password, salt, ITERATIONS);
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    /**
     * Deterministic equality fingerprint used only for multi-tenant membership checks
     * (docs/20 section 3): two tenant accounts share a person when their plaintext passwords
     * are equal even though the PBKDF2 salts differ. Never used for authentication.
     */
    public String fingerprint(String password) {
        if (password == null) {
            return null;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((FINGERPRINT_PEPPER + ":" + password)
                    .getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(value);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    /** Enforces the docs/11 password policy: &gt;= 10 chars with letters and digits. */
    public void validate(String password, String username) {
        if (password == null || password.isBlank()) {
            throw BizException.invalid("password is required");
        }
        if (password.length() < MIN_LENGTH || password.length() > MAX_LENGTH) {
            throw BizException.invalid("password must be between " + MIN_LENGTH + " and "
                    + MAX_LENGTH + " characters");
        }
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (char value : password.toCharArray()) {
            if (Character.isLetter(value)) {
                hasLetter = true;
            } else if (Character.isDigit(value)) {
                hasDigit = true;
            }
        }
        if (!hasLetter || !hasDigit) {
            throw BizException.invalid("password must contain both letters and digits");
        }
        if (username != null && password.equalsIgnoreCase(username)) {
            throw BizException.invalid("password must not be equal to the username");
        }
    }

    /** One-time temporary password that always satisfies {@link #validate}. */
    public String generateTemporary() {
        StringBuilder builder = new StringBuilder(16);
        builder.append(UPPER.charAt(random.nextInt(UPPER.length())));
        builder.append(LOWER.charAt(random.nextInt(LOWER.length())));
        builder.append(DIGITS.charAt(random.nextInt(DIGITS.length())));
        builder.append(SYMBOLS.charAt(random.nextInt(SYMBOLS.length())));
        String alphabet = UPPER + LOWER + DIGITS;
        while (builder.length() < 16) {
            builder.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        char[] chars = builder.toString().toCharArray();
        for (int index = chars.length - 1; index > 0; index--) {
            int swap = random.nextInt(index + 1);
            char tmp = chars[index];
            chars[index] = chars[swap];
            chars[swap] = tmp;
        }
        return new String(chars);
    }

    /** Normalises usernames exactly the way the directory stores them. */
    public String normalizeUsername(String username) {
        return username == null ? null : username.trim().toLowerCase(Locale.ROOT);
    }

    private byte[] derive(String password, byte[] salt, int iterations) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, KEY_LENGTH_BITS);
        try {
            return SecretKeyFactory.getInstance(ALGORITHM).generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException ex) {
            throw new IllegalStateException("PBKDF2 password hashing is unavailable", ex);
        } finally {
            spec.clearPassword();
        }
    }

    /** Base64 encoded hash + salt as persisted in {@code accounts}. */
    public record Credential(String passwordHash, String passwordSalt) {
    }
}
