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

/**
 * Minimal RFC4648 Base32 codec (no padding) used for TOTP secrets and recovery codes.
 * Secrets are generated as base32 strings so authenticator apps can consume them directly.
 */
public final class Base32 {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    private Base32() {
    }

    public static String encode(byte[] data) {
        if (data == null || data.length == 0) {
            return "";
        }
        StringBuilder output = new StringBuilder((data.length * 8 + 4) / 5);
        int buffer = 0;
        int bitsLeft = 0;
        for (byte value : data) {
            buffer = (buffer << 8) | (value & 0xFF);
            bitsLeft += 8;
            while (bitsLeft >= 5) {
                output.append(ALPHABET.charAt((buffer >> (bitsLeft - 5)) & 0x1F));
                bitsLeft -= 5;
            }
        }
        if (bitsLeft > 0) {
            output.append(ALPHABET.charAt((buffer << (5 - bitsLeft)) & 0x1F));
        }
        return output.toString();
    }

    public static byte[] decode(String encoded) {
        if (encoded == null) {
            return new byte[0];
        }
        String normalized = encoded.trim().replace("=", "").replace(" ", "").toUpperCase();
        if (normalized.isEmpty()) {
            return new byte[0];
        }
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(normalized.length() * 5 / 8 + 1);
        int buffer = 0;
        int bitsLeft = 0;
        for (int index = 0; index < normalized.length(); index++) {
            int value = ALPHABET.indexOf(normalized.charAt(index));
            if (value < 0) {
                throw new IllegalArgumentException("invalid base32 character: " + normalized.charAt(index));
            }
            buffer = (buffer << 5) | value;
            bitsLeft += 5;
            if (bitsLeft >= 8) {
                out.write((buffer >> (bitsLeft - 8)) & 0xFF);
                bitsLeft -= 8;
            }
        }
        return out.toByteArray();
    }
}
