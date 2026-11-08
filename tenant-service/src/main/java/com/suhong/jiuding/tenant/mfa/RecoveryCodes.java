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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * One-time MFA recovery codes (docs/16 section 3): 8 random base32 codes, only their
 * SHA-256 hex digests are persisted; a code can be used exactly once.
 */
public final class RecoveryCodes {

    public static final int COUNT = 8;
    private static final int CODE_LENGTH = 8;
    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
    private static final SecureRandom RANDOM = new SecureRandom();

    private RecoveryCodes() {
    }

    public static List<String> generate() {
        Set<String> codes = new LinkedHashSet<>();
        while (codes.size() < COUNT) {
            StringBuilder code = new StringBuilder(CODE_LENGTH);
            for (int index = 0; index < CODE_LENGTH; index++) {
                code.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
            }
            codes.add(code.toString());
        }
        return new ArrayList<>(codes);
    }

    public static String hash(String code) {
        String normalized = normalize(code);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(normalized.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                hex.append(String.format("%02x", value));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    public static String joinHashes(List<String> codes) {
        return String.join(",", codes.stream().map(RecoveryCodes::hash).toList());
    }

    /**
     * Consumes one recovery code.
     *
     * @return the remaining comma separated hashes, or {@code null} when the code is unknown
     */
    public static String consume(String storedHashes, String candidate) {
        if (storedHashes == null || storedHashes.isBlank() || candidate == null) {
            return null;
        }
        String candidateHash = hash(candidate);
        String[] hashes = storedHashes.split(",");
        List<String> remaining = new ArrayList<>(hashes.length);
        boolean matched = false;
        for (String stored : hashes) {
            if (stored.isBlank()) {
                continue;
            }
            if (!matched && MessageDigest.isEqual(stored.getBytes(StandardCharsets.UTF_8),
                    candidateHash.getBytes(StandardCharsets.UTF_8))) {
                matched = true;
                continue;
            }
            remaining.add(stored);
        }
        return matched ? String.join(",", remaining) : null;
    }

    public static String normalize(String code) {
        return code == null ? "" : code.trim().replace(" ", "").replace("-", "").toUpperCase(Locale.ROOT);
    }
}
